// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责用真实 Java 子进程跑完整合包安装、修复与启动的完整场景。

package dev.molifox.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockWebServer
import java.nio.file.Path
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 安装、修复与启动的完整场景；所有断言都建立在真实 Java 子进程的输出上。
 */
internal class LaunchFixtureScenario(
    private val directory: Path,
    private val fixture: LaunchFixtureServer,
    private val server: MockWebServer,
) {
    private val platform = DesktopPlatform()
    private val store = LauncherStore(platform, directory)
    private val javaPath =
        Path
            .of(System.getProperty("java.home"), "bin", platform.javaExecutable)
            .toString()

    /** 安装、修复、正常启动与实例覆盖启动，顺序与原集成测试一致。 */
    suspend fun run() {
        store.mutate {
            it.copy(settings = it.settings.copy(javaPath = javaPath, memoryMb = 768))
        }
        val network = NetworkClient()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val java = JavaService(store, network)
            assertNotNull(
                java.inspect(javaPath),
                "Java inspection must drain process output without blocking",
            )
            val installer =
                MinecraftInstaller(
                    store,
                    network,
                    java,
                    "${fixture.baseUrl}/manifest.json",
                    "${fixture.baseUrl}/objects",
                )
            val accounts = AccountService(store, network, scope)
            accounts.addOffline("MoliFox")
            val instance = installer.createInstance(InstallRequest("我的测试世界", "fixture"))
            val jobs = JobQueue(store, scope)
            installAndRepair(installer, instance, jobs)
            val launcher = GameLauncher(store, installer, java, accounts, scope)
            launchDamagedGame(launcher, instance, jobs)
            configureOverrideLaunch(instance)
            val requestsBefore = launchSecondTime(launcher, instance, jobs)
            assertOverrideArguments(requestsBefore)
            assertInheritedPlan(java, instance)
        } finally {
            scope.cancel()
            network.close()
        }
    }

    /** 实例自己的客户端 JAR。 */
    private fun instanceGameJar(instanceId: String): Path = store.instancePath(instanceId).resolve("我的测试世界.jar")

    /** 先装一次，故意写坏客户端再修复；版本目录必须只落在实例自己的目录里。 */
    private suspend fun installAndRepair(
        installer: MinecraftInstaller,
        instance: GameInstance,
        jobs: JobQueue,
    ) {
        jobs.enqueue("install fixture") { installer.install(instance.id, this) }
        withTimeout(45000) { jobs.awaitIdle() }
        assertEquals(
            JobStatus.Completed,
            store.state.value.jobs
                .first()
                .status,
            store.state.value.jobs
                .first()
                .error,
        )
        assertTrue(store.instance(instance.id).installed)
        // 安装只发布实例自己的目录：配置与客户端 JAR 都在 versions/我的测试世界/ 里。
        assertEquals("我的测试世界", store.instance(instance.id).versionId)
        val gameJar = store.instancePath(instance.id).resolve("我的测试世界.jar")
        assertEquals(sha1(fixture.clientJar), digest(gameJar))
        assertFalse(store.minecraftRoot.resolve("versions/fixture").exists())
        gameJar.writeText("corrupted cache")
        jobs.enqueue("repair fixture") { installer.install(instance.id, this) }
        withTimeout(45000) { jobs.awaitIdle() }
        assertEquals(sha1(fixture.clientJar), digest(gameJar))
    }

    /** 弄坏客户端、依赖与资源文件，启动本身必须自动修好而不是要求手动修复。 */
    private fun damageInstalledFiles(instance: GameInstance) {
        instanceGameJar(instance.id).writeText("damaged after installation")
        store.minecraftRoot
            .resolve("libraries/dev/molifox/fixture/1/fixture-1.jar")
            .deleteExisting()
        store.minecraftRoot
            .resolve("assets/objects/${fixture.assetHash.take(2)}/${fixture.assetHash}")
            .deleteExisting()
        if (store.platform.osName == "windows") {
            store.mutate {
                it.copy(
                    settings =
                        it.settings.copy(
                            extraJvmArgs = "-Dfixture.padding=" + "x".repeat(25_000),
                        ),
                )
            }
        }
    }

    /** 启动一次被弄坏的实例，并核对子进程输出。 */
    private suspend fun launchDamagedGame(
        launcher: GameLauncher,
        instance: GameInstance,
        jobs: JobQueue,
    ) {
        damageInstalledFiles(instance)
        jobs.enqueue("launch fixture") { launcher.launch(instance.id, this) }
        withTimeout(45000) {
            jobs.awaitIdle()
            while (
                store.state.value.logs
                    .none { it.message == "MOLIFOX_GAME_STARTED" } ||
                store.state.value.runningInstanceId != null
            ) {
                if (
                    store.state.value.runningInstanceId == null &&
                    store.state.value.jobs.none {
                        it.status in listOf(JobStatus.Queued, JobStatus.Running)
                    }
                ) {
                    error(
                        "Fixture launch failed: " +
                            store.state.value.logs
                                .joinToString("\n") { it.message },
                    )
                }
                delay(50)
            }
        }
        assertLaunchLogs(instance)
        assertLaunchWorkingState(instance)
        assertRepairedFiles(instance)
    }

    /** 参数、日志配置与本地库路径都必须来自真实启动结果。 */
    private fun assertLaunchLogs(instance: GameInstance) {
        assertTrue(
            store.state.value.logs.any {
                it.message.contains(
                    "PLAYER_ARGS=--username|MoliFox|--accessToken|0|" +
                        "--width|1280|--height|720",
                )
            },
        )
        assertTrue(
            store.state.value.logs.any {
                it.message.contains("LOG_CONFIG=") && it.message.contains("fixture.xml")
            },
        )
        assertTrue(
            store.state.value.logs
                .any { it.message == "NATIVE_FIXTURE=native fixture" },
        )
        assertFalse(
            store
                .instancePath(instance.id)
                .resolve("natives/fixture-native.bin")
                .exists(),
        )
        assertEquals(
            "sound fixture",
            store.minecraftRoot
                .resolve("assets/virtual/fixture/minecraft/sounds/fixture.ogg")
                .readText(),
        )
        assertNotNull(store.instance(instance.id).lastPlayed)
    }

    /** 临时目录与工作目录必须指向实例自己的位置。 */
    private fun assertLaunchWorkingState(instance: GameInstance) {
        assertTrue(
            store.state.value.logs.any {
                it.message == "GAME_TMP=${store.temporaryDirectory}"
            },
        )
        assertTrue(
            store.state.value.logs.any {
                it.message == "GAME_CWD=${store.instancePath(instance.id)}"
            },
        )
    }

    /** 启动顺带修好的文件：摘要与真实类路径。 */
    private fun assertRepairedFiles(instance: GameInstance) {
        assertEquals(sha1(fixture.clientJar), digest(instanceGameJar(instance.id)))
        assertEquals(
            fixture.assetHash,
            digest(
                store.minecraftRoot.resolve(
                    "assets/objects/${fixture.assetHash.take(2)}/${fixture.assetHash}",
                ),
            ),
        )
        val runtimeClasspath =
            store.state.value.logs
                .first { it.message.startsWith("REAL_CLASSPATH=") }
                .message
        assertTrue(
            runtimeClasspath.contains(instanceGameJar(instance.id).toAbsolutePath().toString()),
            "Fabric must be able to see the actual Minecraft jar",
        )
        assertTrue(
            runtimeClasspath.contains("fixture-1.jar"),
            "Loader dependencies must be listed individually",
        )
        assertFalse(runtimeClasspath.contains(".molifox-classpath.jar"))
    }

    /** 实例级设置覆盖全局：窗口、内存与 JVM 参数都取实例自己的值。 */
    private fun configureOverrideLaunch(instance: GameInstance) {
        store.mutate {
            it.copy(
                settings =
                    it.settings.copy(
                        width = 1024,
                        height = 768,
                        initialMemoryMb = 256,
                        fullscreen = false,
                        extraJvmArgs = "-Dfixture.option=global",
                    ),
            )
        }
        store.updateInstance(instance.id) {
            it.copy(
                memoryMb = 1536,
                initialMemoryMb = 768,
                width = 1600,
                height = 900,
                windowMode = "fullscreen",
                extraJvmArgs = "-Dfixture.option=instance",
            )
        }
    }

    /** 再启动一次并返回启动前的请求数，用来证明完整本地游戏不会再下载。 */
    private suspend fun launchSecondTime(
        launcher: GameLauncher,
        instance: GameInstance,
        jobs: JobQueue,
    ): Int {
        val requestsBeforeOfflineLaunch = server.requestCount
        jobs.enqueue("launch with instance overrides") {
            launcher.launch(instance.id, this)
        }
        withTimeout(45000) {
            jobs.awaitIdle()
            while (
                store.state.value.logs.none {
                    it.message == "LAUNCH_OPTION=instance"
                } || store.state.value.runningInstanceId != null
            ) {
                delay(30)
            }
        }
        return requestsBeforeOfflineLaunch
    }

    /** 覆盖后的参数必须生效，且启动过程不再发起下载。 */
    private fun assertOverrideArguments(requestsBeforeOfflineLaunch: Int) {
        assertTrue(
            store.state.value.logs.any {
                it.message.contains(
                    "PLAYER_ARGS=--username|MoliFox|--accessToken|0|" +
                        "--width|1600|--height|900|--fullscreen",
                )
            },
        )
        assertEquals(
            requestsBeforeOfflineLaunch,
            server.requestCount,
            "Complete local game must not request downloads during launch",
        )
        assertTrue(
            store.state.value.logs.any {
                it.message.startsWith("JVM_FLAGS=") &&
                    it.message.contains("-Xms768M") &&
                    it.message.contains("-Xmx1536M")
            },
        )
    }

    /** 实例没填的项回落到全局设置，而不是沿用实例上一次的值。 */
    private suspend fun assertInheritedPlan(
        java: JavaService,
        instance: GameInstance,
    ) {
        val configured = store.instance(instance.id)
        val runtime = java.inspect(javaPath) ?: error("Runtime unavailable")
        val inherited =
            LaunchPlanner(store)
                .plan(
                    configured.copy(
                        memoryMb = 0,
                        initialMemoryMb = 0,
                        width = 0,
                        height = 0,
                        windowMode = "windowed",
                        extraJvmArgs = "",
                    ),
                    VersionResolver(store.minecraftRoot, store.platform)
                        .resolve(configured.versionId),
                    runtime,
                    GameCredentials("MoliFox", offlineUuid("MoliFox")),
                )
        assertContains(inherited.arguments, "-Xms256M")
        assertContains(inherited.arguments, "-Dfixture.option=global")
        assertEquals(
            "1024",
            inherited.arguments[inherited.arguments.indexOf("--width") + 1],
        )
        assertFalse("--fullscreen" in inherited.arguments)
    }
}
