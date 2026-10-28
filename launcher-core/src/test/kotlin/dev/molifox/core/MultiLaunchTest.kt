// 使用受控 Java 子进程验证双开、并行任务、独立运行目录与退出回收。

package dev.molifox.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MultiLaunchTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `same instance and other instances run independently while downloads continue`() =
        runBlocking {
            val source = directory.resolve("MultiGame.java")
            source.writeText(
                """
                import java.nio.file.*;
                public class MultiGame {
                    public static void main(String[] args) throws Exception {
                        long pid = ProcessHandle.current().pid();
                        Files.writeString(Path.of("native-"+pid+".txt"), System.getProperty("java.library.path"));
                        long end = System.nanoTime()+30000000000L;
                        while (!Files.exists(Path.of("exit-"+pid)) && System.nanoTime()<end) Thread.sleep(50);
                    }
                }
                """
                    .trimIndent()
            )
            assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler()
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
            val root = directory.resolve("中文 游戏")
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
            LauncherController(dataDirectory = directory.resolve("profile")).use { controller ->
                val instance =
                    GameInstance(
                        name = "First",
                        gameVersion = "fixture",
                        versionId = "fixture",
                        installed = true,
                        externalProfile = true,
                        managedGameDirectory = true,
                    )
                val second =
                    instance.copy(id = java.util.UUID.randomUUID().toString(), name = "Second")
                controller.store.mutate {
                    it.copy(
                        instances = listOf(instance, second),
                        settings =
                            it.settings.copy(
                                gameRoot = root.toString(),
                                javaPath =
                                    Path.of(
                                            System.getProperty("java.home"),
                                            "bin",
                                            controller.platform.javaExecutable,
                                        )
                                        .toString(),
                                autoMemory = false,
                                memoryMb = 512,
                                initialMemoryMb = 128,
                                extraJvmArgs = "-Dfixture.padding=" + "x".repeat(25_000),
                            ),
                    )
                }
                controller.accounts.addOffline("MultiTester")
                try {
                    repeat(2) {
                        controller.launch(instance.id)
                        controller.jobs.awaitIdle()
                    }
                    controller.launch(second.id)
                    controller.jobs.awaitIdle()
                    val runs = controller.state.value.runningGames
                    assertEquals(
                        3,
                        runs.size,
                        controller.state.value.logs.takeLast(10).joinToString { it.message },
                    )
                    assertFalse(controller.busy)
                    assertTrue(controller.game.isRunning(instance.id))
                    assertFailsWith<IllegalStateException> { controller.requireIdle() }
                    val nativeFiles = runs.map { run ->
                        val gameDirectory = controller.store.instancePath(run.instanceId)
                        val marker = gameDirectory.resolve("native-${run.processId}.txt")
                        eventually { marker.exists() }
                        Path.of(marker.readText())
                    }
                    assertEquals(3, nativeFiles.distinct().size)
                    assertTrue(nativeFiles.all { it.isDirectory() })
                    if (controller.platform.osName == "windows")
                        assertTrue(
                            nativeFiles.all { it.parent.resolve(".molifox-jvm.args").exists() }
                        )
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
                                    )
                                ),
                                1,
                                this,
                                "下载测试版本",
                            )
                        }
                        controller.jobs.awaitIdle()
                        assertEquals(
                            JobStatus.Completed,
                            controller.state.value.jobs.first().status,
                        )
                        assertEquals("download-while-playing", target.readText())
                    }
                    val firstRun = runs.first()
                    controller.store
                        .instancePath(firstRun.instanceId)
                        .resolve("exit-${firstRun.processId}")
                        .writeText("")
                    eventually { controller.state.value.runningGames.size == 2 }
                    assertTrue(controller.game.isRunning)
                    assertFalse(nativeFiles.first().exists())
                    controller.game.stop(second.id)
                    eventually { controller.state.value.runningGames.size == 1 }
                    assertEquals(instance.id, controller.state.value.runningInstanceId)
                    controller.game.stop(instance.id)
                    eventually { controller.state.value.runningGames.isEmpty() }
                    assertFalse(controller.game.isRunning)
                    assertTrue(nativeFiles.none { it.exists() })
                } finally {
                    controller.game.stop()
                    eventually { controller.state.value.runningGames.isEmpty() }
                }
            }
        }

    private suspend fun eventually(condition: () -> Boolean) {
        withTimeout(20_000) { while (!condition()) delay(50) }
    }
}
