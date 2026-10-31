// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证存档详情读取与允许作弊/难度设置写回的边界。

package dev.molifox.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeBytes
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SaveDetailsTest {
    private val temporary: Path = workTestDirectory("launcher-core-save-details")

    @AfterEach
    fun cleanUp() {
        removeTestTree(temporary)
    }

    private fun store() = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))

    private fun instance(store: LauncherStore): GameInstance {
        val instance =
            GameInstance(
                name = "Fixture",
                gameVersion = "1.20.1",
                versionId = "1.20.1",
                gameDirectoryName = "Fixture",
                installed = true,
                managedGameDirectory = true,
                isolation = VersionIsolation.Enabled,
            )
        store.mutate { it.copy(instances = it.instances + instance) }
        store.instancePath(instance.id).createDirectories()
        return store.instance(instance.id)
    }

    private fun writeWorld(
        store: LauncherStore,
        name: String = "World",
        block: TestNbtWriter.() -> Unit,
    ): Path {
        val saved = store.data.instances.single()
        val world = store.instancePath(saved.id).resolve("saves").resolve(name)
        world.createDirectories()
        world.resolve("level.dat").writeBytes(gzipBytes(TestNbtWriter().root(block)))
        return world
    }

    private fun fullWorld(store: LauncherStore): Path =
        writeWorld(store) {
            nested("Data") {
                string("LevelName", "我的世界")
                long("LastPlayed", 1_700_000_000_000L)
                long("RandomSeed", 42L)
                int("SpawnX", 10)
                int("SpawnY", 64)
                int("SpawnZ", -20)
                int("GameType", 1)
                byte("hardcore", 1)
                byte("Difficulty", 3)
                byte("DifficultyLocked", 1)
                byte("allowCommands", 1)
                long("Time", 24_000L)
                nested("Version") {
                    string("Name", "1.20.1")
                    int("Id", 3465)
                }
            }
        }

    @Test
    fun `details expose level information`() {
        val store = store()
        val fixture = instance(store)
        fullWorld(store)

        val details = store.manager().readDetails(fixture.id, "World")

        assertEquals("我的世界", details.levelName)
        assertEquals("1.20.1", details.versionName)
        assertEquals(3465, details.versionId)
        assertEquals(Instant.ofEpochMilli(1_700_000_000_000L), details.lastPlayed)
        assertEquals(42L, details.seed)
        assertEquals(SaveSpawn(10.0, 64.0, -20.0), details.spawn)
        assertEquals(1, details.gameMode)
        assertTrue(details.hardcore)
        assertEquals(3, details.difficulty)
        assertEquals(true, details.difficultyLocked)
        assertEquals(true, details.allowCommands)
        assertEquals(24_000L, details.playTicks)
    }

    @Test
    fun `missing level fields fall back to unknown instead of guessed values`() {
        val store = store()
        val fixture = instance(store)
        writeWorld(store, name = "Old World") {
            nested("Data") {
                string("LevelName", "Old World")
            }
        }

        val details = store.manager().readDetails(fixture.id, "Old World")

        assertEquals("Old World", details.levelName)
        assertNull(details.versionName)
        assertNull(details.versionId)
        assertNull(details.lastPlayed)
        assertNull(details.seed)
        assertNull(details.spawn)
        assertNull(details.gameMode)
        assertFalse(details.hardcore)
        assertNull(details.difficulty)
        assertNull(details.difficultyLocked)
        assertNull(details.allowCommands)
        assertNull(details.playTicks)
    }

    @Test
    fun `zip saves cannot be opened as detail`() {
        val store = store()
        val fixture = instance(store)
        val saves = store.instancePath(fixture.id).resolve("saves").apply { createDirectories() }
        saves.resolve("Packed.zip").writeBytes(byteArrayOf(1, 2, 3))

        assertFailsWith<IllegalArgumentException> {
            store.manager().readDetails(fixture.id, "Packed.zip")
        }
    }

    @Test
    fun `settings write back only existing labels and keep other data`() {
        val store = store()
        val fixture = instance(store)
        val world = fullWorld(store)

        val changes =
            SaveSettingsChanges(
                allowCommands = false,
                difficulty = 1,
                difficultyLocked = false,
            )
        val changed = store.manager().updateSettings(fixture.id, "World", changes)

        assertTrue(changed)
        val details = store.manager().readDetails(fixture.id, "World")
        assertEquals(false, details.allowCommands)
        assertEquals(1, details.difficulty)
        assertEquals(false, details.difficultyLocked)
        assertEquals("我的世界", details.levelName)
        assertTrue(details.hardcore)
        val backups =
            store.dataDirectory
                .resolve("backups/level-dat")
                .listDirectoryEntries("World-*.dat")
        assertEquals(1, backups.size)
        assertTrue(world.resolve("level.dat").exists())
    }

    @Test
    fun `unchanged or absent settings are not written`() {
        val store = store()
        val fixture = instance(store)
        writeWorld(store) {
            nested("Data") {
                string("LevelName", "World")
                byte("allowCommands", 0)
            }
        }

        val same =
            store.manager().updateSettings(
                fixture.id,
                "World",
                SaveSettingsChanges(allowCommands = false, difficulty = 2),
            )

        assertFalse(same)
        assertFalse(
            store.dataDirectory
                .resolve("backups/level-dat/World.dat")
                .exists(),
        )
    }

    @Test
    fun `broken level dat reports a readable error`() {
        val store = store()
        val fixture = instance(store)
        writeWorld(store) { nested("Data") { string("LevelName", "World") } }
        val saves = store.instancePath(fixture.id).resolve("saves/World")
        saves.resolve("level.dat").writeBytes(byteArrayOf(0, 1, 2))

        assertFailsWith<IllegalArgumentException> {
            store.manager().readDetails(fixture.id, "World")
        }
        assertFailsWith<IllegalArgumentException> {
            store.manager().updateSettings(
                fixture.id,
                "World",
                SaveSettingsChanges(difficulty = 1),
            )
        }
    }

    private fun LauncherStore.manager() = SaveManager(this)
}
