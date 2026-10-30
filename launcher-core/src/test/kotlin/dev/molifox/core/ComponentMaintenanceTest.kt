// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证同加载器小版本升级：候选版本、成功替换配置、失败回滚，以及旧的更换加载器入口已移除。

package dev.molifox.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComponentMaintenanceTest {
    @TempDir lateinit var temporary: Path

    private val gameVersion = "1.20.1"
    private val oldLoader = "0.16.14"
    private val newLoader = "0.17.0"

    private class Fixture(
        val base: String,
        val profile: String,
        val clientJar: ByteArray,
        val baseLibrary: ByteArray,
        val oldLibrary: ByteArray,
        val newLibrary: ByteArray,
    )

    private fun jarBytes(entry: String): ByteArray =
        ByteArrayOutputStream()
            .also { buffer ->
                ZipOutputStream(buffer).use { zip ->
                    zip.putNextEntry(ZipEntry(entry))
                    zip.write(byteArrayOf(1, 2, 3))
                    zip.closeEntry()
                }
            }.toByteArray()

    private fun baseUrl(server: MockWebServer): String =
        server.loopbackUrl("/").toString().removeSuffix("/")

    private fun fixture(server: MockWebServer): Fixture {
        val base = baseUrl(server)
        val clientJar = jarBytes("fixture/Main.class")
        val baseLibrary = jarBytes("fixture/Base.class")
        val oldLibrary = jarBytes("fabric/OldLoader.class")
        val newLibrary = jarBytes("fabric/NewLoader.class")
        val profile =
            gson.toJson(
                mapOf(
                    "id" to gameVersion,
                    "type" to "release",
                    "mainClass" to "fixture.Main",
                    "javaVersion" to mapOf("majorVersion" to 17),
                    "arguments" to
                        mapOf(
                            "game" to listOf("--username", "\${auth_player_name}"),
                            "jvm" to listOf("-Dfixture.base=true"),
                        ),
                    "downloads" to
                        mapOf(
                            "client" to
                                mapOf(
                                    "url" to "$base/client.jar",
                                    "sha1" to sha1(clientJar),
                                    "size" to clientJar.size,
                                ),
                        ),
                    "libraries" to
                        listOf(
                            mapOf(
                                "name" to "fixture:base:1",
                                "downloads" to
                                    mapOf(
                                        "artifact" to
                                            mapOf(
                                                "url" to "$base/base-library.jar",
                                                "path" to "fixture/base/1/base-1.jar",
                                                "sha1" to sha1(baseLibrary),
                                                "size" to baseLibrary.size,
                                            ),
                                    ),
                            ),
                        ),
                ),
            )
        return Fixture(base, profile, clientJar, baseLibrary, oldLibrary, newLibrary)
    }

    private fun loaderProfile(
        version: String,
        libraryUrl: String,
        loaderLibrary: ByteArray,
    ): String =
        gson.toJson(
            mapOf(
                "id" to "fabric-loader-$version-$gameVersion",
                "inheritsFrom" to gameVersion,
                "mainClass" to "fixture.KnotClient",
                "arguments" to mapOf("game" to emptyList<Any>(), "jvm" to emptyList<Any>()),
                "libraries" to
                    listOf(
                        mapOf(
                            "name" to "net.fabricmc:fabric-loader:$version",
                            "downloads" to
                                mapOf(
                                    "artifact" to
                                        mapOf(
                                            "url" to libraryUrl,
                                            "path" to
                                                "net/fabricmc/fabric-loader/$version/fabric-loader-$version.jar",
                                            "sha1" to sha1(loaderLibrary),
                                            "size" to loaderLibrary.size,
                                        ),
                                ),
                        ),
                    ),
            ),
        )

    private fun manifest(fixture: Fixture): String =
        gson.toJson(
            mapOf(
                "versions" to
                    listOf(
                        mapOf(
                            "id" to gameVersion,
                            "type" to "release",
                            "url" to "${fixture.base}/version.json",
                            "sha1" to sha1(fixture.profile.toByteArray()),
                        ),
                    ),
            ),
        )

    /** 同一个本地服务既提供安装所需文件，也提供升级所需的新加载器配置；[newLibraryUrl] 可指向 404 制造失败。 */
    private fun dispatcher(
        fixture: Fixture,
        newLibraryUrl: String? = null,
    ): Dispatcher =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when (request.path) {
                    "/manifest.json" -> MockResponse().setBody(manifest(fixture))
                    "/version.json" -> MockResponse().setBody(fixture.profile)
                    "/client.jar" -> MockResponse().setBody(Buffer().write(fixture.clientJar))
                    "/base-library.jar" ->
                        MockResponse().setBody(Buffer().write(fixture.baseLibrary))
                    "/old-loader.jar" ->
                        MockResponse().setBody(Buffer().write(fixture.oldLibrary))
                    "/new-loader.jar" ->
                        MockResponse().setBody(Buffer().write(fixture.newLibrary))
                    "/v2/versions/loader/$gameVersion/$oldLoader/profile/json" ->
                        MockResponse()
                            .setBody(
                                loaderProfile(
                                    oldLoader,
                                    "${fixture.base}/old-loader.jar",
                                    fixture.oldLibrary,
                                ),
                            )
                    "/v2/versions/loader/$gameVersion/$newLoader/profile/json" ->
                        MockResponse()
                            .setBody(
                                loaderProfile(
                                    newLoader,
                                    newLibraryUrl ?: "${fixture.base}/new-loader.jar",
                                    fixture.newLibrary,
                                ),
                            )
                    else -> MockResponse().setResponseCode(404)
                }
        }

    private fun installer(
        server: MockWebServer,
        store: LauncherStore,
        network: NetworkClient,
    ) = MinecraftInstaller(
        store,
        network,
        JavaService(store, network),
        "${baseUrl(server)}/manifest.json",
        baseUrl(server),
        fabricMetaBase = baseUrl(server),
        quiltMetaBase = baseUrl(server),
        forgeMavenBase = baseUrl(server),
    )

    /** 安装一个 Fabric 旧版本实例，返回实例与可复用的本地服务数据。 */
    private suspend fun installOldFabricInstance(
        server: MockWebServer,
        store: LauncherStore,
        network: NetworkClient,
    ): Pair<GameInstance, Fixture> {
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        val fixture = fixture(server)
        server.dispatcher = dispatcher(fixture)
        val installer = installer(server, store, network)
        val instance =
            installer.createInstance(
                InstallRequest("升级测试实例", gameVersion, LoaderType.Fabric, oldLoader),
            )
        installer.install(instance.id, JobContext(store, "install"))
        return store.instance(instance.id) to fixture
    }

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
        val vanilla =
            GameInstance(
                name = "Vanilla",
                installed = true,
                gameVersion = gameVersion,
                loader = LoaderType.Vanilla,
            )
        val latest =
            GameInstance(
                name = "Fabric",
                installed = true,
                gameVersion = gameVersion,
                loader = LoaderType.Fabric,
                loaderVersion = newLoader,
            )
        val external =
            GameInstance(
                name = "External",
                installed = true,
                gameVersion = gameVersion,
                externalProfile = true,
                loader = LoaderType.Fabric,
                loaderVersion = oldLoader,
            )
        val versions = listOf(LoaderVersion(newLoader, true), LoaderVersion(oldLoader, true))
        assertTrue(loaderUpgradeCandidates(vanilla, versions).isEmpty())
        assertTrue(loaderUpgradeCandidates(latest, versions).isEmpty())
        // 外部实例也可以升级；是否显示按钮由原 JSON 的可合并性预检决定。
        assertEquals(
            listOf(newLoader),
            loaderUpgradeCandidates(external, versions).map { it.version },
        )
        // 原版实例直接提交升级也会被拒绝，不会变成「加装加载器」。
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
                    val before = readObject(jsonPath)
                    assertEquals(
                        "net.fabricmc:fabric-loader:$oldLoader",
                        before.array("libraries").map { it.asJsonObject.string("name") }.first {
                            it.startsWith("net.fabricmc:fabric-loader:")
                        },
                    )
                    installer(server, store, network)
                        .upgradeLoader(installed.id, newLoader, JobContext(store, "upgrade"))
                    val upgraded = store.instance(installed.id)
                    assertEquals("升级测试实例", upgraded.versionId)
                    assertEquals("升级测试实例", upgraded.gameDirectoryName)
                    assertEquals(LoaderType.Fabric, upgraded.loader)
                    assertEquals(newLoader, upgraded.loaderVersion)
                    assertTrue(upgraded.installed)
                    assertEquals(listOf("升级测试实例"), versionDirectories(store))
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

    /** 在外部游戏根写一个基础版本和一个外部加载器版本（没有 molifoxManaged 标记）。 */
    private fun writeExternalFabricVersion(
        root: Path,
        fixture: Fixture,
        loader: String,
        libraryUrl: String,
        library: ByteArray,
        parentPresent: Boolean = true,
    ): Path {
        if (parentPresent) {
            val base = root.resolve("versions/$gameVersion").createDirectories()
            base.resolve("$gameVersion.json").writeText(fixture.profile)
            base.resolve("$gameVersion.jar").writeBytes(fixture.clientJar)
        }
        val folder = root.resolve("versions/ForeignFabric").createDirectories()
        folder.resolve("ForeignFabric.json").writeText(
            gson.toJson(
                mapOf(
                    "id" to "ForeignFabric",
                    "inheritsFrom" to gameVersion,
                    "type" to "release",
                    "time" to "2026-10-29T10:00:00Z",
                    "mainClass" to "fixture.OldKnotClient",
                    "foreignCustom" to mapOf("keep" to "yes"),
                    "libraries" to
                        listOf(
                            mapOf(
                                "name" to "net.fabricmc:fabric-loader:$loader",
                                "downloads" to
                                    mapOf(
                                        "artifact" to
                                            mapOf(
                                                "url" to libraryUrl,
                                                "path" to
                                                    "net/fabricmc/fabric-loader/$loader/fabric-loader-$loader.jar",
                                                "sha1" to sha1(library),
                                                "size" to library.size,
                                            ),
                                    ),
                            ),
                        ),
                    "arguments" to
                        mapOf(
                            "game" to emptyList<Any>(),
                            "jvm" to listOf("-DForeignKnot=true"),
                        ),
                ),
            ),
        )
        return folder
    }

    private fun registerExternalRoot(
        store: LauncherStore,
        root: Path,
    ) {
        val directory = GameDirectory(name = "外部目录", path = root.toString())
        store.mutate {
            it.copy(gameDirectories = listOf(directory), activeGameDirectoryId = directory.id)
        }
    }

    @Test
    fun `external upgrade merges into the original profile keeps its fields and backs it up`() =
        runBlocking {
            MockWebServer().use { server ->
                server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
                val fixture = fixture(server)
                server.dispatcher = dispatcher(fixture)
                val store = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))
                NetworkClient(source = { "official" }).use { network ->
                    val root = temporary.resolve("foreign").createDirectories()
                    registerExternalRoot(store, root)
                    val folder =
                        writeExternalFabricVersion(
                            root,
                            fixture,
                            oldLoader,
                            "${fixture.base}/old-loader.jar",
                            fixture.oldLibrary,
                        )
                    store.refreshInstances()
                    val external = store.instance("ForeignFabric")
                    assertTrue(external.externalProfile)
                    assertEquals(LoaderType.Fabric, external.loader)
                    assertEquals(oldLoader, external.loaderVersion)
                    assertTrue(external.installed)
                    val jsonPath = folder.resolve("ForeignFabric.json")
                    val before = Files.readAllBytes(jsonPath)
                    val mods =
                        folder.resolve("mods").createDirectories().resolve("keep.jar").apply {
                            writeText("keep")
                        }
                    val sibling = folder.resolve("foreign.txt").apply { writeText("untouched") }
                    val installer = installer(server, store, network)
                    assertNull(installer.externalLoaderUpgradeBlocker(external))
                    installer.upgradeLoader(
                        external.id,
                        newLoader,
                        JobContext(store, "external-upgrade"),
                    )
                    val json = readObject(jsonPath)
                    // 原结构：id、inheritsFrom 与自定义字段都保留，且不写自有标记/游戏版本字段。
                    assertEquals("ForeignFabric", json.string("id"))
                    assertEquals(gameVersion, json.string("inheritsFrom"))
                    assertFalse(json.has("molifoxManaged"))
                    assertFalse(json.has("_minecraftVersion"))
                    assertFalse(json.has("gameVersion"))
                    assertEquals("fixture.KnotClient", json.string("mainClass"))
                    assertEquals("yes", json.obj("foreignCustom").string("keep"))
                    val names = json.array("libraries").map { it.asJsonObject.string("name") }
                    assertTrue(names.none { it == "net.fabricmc:fabric-loader:$oldLoader" })
                    assertEquals(
                        listOf("net.fabricmc:fabric-loader:$newLoader"),
                        names.filter { it.startsWith("net.fabricmc:fabric-loader:") },
                    )
                    assertEquals(newLoader, store.instance(external.id).loaderVersion)
                    // 其它文件原样保留。
                    assertEquals("keep", mods.readText())
                    assertEquals("untouched", sibling.readText())
                    val backups =
                        folder.listDirectoryEntries().filter {
                            it.name.startsWith("ForeignFabric.json.molifox-backup-")
                        }
                    assertEquals(1, backups.size)
                    assertTrue(
                        backups.single().name.matches(
                            Regex("ForeignFabric\\.json\\.molifox-backup-\\d{8}-\\d{6}(-\\d+)?"),
                        ),
                    )
                    assertContentEquals(before, Files.readAllBytes(backups.single()))
                }
            }
        }

    @Test
    fun `failed external upgrade restores the original json byte for byte`() =
        runBlocking {
            MockWebServer().use { server ->
                server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
                val fixture = fixture(server)
                server.dispatcher = dispatcher(fixture)
                val store = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))
                NetworkClient(source = { "official" }).use { network ->
                    val root = temporary.resolve("foreign-failure").createDirectories()
                    registerExternalRoot(store, root)
                    val folder =
                        writeExternalFabricVersion(
                            root,
                            fixture,
                            oldLoader,
                            "${fixture.base}/old-loader.jar",
                            fixture.oldLibrary,
                        )
                    store.refreshInstances()
                    val external = store.instance("ForeignFabric")
                    val jsonPath = folder.resolve("ForeignFabric.json")
                    val before = Files.readAllBytes(jsonPath)
                    val sibling = folder.resolve("foreign.txt").apply { writeText("untouched") }
                    server.dispatcher =
                        dispatcher(fixture, newLibraryUrl = "${fixture.base}/broken.jar")
                    assertFails {
                        installer(server, store, network).upgradeLoader(
                            external.id,
                            newLoader,
                            JobContext(store, "external-upgrade-failure"),
                        )
                    }
                    assertContentEquals(before, Files.readAllBytes(jsonPath))
                    assertEquals(oldLoader, store.instance(external.id).loaderVersion)
                    assertEquals("untouched", sibling.readText())
                    // 备份保留，且与恢复后的 JSON 内容一致。
                    val backups =
                        folder.listDirectoryEntries().filter {
                            it.name.startsWith("ForeignFabric.json.molifox-backup-")
                        }
                    assertEquals(1, backups.size)
                    assertContentEquals(before, Files.readAllBytes(backups.single()))
                }
            }
        }

    @Test
    fun `external upgrade with a missing base version reports the reason and changes nothing`() {
        val store = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))
        NetworkClient().use { network ->
            val root = temporary.resolve("foreign-missing-base").createDirectories()
            registerExternalRoot(store, root)
            val fixture = Fixture("", "", ByteArray(0), ByteArray(0), ByteArray(0), ByteArray(0))
            val folder =
                writeExternalFabricVersion(
                    root,
                    fixture,
                    oldLoader,
                    "https://example.invalid/loader.jar",
                    ByteArray(0),
                    parentPresent = false,
                )
            store.refreshInstances()
            val instance = store.instance("ForeignFabric")
            assertTrue(instance.externalProfile)
            assertTrue(instance.broken)
            val jsonPath = folder.resolve("ForeignFabric.json")
            val before = Files.readAllBytes(jsonPath)
            val blocker =
                MinecraftInstaller(store, network, JavaService(store, network))
                    .externalLoaderUpgradeBlocker(instance)
            assertNotNull(blocker)
            assertContains(blocker, "基础版本 $gameVersion")
            assertContains(blocker, "不存在")
            assertContentEquals(before, Files.readAllBytes(jsonPath))
            assertTrue(
                folder.listDirectoryEntries().none {
                    it.name.contains(".molifox-backup-")
                },
            )
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

    private fun versionDirectories(store: LauncherStore): List<String> =
        store.minecraftRoot
            .resolve("versions")
            .listDirectoryEntries()
            .map { it.name }
            .sorted()

    private fun assertNoComponentBackups(store: LauncherStore) {
        val folder = store.directories.instanceBackups.resolve("components")
        assertTrue(!folder.exists() || folder.listDirectoryEntries().isEmpty())
    }
}
