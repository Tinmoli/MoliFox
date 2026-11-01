// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证游戏与启动器窗口的生命周期的行为与边界。

package dev.molifox.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private data class Fixture(
    val store: LauncherStore,
    val instance: GameInstance,
    val launcher: GameLauncher,
    val jobs: JobQueue,
    val command: String,
    val root: Path,
)

private const val COMMAND_FIXTURE_SOURCE =
    """import java.nio.file.*;
            public class CommandFixture {
            public static void main(String[] args) throws Exception {
            String mode=args[0];
            if(mode.equals("wait")) {
            Files.writeString(
                Path.of("waiting.pid.tmp"),
                Long.toString(ProcessHandle.current().pid()));
            Files.move(Path.of("waiting.pid.tmp"), Path.of("waiting.pid"));
            Thread.sleep(30000);
            return;
            }
            String entry=mode.equals("post")
                ? "post:"+System.getenv("MOLIFOX_EXIT_CODE")
                : mode;
            Files.writeString(
                Path.of("order.txt"), entry+"\n", StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
            System.out.println("COMMAND_SECRET="+System.getenv("SAMPLE_API_KEY"));
            if(mode.equals("fail")) System.exit(7);
            if(mode.equals("game")) Thread.sleep(600);
        }}"""

class LaunchLifecycleTest {
    @TempDir lateinit var temporary: Path

    private fun fixture(action: suspend (Fixture) -> Unit): Unit =
        runBlocking {
            compileCommandFixture(temporary)
            val platform = DesktopPlatform()
            val store = LauncherStore(platform, temporary.resolve("profile"))
            val instance =
                GameInstance(
                    name = "命令 fixture",
                    gameVersion = "fixture",
                    versionId = "fixture",
                    installed = true,
                    externalProfile = true,
                    managedGameDirectory = true,
                )
            val root = temporary.resolve("中文 game with spaces")
            val javaPath =
                Path.of(System.getProperty("java.home"), "bin", platform.javaExecutable).toString()
            registerCommandFixture(store, instance, root, javaPath)
            val jar = installCommandFixtureJar(temporary.resolve("CommandFixture.class"), root)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            NetworkClient().use { network ->
                val launcher = lifecycleLauncher(store, network, scope)
                try {
                    action(
                        Fixture(
                            store,
                            instance,
                            launcher,
                            JobQueue(store, scope),
                            "\"$javaPath\" -cp \"$jar\" CommandFixture",
                            store.instancePath(instance.id),
                        ),
                    )
                } finally {
                    launcher.stop()
                    scope.cancel()
                }
            }
        }

    @Test
    fun `window policies preserve legacy behavior restore failures and exit only on success`() {
        val event = GameRunEvent("session", "instance", "hide", true)
        assertEquals(LauncherWindowAction.Hide, launcherWindowAction(event, true))
        assertEquals(LauncherWindowAction.Minimize, launcherWindowAction(event, false))
        assertEquals(
            LauncherWindowAction.Exit,
            launcherWindowAction(event.copy(finished = true, exitCode = 0), true),
        )
        assertEquals(
            LauncherWindowAction.Restore,
            launcherWindowAction(event.copy(finished = true, exitCode = 1), true),
        )
        assertEquals(
            LauncherWindowAction.Restore,
            launcherWindowAction(
                event.copy(finished = true, exitCode = 0, postCommandFailed = true),
                true,
            ),
        )
        assertFails {
            validateAdvancedLaunchSettings(
                AdvancedLaunchSettings(preLaunchCommand = "\"broken"),
                "windows",
            )
        }
        assertFails {
            validateAdvancedLaunchSettings(
                AdvancedLaunchSettings(commandTimeoutSeconds = 0),
                "windows",
            )
        }
    }

    @Test
    fun `pre game post order and run snapshot survive settings changes while playing`() =
        fixture { f ->
            val secret = "a-private-fixture-value"
            configureLifecycle(f, secret)
            val id = f.jobs.enqueue("lifecycle fixture") { f.launcher.launch(f.instance.id, this) }
            awaitGameStarted(f)
            switchToKeepVisibility(f)
            awaitLaunchFinished(f)
            assertEquals(
                JobStatus.Completed,
                f.store.state.value.jobs
                    .first { it.id == id }
                    .status,
            )
            assertEquals(listOf("pre", "game", "post:0"), f.root.resolve("order.txt").readLines())
            assertRunSnapshot(f)
            assertSessionsHideSecret(f, secret)
        }

    @Test
    fun `failed pre command prevents game and post command from starting`() =
        fixture { f ->
            f.store.mutate {
                it.copy(
                    settings =
                        it.settings.copy(
                            advancedLaunch =
                                AdvancedLaunchSettings(
                                    preLaunchCommand = "${f.command} fail",
                                    postExitCommand = "${f.command} post",
                                ),
                        ),
                )
            }
            val id = f.jobs.enqueue("pre failure") { f.launcher.launch(f.instance.id, this) }
            withTimeout(30_000) { f.jobs.awaitIdle() }
            assertEquals(
                JobStatus.Failed,
                f.store.state.value.jobs
                    .first { it.id == id }
                    .status,
            )
            assertEquals(listOf("fail"), f.root.resolve("order.txt").readLines())
            assertNull(f.store.state.value.gameRun)
            assertEquals(
                "启动前命令执行失败",
                f.store.state.value.launchIssue
                    ?.title,
            )
        }

    @Test
    fun `post command failure preserves game exit code and keeps launcher open`() =
        fixture { f ->
            f.store.mutate {
                it.copy(
                    settings =
                        it.settings.copy(
                            advancedLaunch =
                                AdvancedLaunchSettings(
                                    postExitCommand = "${f.command} fail",
                                    closeLauncherAfterGameExit = true,
                                ),
                        ),
                )
            }
            f.jobs.enqueue("post failure") { f.launcher.launch(f.instance.id, this) }
            withTimeout(30_000) {
                f.jobs.awaitIdle()
                while (f.launcher.isRunning) delay(25)
            }
            val event = assertNotNull(f.store.state.value.gameRun)
            assertEquals(0, event.exitCode)
            assertTrue(event.postCommandFailed)
            assertEquals(LauncherWindowAction.Restore, launcherWindowAction(event, true))
            assertNull(f.store.state.value.launchIssue)
        }

    @Test
    fun `timed out command stops only its owned process`() =
        fixture { f ->
            f.root.createDirectories()
            val session = LaunchSession(f.store, f.instance)
            val plan = LaunchPlan("java", emptyList(), f.root)
            assertFails {
                LaunchCommandRunner(f.store)
                    .run(
                        "${f.command} wait",
                        1,
                        LaunchCommandPhase.Before,
                        f.instance,
                        plan,
                        session,
                    )
            }
            val pid =
                f.root
                    .resolve("waiting.pid")
                    .readText()
                    .toLong()
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        }

    @Test
    fun `cancelling queued pre command terminates the helper without launching game`() =
        fixture { f ->
            f.store.mutate {
                it.copy(
                    settings =
                        it.settings.copy(
                            advancedLaunch =
                                AdvancedLaunchSettings(preLaunchCommand = "${f.command} wait"),
                        ),
                )
            }
            val id = f.jobs.enqueue("cancel pre") { f.launcher.launch(f.instance.id, this) }
            withTimeout(30_000) { while (!f.root.resolve("waiting.pid").exists()) delay(25) }
            val pid =
                f.root
                    .resolve("waiting.pid")
                    .readText()
                    .toLong()
            f.jobs.cancel(id)
            withTimeout(10_000) { f.jobs.awaitIdle() }
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
            assertNull(f.store.state.value.gameRun)
            assertFalse(f.root.resolve("order.txt").exists())
        }

    private fun configureLifecycle(
        f: Fixture,
        secret: String,
    ) {
        f.store.mutate {
            it.copy(
                settings =
                    it.settings.copy(
                        advancedLaunch =
                            AdvancedLaunchSettings(
                                environmentVariables = mapOf("SAMPLE_API_KEY" to secret),
                                preLaunchCommand = "${f.command} pre",
                                postExitCommand = "${f.command} post",
                                launcherVisibility = "hide",
                            ),
                    ),
            )
        }
    }

    private fun switchToKeepVisibility(f: Fixture) {
        f.store.mutate {
            it.copy(
                settings =
                    it.settings.copy(
                        advancedLaunch =
                            AdvancedLaunchSettings(
                                postExitCommand = "missing-program",
                                launcherVisibility = "keep",
                                closeLauncherAfterGameExit = true,
                            ),
                    ),
            )
        }
    }

    private suspend fun awaitGameStarted(f: Fixture) {
        withTimeout(30_000) {
            while (
                !f.root.resolve("order.txt").exists() ||
                !f.root
                    .resolve("order.txt")
                    .readText()
                    .contains("game")
            ) {
                delay(25)
            }
        }
    }

    private suspend fun awaitLaunchFinished(f: Fixture) {
        withTimeout(30_000) {
            f.jobs.awaitIdle()
            while (f.launcher.isRunning) delay(25)
        }
    }

    private fun assertRunSnapshot(f: Fixture) {
        val event = assertNotNull(f.store.state.value.gameRun)
        assertTrue(event.finished)
        assertEquals(0, event.exitCode)
        assertEquals("hide", event.visibility)
        assertFalse(event.closeAfterExit)
    }

    private fun assertSessionsHideSecret(
        f: Fixture,
        secret: String,
    ) {
        val sessions =
            f.store.dataDirectory
                .resolve("logs/games")
                .listDirectoryEntries()
        assertTrue(
            sessions
                .flatMap { session ->
                    session
                        .listDirectoryEntries()
                        .filter { it.isRegularFile() }
                        .map { it.readText() }
                }.none { it.contains(secret) },
        )
    }
}

private fun compileCommandFixture(temporary: Path) {
    val source = temporary.resolve("CommandFixture.java")
    source.writeText(COMMAND_FIXTURE_SOURCE)
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
                temporary.toString(),
                source.toString(),
            ),
    )
}

private fun registerCommandFixture(
    store: LauncherStore,
    instance: GameInstance,
    root: Path,
    javaPath: String,
) {
    store.mutate {
        it.copy(
            instances = listOf(instance),
            settings =
                it.settings.copy(
                    gameRoot = root.toString(),
                    javaPath = javaPath,
                    memoryMb = 512,
                ),
        )
    }
}

private fun installCommandFixtureJar(
    compiledClass: Path,
    root: Path,
): Path {
    val jar =
        root
            .resolve(
                "versions/fixture/fixture.jar",
            ).apply { parent.createDirectories() }
    JarOutputStream(Files.newOutputStream(jar)).use { zip ->
        zip.putNextEntry(JarEntry("CommandFixture.class"))
        zip.write(compiledClass.readBytes())
        zip.closeEntry()
    }
    writeJson(
        jar.resolveSibling("fixture.json"),
        mapOf(
            "id" to "fixture",
            "mainClass" to "CommandFixture",
            "javaVersion" to mapOf("majorVersion" to Runtime.version().feature()),
            "libraries" to emptyList<Any>(),
            "arguments" to mapOf("game" to listOf("game")),
        ),
    )
    return jar
}

private fun lifecycleLauncher(
    store: LauncherStore,
    network: NetworkClient,
    scope: CoroutineScope,
): GameLauncher {
    val java = JavaService(store, network)
    val accounts =
        AccountService(store, network, scope).apply { addOffline("LifecycleTester") }
    return GameLauncher(
        store,
        MinecraftInstaller(store, network, java),
        java,
        accounts,
        scope,
    )
}
