// 验证当前便携目录、诊断导出和配置并发保护。

package dev.molifox.core

import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.*
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StorageLayoutTest {
    @TempDir lateinit var temporary: Path

    private fun put(root: Path, relative: String, text: String) =
        root.resolve(relative).apply {
            parent.createDirectories()
            writeText(text)
        }

    @Test
    fun `diagnostics reads new logs while excluding configuration credentials and backups`() {
        val root = temporary.resolve("profile").createDirectory()
        put(root, "launcher.log", "old startup log")
        val store = LauncherStore(DesktopPlatform(), root)
        store.log("new log line")
        put(root, "credentials/secret.json", "DO_NOT_EXPORT")
        put(root, "backups/private.json", "DO_NOT_EXPORT")
        val exported = temporary.resolve("diagnostics.zip")
        exportDiagnosticBundle(store, exported)
        ZipFile(exported.toFile()).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            assertEquals(listOf("logs/launcher.log"), names)
            val log = zip.getInputStream(zip.getEntry("logs/launcher.log")).reader().readText()
            assertTrue(log.contains("new log line"))
            assertFalse(log.contains("old startup log"))
            assertFalse(log.contains("DO_NOT_EXPORT"))
        }
        assertEquals("old startup log", root.resolve("launcher.log").readText())
    }

    @Test
    fun `second controller cannot change data under an active profile lock`() {
        val root = temporary.resolve("profile")
        LauncherController(DesktopPlatform(), root).use { first ->
            val current = first.store.directories.configuration.readBytes()
            put(root, "launcher.json", "legacy file must stay while locked")
            assertFails { LauncherController(DesktopPlatform(), root) }
            assertContentEquals(current, first.store.directories.configuration.readBytes())
            assertEquals(
                "legacy file must stay while locked",
                root.resolve("launcher.json").readText(),
            )
        }
    }

    @Test
    fun `fresh profile has portable roots and never creates a game installation`() {
        val root = temporary.resolve("MoliFox")
        val store = LauncherStore(DesktopPlatform(), root)
        assertEquals(temporary.resolve(".minecraft").toString(), store.data.settings.gameRoot)
        assertFalse(temporary.resolve(".minecraft").exists())
        for (directory in
            listOf(
                "config",
                "credentials",
                "appearance/backgrounds",
                "appearance/fonts",
                "appearance/music",
                "appearance/themes",
                "skins",
                "icons",
                "runtimes",
                "dependencies",
                "logs",
                "backups/config",
                "cache/downloads",
                "cache/metadata",
                "cache/skins",
                "cache/tmp",
            )) assertTrue(root.resolve(directory).isDirectory(), directory)
        assertFalse(root.resolve("launcher.json").exists())
        assertTrue(root.resolve("config/launcher.json").isRegularFile())
    }
}
