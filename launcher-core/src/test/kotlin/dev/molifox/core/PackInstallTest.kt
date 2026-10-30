// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证 MCBBS 与 CurseForge 整合包的安装、失败与修复行为。

package dev.molifox.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackInstallTest : PackFormatFixtures() {
    @TempDir override lateinit var temporary: Path

    @Test
    fun `MCBBS CurseForge install supports failure and repair without credentials`() =
        runBlocking {
            val store = store()
            configureGlobalLaunchSettings(store)
            MockWebServer().use { server ->
                val requests = CopyOnWriteArrayList<RecordedRequest>()
                val base = curseForgeBase(store, server)
                val fixture = curseForgeFixture(base, requests)
                fixture.serve(server)
                NetworkClient().use { network ->
                    val service = installService(store, network, base)
                    val instance = installAndRepair(store, service, fixture)
                    assertImportedLaunchOptions(store, instance)
                    assertOverridesSurviveRepair(store, service, instance)
                    assertWrongPackKeepsInstance(store, service, instance)
                    assertTrue(requests.all { it.getHeader("Authorization") == null })
                    PackJavaArgumentsCheck(temporary).verify(store, network, instance.id)
                }
            }
        }

    /** 全局设置：不隔离版本、固定 Java、带全局 JVM 与游戏参数。 */
    private fun configureGlobalLaunchSettings(store: LauncherStore) {
        store.mutate {
            it.copy(
                settings =
                    it.settings.copy(
                        isolateVersions = false,
                        memoryMb = 512,
                        initialMemoryMb = 128,
                        javaPath =
                            Path
                                .of(
                                    System.getProperty("java.home"),
                                    "bin",
                                    store.platform.javaExecutable,
                                ).toString(),
                        extraJvmArgs = "-Dglobal.message=global",
                        advancedLaunch = AdvancedLaunchSettings("--demo"),
                    ),
            )
        }
    }

    /** 记下 MockWebServer 的地址，让 CurseForge 后端指过去。 */
    private fun curseForgeBase(
        store: LauncherStore,
        server: MockWebServer,
    ): String {
        val serverBase = server.loopbackUrl("").toString().trimEnd('/')
        store.mutate {
            it.copy(settings = it.settings.copy(curseForgeBaseUrl = serverBase))
        }
        return server.loopbackUrl("/").toString().trimEnd('/')
    }

    private fun curseForgeFixture(
        base: String,
        requests: MutableList<RecordedRequest>,
    ) = CurseForgePackFixture(
        base = base,
        requests = requests,
        client = zipBytes(mapOf("fixture.txt" to "fixture")),
        resource = "resource".toByteArray(),
    )

    private fun installService(
        store: LauncherStore,
        network: NetworkClient,
        base: String,
    ) = ContentService(
        store,
        network,
        MinecraftInstaller(store, network, JavaService(store, network), "$base/manifest.json"),
    )

    /** 带 CurseForge 文件、JVM 与游戏参数的整合包归档。 */
    private fun cursePackSource(): Path =
        pack(
            mcbbs().apply {
                obj("launchInfo")
                    .array("javaArgument")
                    .add("-Dpack.message=茉狐 value with spaces")
                obj("launchInfo")
                    .add(
                        "launchArgument",
                        gson.toJsonTree(
                            listOf("--server", "茉狐 server", "--port", "25565"),
                        ),
                    )
                array("files")
                    .add(
                        gson.toJsonTree(
                            mapOf(
                                "type" to "curse",
                                "projectID" to 100,
                                "fileID" to 200,
                                "url" to "https://invalid.example/not-used",
                            ),
                        ),
                    )
            },
        )

    /** 第一次导入因客户端 JAR 404 失败留下未安装记录，放开下载后必须修复成功。 */
    private suspend fun installAndRepair(
        store: LauncherStore,
        service: ContentService,
        fixture: CurseForgePackFixture,
    ): GameInstance {
        val source = cursePackSource()
        assertEquals(2, service.previewPack(source).requiredFiles)
        assertFails { service.importPack(source, JobContext(store, "test")) }
        val instance = store.data.instances.single()
        assertFalse(instance.installed)
        assertEquals(VersionIsolation.Inherit, instance.isolation)
        fixture.failDownload = false
        service.importPack(
            Path.of(instance.packFile),
            JobContext(store, "test"),
            instance.id,
        )
        assertEquals(1, store.data.instances.size)
        assertTrue(store.instance(instance.id).installed)
        val root = store.instancePath(instance.id)
        assertEquals("setting", root.resolve("config/test.txt").readText())
        assertEquals("resource", root.resolve("resourcepacks/resource.zip").readText())
        return instance
    }

    /** 全局与整合包参数合并：全局在前，包参数在后，都不丢。 */
    private fun assertImportedLaunchOptions(
        store: LauncherStore,
        instance: GameInstance,
    ) {
        val imported = store.instance(instance.id)
        assertEquals(
            listOf("-Dglobal.message=global", "-Dpack.message=茉狐 value with spaces"),
            splitArguments(imported.extraJvmArgs),
        )
        assertEquals(
            listOf("--demo", "--server", "茉狐 server", "--port", "25565"),
            extraGameArguments(requireNotNull(imported.advancedLaunch)),
        )
    }

    /** 修复重装不能覆盖用户改过的参数。 */
    private suspend fun assertOverridesSurviveRepair(
        store: LauncherStore,
        service: ContentService,
        instance: GameInstance,
    ) {
        store.updateInstance(instance.id) {
            it.copy(
                extraJvmArgs = it.extraJvmArgs + " -Duser.custom=kept",
                advancedLaunch =
                    requireNotNull(it.advancedLaunch)
                        .copy(
                            extraGameArgs =
                                it.advancedLaunch.extraGameArgs + " --user-value kept",
                        ),
            )
        }
        val customized = store.instance(instance.id)
        service.importPack(
            Path.of(instance.packFile),
            JobContext(store, "test"),
            instance.id,
        )
        assertEquals(customized.extraJvmArgs, store.instance(instance.id).extraJvmArgs)
        assertEquals(
            customized.advancedLaunch,
            store.instance(instance.id).advancedLaunch,
        )
    }

    /** 换成别的游戏版本的包必须被拒绝，且已有实例保持原版本。 */
    private suspend fun assertWrongPackKeepsInstance(
        store: LauncherStore,
        service: ContentService,
        instance: GameInstance,
    ) {
        val wrongPack =
            pack(
                mcbbs().apply {
                    array("addons").first().asJsonObject.addProperty("version", "1.21")
                },
            )
        assertFails {
            service.importPack(wrongPack, JobContext(store, "test"), instance.id)
        }
        assertEquals("1.20.1", store.instance(instance.id).gameVersion)
    }
}
