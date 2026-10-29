// 负责验证高级启动配置的继承与实际参数的行为与边界。

package dev.molifox.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AdvancedLaunchTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `environment values are literal and invalid names duplicates and managed game flags are rejected`() {
        assertEquals(
            mapOf("NAME" to "  a=b ", "EMPTY" to ""),
            parseEnvironmentVariables("NAME=  a=b \nEMPTY=", "linux"),
        )
        assertEquals(2, parseEnvironmentVariables("Name=a\nNAME=b", "linux").size)
        for (text in listOf("BAD-NAME=a", "NAME", "NAME=a\nNAME=b", "Path=a\nPATH=b")) assertFails {
            parseEnvironmentVariables(text, "windows")
        }
        for (arguments in
            listOf(
                "--gameDir other",
                "--gameDir=other",
                "--accessToken secret",
                "--width 800",
                "@unwanted.args",
                "--server \"unterminated",
            )) {
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
    fun `default launch settings inherit and explicit empty instance settings override the group`() {
        val settings = gson.fromJson("{}", LauncherSettings::class.java)
        val instance = gson.fromJson("{}", GameInstance::class.java)
        assertEquals(AdvancedLaunchSettings(), settings.advancedLaunch)
        assertNull(instance.advancedLaunch)
        val global =
            settings.copy(
                advancedLaunch =
                    AdvancedLaunchSettings("--server global", mapOf("SAMPLE" to "global"))
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
                            }
                            .map { it.toString() to digest(it) }
                            .toList()
                            .toMap()
                    }
                val before = snapshot()
                val preview = controller.previewLaunch(instance.id)
                assertEquals(before, snapshot())
                assertFalse(controller.store.instancePath(instance.id).resolve("natives").exists())
                assertTrue(preview.arguments.any { it.length >= 30_000 })
                assertTrue(preview.arguments.contains("<访问令牌>"))
                assertTrue(preview.arguments.contains("example.test"))
                assertEquals("[已隐藏]", preview.environment["SECRET_TOKEN"])
                assertFalse(preview.environment.values.contains("must-not-leak"))
            }
        }

    @Test
    fun `real Java receives inherited overridden and cleared arguments and environment in isolated directory`() =
        runBlocking {
            val variable = "MOLIFOX_TEST_" + UUID.randomUUID().toString().replace("-", "")
            val source = temporary.resolve("LaunchOptionsFixture.java")
            source.writeText(
                """public class LaunchOptionsFixture { public static void main(String[] args) { System.out.println("OPTIONS_ARGS=" + String.join("|", args)); System.out.println("OPTIONS_ENV=" + System.getenv("$variable")); System.out.println("OPTIONS_CWD=" + java.nio.file.Path.of("").toAbsolutePath().normalize()); } }"""
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
                                Path.of(
                                        System.getProperty("java.home"),
                                        "bin",
                                        platform.javaExecutable,
                                    )
                                    .toString(),
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
                        val job = store.state.value.jobs.first { it.id == id }
                        assertEquals(JobStatus.Completed, job.status, job.error)
                        val logs = store.state.value.logs.map { it.message }
                        assertTrue(
                            logs.contains("OPTIONS_ENV=$expectedEnvironment"),
                            logs.joinToString("\n"),
                        )
                        assertTrue(
                            logs.contains(
                                "OPTIONS_CWD=${store.instancePath(instance.id).toAbsolutePath().normalize()}"
                            )
                        )
                        return logs
                    }
                    assertTrue(
                        run("全局 value=a b")
                            .last { it.startsWith("OPTIONS_ARGS=") }
                            .contains("--server|global.example|--port|25565")
                    )
                    store.updateInstance(instance.id) {
                        it.copy(
                            advancedLaunch =
                                AdvancedLaunchSettings(
                                    "--server \"instance host\"",
                                    mapOf(variable to "独立 value"),
                                )
                        )
                    }
                    val overridden = run("独立 value").last { it.startsWith("OPTIONS_ARGS=") }
                    assertTrue(overridden.contains("--server|instance host"))
                    assertFalse(overridden.contains("global.example"))
                    store.updateInstance(instance.id) {
                        it.copy(advancedLaunch = AdvancedLaunchSettings())
                    }
                    assertFalse(
                        run("null").last { it.startsWith("OPTIONS_ARGS=") }.contains("--server")
                    )
                    assertNull(System.getenv(variable))
                } finally {
                    launcher.stop()
                    scope.cancel()
                }
            }
        }
}
