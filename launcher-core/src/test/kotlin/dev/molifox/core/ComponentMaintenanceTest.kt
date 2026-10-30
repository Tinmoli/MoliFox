// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证同加载器小版本升级：候选版本、成功替换配置、失败回滚，以及旧的更换加载器入口已移除。

package dev.molifox.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComponentMaintenanceTest : ComponentMaintenanceTestSupport() {
    @Test
    fun `upgrade offers only newer versions of the same loader and game version`() =
        runBlocking {
            MockWebServer().use { server ->
                server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            when (request.path) {
                                "/net/minecraftforge/forge/maven-metadata.xml" ->
                                    MockResponse()
                                        .setBody(
                                            """
                                            <metadata><versioning><versions>
                                              <version>1.20.1-47.1.0</version>
                                              <version>1.20.1-47.2.0</version>
                                              <version>1.21.1-52.0.1</version>
                                            </versions></versioning></metadata>
                                            """.trimIndent(),
                                        )
                                else -> MockResponse().setResponseCode(404)
                            }
                    }
                val store = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))
                val instance =
                    GameInstance(
                        name = "Forge 实例",
                        installed = true,
                        gameVersion = gameVersion,
                        versionId = "Forge 实例",
                        loader = LoaderType.Forge,
                        loaderVersion = "47.1.0",
                    )
                NetworkClient(source = { "official" }).use { network ->
                    val installer = installer(server, store, network)
                    val available = installer.loaderVersions(LoaderType.Forge, gameVersion)
                    // 同一 MC 版本里更新的 Forge 会出现；另一个 MC 版本上更新的 Forge 不会出现。
                    assertEquals(listOf("47.2.0", "47.1.0"), available.map { it.version })
                    assertEquals(
                        listOf("47.2.0"),
                        loaderUpgradeCandidates(instance, available).map { it.version },
                    )
                    assertTrue(available.none { it.version.startsWith("52.") })
                }
            }
        }

    @Test
    fun `vanilla and already latest instances offer no upgrade but external can be a candidate`() {
        val vanilla = instanceWithLoader("Vanilla", LoaderType.Vanilla)
        val latest = instanceWithLoader("Fabric", LoaderType.Fabric, newLoader)
        val external =
            instanceWithLoader("External", LoaderType.Fabric, oldLoader, externalProfile = true)
        val versions = listOf(LoaderVersion(newLoader, true), LoaderVersion(oldLoader, true))
        assertUpgradeCandidates(vanilla, latest, external, versions)
        // 原版实例直接提交升级也会被拒绝，不会变成「加装加载器」。
        assertVanillaUpgradeRejected(vanilla)
    }

    @Test
    fun `loader upgrade keeps a self-contained profile in the instance directory`() =
        runBlocking {
            MockWebServer().use { server ->
                val store = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))
                NetworkClient(source = { "official" }).use { network ->
                    val (installed, _) = installOldFabricInstance(server, store, network)
                    val folder = store.versionGamePath(installed)
                    val jsonPath = folder.resolve("升级测试实例.json")
                    val gameDirectory =
                        store.instancePath(installed.id).apply { createDirectories() }
                    gameDirectory
                        .resolve("mods")
                        .createDirectories()
                        .resolve("keep.jar")
                        .writeText("mod")
                    assertOldLoaderInstalled(jsonPath)
                    installer(server, store, network)
                        .upgradeLoader(installed.id, newLoader, JobContext(store, "upgrade"))
                    val upgraded = store.instance(installed.id)
                    assertEquals("升级测试实例", upgraded.versionId)
                    assertEquals("升级测试实例", upgraded.gameDirectoryName)
                    assertEquals(LoaderType.Fabric, upgraded.loader)
                    assertEquals(newLoader, upgraded.loaderVersion)
                    assertTrue(upgraded.installed)
                    assertEquals(listOf("升级测试实例"), versionDirectories(store))
                    assertSelfContainedUpgrade(store, jsonPath, folder, gameDirectory)
                }
            }
        }

    @Test
    fun `a failed upgrade restores the previous profile`() =
        runBlocking {
            MockWebServer().use { server ->
                val store = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))
                NetworkClient(source = { "official" }).use { network ->
                    val (installed, fixture) = installOldFabricInstance(server, store, network)
                    val jsonPath = store.versionGamePath(installed).resolve("升级测试实例.json")
                    val before = jsonPath.readText()
                    // 新加载器库下载失败：配置已经写盘，升级必须回滚到旧 JSON。
                    server.dispatcher =
                        dispatcher(fixture, newLibraryUrl = "${fixture.base}/broken.jar")
                    assertFails {
                        installer(server, store, network)
                            .upgradeLoader(installed.id, newLoader, JobContext(store, "upgrade"))
                    }
                    assertEquals(before, jsonPath.readText())
                    val after = store.instance(installed.id)
                    assertEquals(oldLoader, after.loaderVersion)
                    assertEquals(LoaderType.Fabric, after.loader)
                    assertTrue(after.installed)
                    assertNoComponentBackups(store)
                }
            }
        }

    @Test
    fun `the loader switch entry points no longer exist`() {
        val controllerMethods = LauncherController::class.java.methods.map { it.name }
        assertFalse("changeInstanceLoader" in controllerMethods)
        assertFalse("restoreInstanceComponents" in controllerMethods)
        assertTrue("upgradeInstanceLoader" in controllerMethods)
        val installerMethods = MinecraftInstaller::class.java.methods.map { it.name }
        assertFalse("changeLoader" in installerMethods)
        assertFalse("restoreComponents" in installerMethods)
        assertTrue("upgradeLoader" in installerMethods)
    }

    /** 只带加载器信息的实例：原版没有加载器版本，外部实例带 externalProfile 标记。 */
    private fun instanceWithLoader(
        name: String,
        loader: LoaderType,
        loaderVersion: String = "",
        externalProfile: Boolean = false,
    ): GameInstance =
        GameInstance(
            name = name,
            installed = true,
            gameVersion = gameVersion,
            externalProfile = externalProfile,
            loader = loader,
            loaderVersion = loaderVersion,
        )

    /** 原版与已是最新的实例都没有候选；外部实例可以升级，是否显示按钮由原 JSON 的可合并性预检决定。 */
    private fun assertUpgradeCandidates(
        vanilla: GameInstance,
        latest: GameInstance,
        external: GameInstance,
        versions: List<LoaderVersion>,
    ) {
        assertTrue(loaderUpgradeCandidates(vanilla, versions).isEmpty())
        assertTrue(loaderUpgradeCandidates(latest, versions).isEmpty())
        assertEquals(
            listOf(newLoader),
            loaderUpgradeCandidates(external, versions).map { it.version },
        )
    }

    /** 原版实例直接提交升级会被拒绝，实例保持原样。 */
    private fun assertVanillaUpgradeRejected(vanilla: GameInstance) {
        val store = LauncherStore(DesktopPlatform(), temporary.resolve("vanilla"))
        store.mutate { it.copy(instances = listOf(vanilla)) }
        NetworkClient().use { network ->
            assertFails {
                runBlocking {
                    MinecraftInstaller(store, network, JavaService(store, network))
                        .upgradeLoader(vanilla.id, newLoader, JobContext(store, "test"))
                }
            }
        }
        assertEquals(vanilla, store.instance(vanilla.id))
    }

    /** 升级前：配置里的加载器库还是旧版本。 */
    private fun assertOldLoaderInstalled(jsonPath: Path) {
        val before = readObject(jsonPath)
        assertEquals(
            "net.fabricmc:fabric-loader:$oldLoader",
            before.array("libraries").map { it.asJsonObject.string("name") }.first {
                it.startsWith("net.fabricmc:fabric-loader:")
            },
        )
    }

    /** 升级后：配置自包含、客户端 JAR 与用户文件保留、备份已丢弃。 */
    private fun assertSelfContainedUpgrade(
        store: LauncherStore,
        jsonPath: Path,
        folder: Path,
        gameDirectory: Path,
    ) {
        val json = readObject(jsonPath)
        assertEquals("升级测试实例", json.string("id"))
        assertEquals("", json.string("inheritsFrom"))
        assertTrue(json.boolean("molifoxManaged"))
        assertEquals(gameVersion, json.string("_minecraftVersion"))
        assertEquals("fixture.KnotClient", json.string("mainClass"))
        assertTrue(json.obj("downloads").has("client"))
        val loaderLibraries =
            json.array("libraries").map { it.asJsonObject.string("name") }.filter {
                it.startsWith("net.fabricmc:fabric-loader:")
            }
        assertEquals(listOf("net.fabricmc:fabric-loader:$newLoader"), loaderLibraries)
        assertTrue(folder.resolve("升级测试实例.jar").isRegularFile())
        assertEquals("mod", gameDirectory.resolve("mods/keep.jar").readText())
        // 升级成功后备份即丢弃，不会随每次升级累积。
        assertNoComponentBackups(store)
    }
}
