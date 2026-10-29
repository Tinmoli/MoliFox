// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验证当前配置字段、损坏备份恢复及旧配置/缓存不再自动迁移的约定。

package dev.molifox.core

import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CurrentConfigurationTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `current configuration keeps theme and root without emitting obsolete fields`() {
        val store = LauncherStore(DesktopPlatform(), directory)
        val gameRoot = directory.resolve("chosen-game").toString()
        store.mutate {
            it.copy(
                settings =
                    it.settings.copy(
                        gameRoot = gameRoot,
                        themeMode = "dark",
                        advancedLaunch = AdvancedLaunchSettings(launcherVisibility = "minimize"),
                    )
            )
        }
        val loaded = LauncherStore(DesktopPlatform(), directory)
        assertEquals(gameRoot, loaded.data.settings.gameRoot)
        assertEquals("dark", loaded.data.settings.themeMode)
        assertEquals("minimize", loaded.data.settings.advancedLaunch.launcherVisibility)
        assertEquals(2, loaded.data.schemaVersion)
        val json = readObject(loaded.directories.configuration).obj("settings")
        for (key in
            listOf(
                "darkTheme",
                "minimizeOnLaunch",
                "gameRootSemantics",
                "updateManifestUrl",
            )) assertFalse(json.has(key))
        assertFalse(directory.resolve("config/storage-layout.json").exists())
    }

    @Test
    fun `old root configuration and cache are left untouched rather than imported`() {
        directory.resolve("launcher.json").writeText("legacy settings")
        val oldCache =
            directory.resolve("cache/versions.json").apply {
                parent.createDirectories()
                writeText("legacy metadata")
            }
        val store = LauncherStore(DesktopPlatform(), directory)
        assertEquals("legacy settings", directory.resolve("launcher.json").readText())
        assertEquals("legacy metadata", oldCache.readText())
        assertFalse(store.metadataDirectory.resolve("versions.json").exists())
        assertTrue(store.data.accounts.isEmpty())
    }

    @Test
    fun `damaged current configuration recovers previous current version and preserves damaged bytes`() {
        val store = LauncherStore(DesktopPlatform(), directory)
        store.mutate { it.copy(settings = it.settings.copy(themeMode = "dark")) }
        LauncherStore(DesktopPlatform(), directory)
        store.directories.configuration.writeText("BROKEN_CURRENT_CONFIGURATION")
        val restored = LauncherStore(DesktopPlatform(), directory)
        assertEquals("dark", restored.data.settings.themeMode)
        assertTrue(
            restored.directories.configurationBackups
                .listDirectoryEntries("launcher.damaged-*.json")
                .any { it.readText() == "BROKEN_CURRENT_CONFIGURATION" }
        )
    }

    @Test
    fun `old schema is not interpreted or moved to another game root`() {
        val store = LauncherStore(DesktopPlatform(), directory)
        val old = readObject(store.directories.configuration)
        old.addProperty("schemaVersion", 1)
        old.obj("settings").addProperty("gameRoot", directory.resolve("legacy-game").toString())
        writeJson(store.directories.configuration, old)
        val reloaded = LauncherStore(DesktopPlatform(), directory)
        assertEquals(2, reloaded.data.schemaVersion)
        assertFalse(directory.resolve("legacy-game").exists())
        assertNotEquals(
            directory.resolve("legacy-game/minecraft").toString(),
            reloaded.data.settings.gameRoot,
        )
    }
}
