package dev.molifox.core

import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.*
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LaunchDiagnosticsTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `game error filter includes exceptions and traces without classifying info as errors`() {
        assertTrue(isGameError("[Render thread/ERROR]: Couldn't load game"))
        assertTrue(isGameError("java.lang.UnsupportedClassVersionError: version 65"))
        assertTrue(isGameError("\tat net.minecraft.client.main.Main.main(Main.java:1)"))
        assertFalse(isGameError("[Render thread/INFO]: Loaded 1234 recipes"))
        assertFalse(isGameError("[Render thread/WARN]: Optional component unavailable"))
    }

    @Test
    fun `diagnostic package preserves older output and masks tokens in output and game files`() {
        val store = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))
        val instance = GameInstance(name = "Test", gameVersion = "fixture", versionId = "fixture")
        store.mutate { it.copy(instances = listOf(instance)) }
        val game = store.instancePath(instance.id).apply { createDirectories() }
        val session = LaunchSession(store, instance)
        val secret = "sensitive-token-without-jwt-prefix"
        session.prepared(
            LaunchPlan("java", emptyList(), game),
            GameCredentials("Tester", "uuid", secret),
        )
        repeat(750) { session.append("output-line-$it $secret") }
        session.append("[main/ERROR] Incompatible mod set!")
        game.resolve("logs").createDirectories()
        game.resolve("logs/latest.log").writeText("access_token=$secret\nplain=$secret")
        session.finish(1)
        assertEquals("模组依赖或版本不兼容", store.state.value.launchIssue?.title)
        val destination = temporary.resolve("diagnostics.zip")
        exportDiagnosticBundle(store, destination)
        ZipFile(destination.toFile()).use { zip ->
            val texts =
                zip.entries().asSequence().associate { entry ->
                    entry.name to zip.getInputStream(entry).reader().use { it.readText() }
                }
            val output = texts.entries.first { it.key.endsWith("/output.log") }.value
            assertTrue(output.contains("output-line-0"))
            assertTrue(output.contains("output-line-749"))
            assertFalse(texts.values.any { it.contains(secret) })
            assertTrue(texts.keys.any { it.endsWith("/latest.log") })
        }
    }

    @Test
    fun `diagnosis distinguishes memory Java dependencies and unknown errors`() {
        assertEquals(
            "游戏内存不足或分配失败",
            diagnoseGameFailure("java.lang.OutOfMemoryError: Java heap space", temporary).title,
        )
        assertEquals(
            "Java 版本或参数不兼容",
            diagnoseGameFailure("UnsupportedClassVersionError: class file version 65.0", temporary)
                .title,
        )
        assertTrue(
            diagnoseGameFailure("Fabric game provider couldn't locate the game!", temporary)
                .title
                .contains("启动依赖")
        )
        assertEquals("游戏启动失败或异常退出", diagnoseGameFailure("unexplained native exit", temporary).title)
        assertEquals(
            "游戏目录或缓存无法写入",
            diagnoseGameFailure(
                    "java.io.IOException: the jar file client-intermediary.jar.tmp can't be written",
                    temporary,
                )
                .title,
        )
    }
}
