// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 使用受控 Java 子进程验证双开、并行任务、独立运行目录与退出回收。

package dev.molifox.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MultiLaunchTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `same instance and other instances run independently while downloads continue`() =
        runBlocking {
            compileMultiGame()
            val root = directory.resolve("中文 游戏")
            writeFixtureProfile(root)
            LauncherController(dataDirectory = directory.resolve("profile")).use { controller ->
                val instance = fixtureInstance()
                val second =
                    instance.copy(
                        id =
                            java.util.UUID
                                .randomUUID()
                                .toString(),
                        name = "Second",
                    )
                configureFixture(controller, root, instance, second)
                try {
                    val release = startBackgroundDownload(controller)
                    assertFalse(controller.busy)
                    controller.saveInstanceConfiguration(second.copy(notes = "下载期间可保存其他实例设置"))
                    launchWhileDownloading(controller, instance, second)
                    release.complete(Unit)
                    controller.jobs.awaitIdle()
                    val snapshot = awaitThreeRuns(controller, instance)
                    assertDownloadWhilePlaying(controller)
                    assertExitRecycling(controller, snapshot, instance, second)
                } finally {
                    controller.game.stop()
                    eventually {
                        controller.state.value.runningGames
                            .isEmpty()
                    }
                }
            }
        }

    /** 编译受控 Java 子进程：写标记文件后等待退出信号。 */
    private fun compileMultiGame() {
        val source = directory.resolve("MultiGame.java")
        source.writeText(
            """
            import java.nio.file.*;
            public class MultiGame {
                public static void main(String[] args) throws Exception {
                    long pid = ProcessHandle.current().pid();
                    Files.writeString(
                        Path.of("native-"+pid+".txt"),
                        System.getProperty("java.library.path"));
                    long end = System.nanoTime()+30000000000L;
                    while (!Files.exists(Path.of("exit-"+pid)) && System.nanoTime()<end)
                        Thread.sleep(50);
                }
            }
            """.trimIndent(),
        )
        assertEquals(
            0,
            ToolProvider
                .getSystemJavaCompiler()
                .run(
                    null,
                    null,
                    null,
                    "--release",
                    "17",
                    "-d",
                    directory.toString(),
                    source.toString(),
                ),
        )
    }

    /** 在游戏根下写入版本 JAR 与清单。 */
    private fun writeFixtureProfile(root: Path) {
        val jar =
            root.resolve("versions/fixture/fixture.jar").apply { parent.createDirectories() }
        JarOutputStream(Files.newOutputStream(jar)).use {
            it.putNextEntry(JarEntry("MultiGame.class"))
            it.write(directory.resolve("MultiGame.class").readBytes())
            it.closeEntry()
        }
        writeJson(
            jar.resolveSibling("fixture.json"),
            mapOf(
                "id" to "fixture",
                "mainClass" to "MultiGame",
                "libraries" to emptyList<Any>(),
                "javaVersion" to mapOf("majorVersion" to Runtime.version().feature()),
                "arguments" to
                    mapOf(
                        "jvm" to
                            listOf(
                                "-Djava.library.path=${'$'}{natives_directory}",
                                "-cp",
                                "${'$'}{classpath}",
                            ),
                        "game" to emptyList<String>(),
                    ),
            ),
        )
    }

    private fun fixtureInstance(): GameInstance =
        GameInstance(
            name = "First",
            gameVersion = "fixture",
            versionId = "fixture",
            installed = true,
            externalProfile = true,
            managedGameDirectory = true,
        )

    /** 写入两个实例与共用设置，并添加一个离线账户。 */
    private fun configureFixture(
        controller: LauncherController,
        root: Path,
        instance: GameInstance,
        second: GameInstance,
    ) {
        controller.store.mutate {
            it.copy(
                instances = listOf(instance, second),
                settings =
                    it.settings.copy(
                        gameRoot = root.toString(),
                        javaPath =
                            Path
                                .of(
                                    System.getProperty("java.home"),
                                    "bin",
                                    controller.platform.javaExecutable,
                                ).toString(),
                        autoMemory = false,
                        memoryMb = 512,
                        initialMemoryMb = 128,
                        extraJvmArgs = "-Dfixture.padding=" + "x".repeat(25_000),
                    ),
            )
        }
        controller.accounts.addOffline("MultiTester")
    }

    /** 启动一个挂起的下载任务，返回完成信号；任务在信号完成后才结束。 */
    private suspend fun startBackgroundDownload(
        controller: LauncherController,
    ): CompletableDeferred<Unit> {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        controller.jobs.enqueue("下载中的独立任务", setOf("another-download")) {
            started.complete(Unit)
            release.await()
        }
        started.await()
        return release
    }

    /** 下载尚未结束时依次启动同一实例两次、再启动另一个实例。 */
    private suspend fun launchWhileDownloading(
        controller: LauncherController,
        instance: GameInstance,
        second: GameInstance,
    ) {
        repeat(2) {
            controller.launch(instance.id)
            val expected = it + 1
            eventually { controller.state.value.runningGames.size == expected }
        }
        controller.launch(second.id)
        eventually { controller.state.value.runningGames.size == 3 }
    }

    /** 等三个运行记录稳定并收集各自的本地库标记。 */
    private suspend fun awaitThreeRuns(
        controller: LauncherController,
        instance: GameInstance,
    ): RunningSnapshot {
        val runs = controller.state.value.runningGames
        assertEquals(
            3,
            runs.size,
            controller.state.value.logs
                .takeLast(10)
                .joinToString { it.message },
        )
        assertFalse(controller.busy)
        assertTrue(controller.game.isRunning(instance.id))
        assertFailsWith<IllegalStateException> { controller.requireIdle() }
        val nativeFiles =
            runs.map { run ->
                val gameDirectory = controller.store.instancePath(run.instanceId)
                val marker = gameDirectory.resolve("native-${run.processId}.txt")
                eventually { marker.exists() }
                Path.of(marker.readText())
            }
        assertEquals(3, nativeFiles.distinct().size)
        assertTrue(nativeFiles.all { it.isDirectory() })
        if (controller.platform.osName == "windows") {
            assertTrue(
                nativeFiles.all { it.parent.resolve(".molifox-jvm.args").exists() },
            )
        }
        return RunningSnapshot(runs, nativeFiles)
    }

    /** 游戏运行期间仍能完成一次真实下载。 */
    private suspend fun assertDownloadWhilePlaying(controller: LauncherController) {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("download-while-playing"))
            server.start()
            val target = directory.resolve("another-version.bin")
            controller.jobs.enqueue("Download another version") {
                controller.network.downloadAll(
                    listOf(
                        DownloadArtifact(
                            server.loopbackUrl("/game").toString(),
                            target,
                            size = 22,
                        ),
                    ),
                    1,
                    this,
                    "下载测试版本",
                )
            }
            controller.jobs.awaitIdle()
            assertEquals(
                JobStatus.Completed,
                controller.state.value.jobs
                    .first()
                    .status,
            )
            assertEquals("download-while-playing", target.readText())
        }
    }

    /** 逐个回收游戏进程，运行目录里的标记文件也应随之清理。 */
    private suspend fun assertExitRecycling(
        controller: LauncherController,
        snapshot: RunningSnapshot,
        instance: GameInstance,
        second: GameInstance,
    ) {
        val firstRun = snapshot.runs.first()
        controller.store
            .instancePath(firstRun.instanceId)
            .resolve("exit-${firstRun.processId}")
            .writeText("")
        eventually { controller.state.value.runningGames.size == 2 }
        assertTrue(controller.game.isRunning)
        assertFalse(snapshot.nativeFiles.first().exists())
        controller.game.stop(second.id)
        eventually { controller.state.value.runningGames.size == 1 }
        assertEquals(instance.id, controller.state.value.runningInstanceId)
        controller.game.stop(instance.id)
        eventually {
            controller.state.value.runningGames
                .isEmpty()
        }
        assertFalse(controller.game.isRunning)
        assertTrue(snapshot.nativeFiles.none { it.exists() })
    }

    /** 三个实例稳定运行后的快照：运行记录与各自的本地库标记文件。 */
    private data class RunningSnapshot(
        val runs: List<GameRunEvent>,
        val nativeFiles: List<Path>,
    )

    private suspend fun eventually(condition: () -> Boolean) {
        withTimeout(20_000) { while (!condition()) delay(50) }
    }
}
