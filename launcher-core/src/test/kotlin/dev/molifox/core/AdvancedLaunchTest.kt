// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证高级启动配置的继承与实际参数的行为与边界。

package dev.molifox.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.walk
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdvancedLaunchTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `environment rejects invalid names duplicates and managed flags`() {
        assertEquals(
            mapOf("NAME" to "  a=b ", "EMPTY" to ""),
            parseEnvironmentVariables("NAME=  a=b \nEMPTY=", "linux"),
        )
        assertEquals(2, parseEnvironmentVariables("Name=a\nNAME=b", "linux").size)
        for (text in listOf("BAD-NAME=a", "NAME", "NAME=a\nNAME=b", "Path=a\nPATH=b")) {
            assertFails {
                parseEnvironmentVariables(text, "windows")
            }
        }
        for (
        arguments in
        listOf(
            "--gameDir other",
            "--gameDir=other",
            "--accessToken secret",
            "--width 800",
            "@unwanted.args",
            "--server \"unterminated",
        )
        ) {
            assertFails {
                validateAdvancedLaunchSettings(AdvancedLaunchSettings(arguments), "windows")
            }
        }
        assertEquals(
            listOf("--server", "some server", "--port", "25565"),
            extraGameArguments(AdvancedLaunchSettings("--server \"some server\" --port 25565")),
        )
    }

    @Test
    fun `default arguments inherit and explicit empty values override`() {
        val settings = gson.fromJson("{}", LauncherSettings::class.java)
        val instance = gson.fromJson("{}", GameInstance::class.java)
        assertEquals(AdvancedLaunchSettings(), settings.advancedLaunch)
        assertNull(instance.advancedLaunch)
        val global =
            settings.copy(
                advancedLaunch =
                    AdvancedLaunchSettings("--server global", mapOf("SAMPLE" to "global")),
            )
        assertEquals(global.advancedLaunch, effectiveLaunchSettings(global, instance))
        assertEquals(
            AdvancedLaunchSettings(),
            effectiveLaunchSettings(
                global,
                instance.copy(advancedLaunch = AdvancedLaunchSettings()),
            ),
        )
        assertEquals("[已隐藏]", environmentForDiagnostics(mapOf("API_KEY" to "secret"))["API_KEY"])
    }

    @Test
    fun `preview does not download create directories extract natives or write argument files`() =
        runBlocking {
            val platform = DesktopPlatform()
            LauncherController(platform, temporary.resolve("profile")).use { controller ->
                val instance =
                    GameInstance(
                        name = "Preview",
                        gameVersion = "fixture",
                        versionId = "fixture",
                        managedGameDirectory = true,
                        installed = true,
                    )
                controller.store.mutate {
                    it.copy(
                        instances = listOf(instance),
                        settings =
                            it.settings.copy(
                                extraJvmArgs = "-Dpadding=" + "x".repeat(30_000),
                                advancedLaunch =
                                    AdvancedLaunchSettings(
                                        "--server example.test",
                                        mapOf("SECRET_TOKEN" to "must-not-leak"),
                                    ),
                            ),
                    )
                }
                writeJson(
                    controller.store.minecraftRoot.resolve("versions/fixture/fixture.json"),
                    mapOf(
                        "id" to "fixture",
                        "mainClass" to "Fixture",
                        "javaVersion" to mapOf("majorVersion" to 17),
                        "libraries" to emptyList<Any>(),
                        "arguments" to
                            mapOf("game" to listOf("--accessToken", "${'$'}{auth_access_token}")),
                    ),
                )

                fun snapshot() =
                    Files.walk(temporary).use { paths ->
                        paths
                            .filter {
                                it.isRegularFile() && it.fileName.toString() != "instance.lock"
                            }.map { it.toString() to digest(it) }
                            .toList()
                            .toMap()
                    }
                val before = snapshot()
                val preview = controller.previewLaunch(instance.id)
                assertEquals(before, snapshot())
                assertFalse(
                    controller.store
                        .instancePath(instance.id)
                        .resolve("natives")
                        .exists(),
                )
                assertTrue(preview.arguments.any { it.length >= 30_000 })
                assertTrue(preview.arguments.contains("<访问令牌>"))
                assertTrue(preview.arguments.contains("example.test"))
                assertEquals("[已隐藏]", preview.environment["SECRET_TOKEN"])
                assertFalse(preview.environment.values.contains("must-not-leak"))
            }
        }

    @Test
    fun `real Java receives arguments and environment in an isolated directory`() =
        runBlocking {
            val variable = "MOLIFOX_TEST_" + UUID.randomUUID().toString().replace("-", "")
            val source = temporary.resolve("LaunchOptionsFixture.java")
            source.writeText(
                """public class LaunchOptionsFixture { public static void main(String[] args) { System.out.println("OPTIONS_ARGS=" + String.join("|", args)); System.out.println("OPTIONS_ENV=" + System.getenv("$variable")); System.out.println("OPTIONS_CWD=" + java.nio.file.Path.of("").toAbsolutePath().normalize()); } }""",
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
                        temporary.toString(),
                        source.toString(),
                    ),
            )
            val platform = DesktopPlatform()
            val store = LauncherStore(platform, temporary.resolve("profile"))
            val instance =
                GameInstance(
                    name = "中文 options with spaces",
                    gameVersion = "fixture",
                    versionId = "fixture",
                    installed = true,
                    externalProfile = true,
                    managedGameDirectory = true,
                    isolation = VersionIsolation.Enabled,
                )
            store.mutate {
                it.copy(
                    instances = listOf(instance),
                    settings =
                        it.settings.copy(
                            gameRoot = temporary.resolve("中文 game root with spaces").toString(),
                            javaPath =
                                Path
                                    .of(
                                        System.getProperty("java.home"),
                                        "bin",
                                        platform.javaExecutable,
                                    ).toString(),
                            memoryMb = 512,
                            advancedLaunch =
                                AdvancedLaunchSettings(
                                    "--server global.example --port 25565",
                                    mapOf(variable to "全局 value=a b"),
                                ),
                        ),
                )
            }
            val jar =
                store.minecraftRoot.resolve("versions/fixture/fixture.jar").apply {
                    parent.createDirectories()
                }
            JarOutputStream(Files.newOutputStream(jar)).use { zip ->
                zip.putNextEntry(JarEntry("LaunchOptionsFixture.class"))
                zip.write(temporary.resolve("LaunchOptionsFixture.class").readBytes())
                zip.closeEntry()
            }
            writeJson(
                jar.resolveSibling("fixture.json"),
                mapOf(
                    "id" to "fixture",
                    "mainClass" to "LaunchOptionsFixture",
                    "javaVersion" to mapOf("majorVersion" to Runtime.version().feature()),
                    "libraries" to emptyList<Any>(),
                    "arguments" to mapOf("game" to emptyList<Any>()),
                ),
            )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            NetworkClient().use { network ->
                val accounts = AccountService(store, network, scope)
                accounts.addOffline("OptionsTester")
                val java = JavaService(store, network)
                val launcher =
                    GameLauncher(
                        store,
                        MinecraftInstaller(store, network, java),
                        java,
                        accounts,
                        scope,
                    )
                val jobs = JobQueue(store, scope)
                try {
                    suspend fun run(expectedEnvironment: String): List<String> {
                        val id =
                            jobs.enqueue("launch options fixture") {
                                launcher.launch(instance.id, this)
                            }
                        withTimeout(30_000) {
                            jobs.awaitIdle()
                            while (store.state.value.runningInstanceId != null) delay(25)
                        }
                        val job =
                            store.state.value.jobs
                                .first { it.id == id }
                        assertEquals(JobStatus.Completed, job.status, job.error)
                        val logs =
                            store.state.value.logs
                                .map { it.message }
                        assertTrue(
                            logs.contains("OPTIONS_ENV=$expectedEnvironment"),
                            logs.joinToString("\n"),
                        )
                        assertTrue(
                            logs.contains(
                                "OPTIONS_CWD=${store.instancePath(
                                    instance.id,
                                ).toAbsolutePath().normalize()}",
                            ),
                        )
                        return logs
                    }
                    assertTrue(
                        run("全局 value=a b")
                            .last { it.startsWith("OPTIONS_ARGS=") }
                            .contains("--server|global.example|--port|25565"),
                    )
                    store.updateInstance(instance.id) {
                        it.copy(
                            advancedLaunch =
                                AdvancedLaunchSettings(
                                    "--server \"instance host\"",
                                    mapOf(variable to "独立 value"),
                                ),
                        )
                    }
                    val overridden = run("独立 value").last { it.startsWith("OPTIONS_ARGS=") }
                    assertTrue(overridden.contains("--server|instance host"))
                    assertFalse(overridden.contains("global.example"))
                    store.updateInstance(instance.id) {
                        it.copy(advancedLaunch = AdvancedLaunchSettings())
                    }
                    assertFalse(
                        run("null").last { it.startsWith("OPTIONS_ARGS=") }.contains("--server"),
                    )
                    assertNull(System.getenv(variable))
                } finally {
                    launcher.stop()
                    scope.cancel()
                }
            }
        }
}
