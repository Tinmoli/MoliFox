// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证存档列表、原版备份命名与非破坏恢复的行为与边界。

package dev.molifox.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SaveManagerTest : SaveManagerTestSupport("launcher-core-saves") {
    // ---- 列出 ----

    @Test
    fun `list reports worlds archives and empty directories without other files`() {
        val store = store()
        val manager = SaveManager(store)
        val fixture = instance(store)
        val root = store.instancePath(fixture.id)
        world(root, "World")
        write(root.resolve("saves/empty/readme.txt"), "not a world")
        write(root.resolve("saves/notes.txt"), "user notes")
        writeZip(
            root.resolve("saves/Exported.zip"),
            listOf("level.dat" to "level", "region/r.0.0.mca" to "region"),
        )
        writeZip(root.resolve("saves/Screenshots.zip"), listOf("a.png" to "png"))

        val entries = manager.listSaves(fixture.id)

        assertEquals(
            listOf("empty", "Exported.zip", "Screenshots.zip", "World"),
            entries.map { it.name },
        )
        val byName = entries.associateBy { it.name }
        assertTrue(byName.getValue("World").hasLevelDat)
        assertTrue(byName.getValue("World").size > 0)
        assertNotNull(byName.getValue("World").lastModified)
        assertTrue(byName.getValue("empty").hasLevelDat.not())
        assertTrue(byName.getValue("Exported.zip").hasLevelDat)
        assertTrue(byName.getValue("Screenshots.zip").hasLevelDat.not())
        assertTrue(entries.none { it.backup })
        assertTrue(
            entries.all {
                it.path.parent
                    .toAbsolutePath()
                    .normalize() ==
                    root.resolve("saves").toAbsolutePath().normalize()
            },
        )
    }

    @Test
    fun `list skips symbolic links`() {
        val store = store()
        val manager = SaveManager(store)
        val fixture = instance(store)
        val root = store.instancePath(fixture.id)
        world(root, "World")
        val outside = temporary.resolve("outside-world").apply { createDirectories() }
        write(outside.resolve("level.dat"), "outside")
        val created = linkDirectory(root.resolve("saves/Linked"), outside)
        assumeTrue(created, "当前环境不允许创建符号链接或目录联接")

        val entries = manager.listSaves(fixture.id)

        assertEquals(listOf("World"), entries.map { it.name })
        assertTrue(outside.resolve("level.dat").isRegularFile(), "列出不得跟随符号链接")
    }

    @Test
    fun `list returns empty when the saves directory is missing`() {
        val store = store()
        val fixture = instance(store)
        assertFalse(store.instancePath(fixture.id).resolve("saves").exists())

        assertTrue(SaveManager(store).listSaves(fixture.id).isEmpty())
    }

    @Test
    fun `list does not throw on a damaged archive`() {
        val store = store()
        val manager = SaveManager(store)
        val fixture = instance(store)
        val broken = store.instancePath(fixture.id).resolve("saves/Broken.zip")
        write(broken, "this is not a zip file")

        val entries = manager.listSaves(fixture.id)

        assertEquals(listOf("Broken.zip"), entries.map { it.name })
        assertFalse(entries.single().hasLevelDat)
    }

    // ---- 备份 ----

    @Test
    fun `backup writes a vanilla named archive into backups and keeps the original save`() =
        runBlocking {
            val store = store()
            val manager = SaveManager(store)
            val fixture = instance(store)
            val root = store.instancePath(fixture.id)
            world(root, "World")
            val original = root.resolve("saves/World")

            val info = manager.backupSave(fixture.id, "World", JobContext(store, "fixture"))

            assertTrue(info.archive.startsWith(backupDirectory(store, fixture)))
            assertTrue(
                vanillaBackupName.matches(info.archive.name),
                "备份文件名必须是原版命名：${info.archive.name}",
            )
            assertTrue(info.archive.name.endsWith("_World.zip"))
            assertEquals("World", info.saveName)
            assertTrue(info.restorable)
            assertEquals(
                info.archive.fileName
                    .toString()
                    .removeSuffix(".zip"),
                info.id,
            )
            assertEquals(info.archive.fileSize(), info.size)
            assertEquals(
                mapOf(
                    "level.dat" to "level",
                    "playerdata/player.dat" to "level-player",
                    "region/r.0.0.mca" to "level-region",
                ),
                zipEntries(info.archive),
            )
            assertTrue(original.isDirectory(), "备份必须保留原存档")
            assertEquals("level", original.resolve("level.dat").readText())
            assertEquals(listOf(info.id), SaveManager(store).listBackups(fixture.id).map { it.id })
            assertTrue(leftovers(backupDirectory(store, fixture)).isEmpty())
            // 旧隐藏目录不再被写入，也不会被创建。
            assertFalse(root.resolve(".molifox-saves-backups").exists())
        }

    @Test
    fun `backup twice in a row does not collide`() =
        runBlocking {
            val store = store()
            val manager = SaveManager(store)
            val fixture = instance(store)
            world(store.instancePath(fixture.id), "World")

            val first = manager.backupSave(fixture.id, "World")
            val second = manager.backupSave(fixture.id, "World")

            assertNotEquals(first.id, second.id)
            assertNotEquals(first.archive, second.archive)
            assertTrue(first.archive.isRegularFile())
            assertTrue(second.archive.isRegularFile())
            assertEquals(2, manager.listBackups(fixture.id).size)
            assertEquals(zipEntries(first.archive), zipEntries(second.archive))
        }

    @Test
    fun `backup of a zip save copies the archive as is`() =
        runBlocking {
            val store = store()
            val manager = SaveManager(store)
            val fixture = instance(store)
            val root = store.instancePath(fixture.id)
            writeZip(root.resolve("saves/Legacy.zip"), listOf("level.dat" to "legacy"))

            val info = manager.backupSave(fixture.id, "Legacy.zip")

            assertEquals("Legacy", info.saveName)
            assertEquals(mapOf("level.dat" to "legacy"), zipEntries(info.archive))
            assertTrue(root.resolve("saves/Legacy.zip").isRegularFile())
        }

    @Test
    fun `cancelled backup leaves no part file`() =
        runBlocking {
            val store = store()
            val manager = SaveManager(store)
            val fixture = instance(store)
            val world = store.instancePath(fixture.id).resolve("saves/Big")
            write(world.resolve("level.dat"), "level")
            repeat(300) { index ->
                val file = world.resolve("region/f%03d.mca".format(index))
                file.parent.createDirectories()
                file.writeBytes(Random(index).nextBytes(64 * 1024))
            }
            val backups = backupDirectory(store, fixture)
            val job = Job()
            val cancelled = AtomicBoolean(false)
            val watcher = startCancelWatcher(backups, job, cancelled)
            try {
                assertFailsWith<CancellationException> {
                    withContext(job) {
                        manager.backupSave(fixture.id, "Big", JobContext(store, "fixture"))
                    }
                }
            } finally {
                watcher.join(5_000)
            }
            assertCancelledBackupLeavesNoTrace(backups, cancelled, world)
        }

    /** 后台轮询 `.part` 出现的瞬间取消任务，证明取消发生在写入过程中。 */
    private fun startCancelWatcher(
        backups: Path,
        job: Job,
        cancelled: AtomicBoolean,
    ): Thread =
        Thread {
            val deadline = System.nanoTime() + 15_000_000_000L
            while (System.nanoTime() < deadline) {
                val running =
                    runCatching {
                        backups.listDirectoryEntries("*.part").isNotEmpty()
                    }.getOrDefault(false)
                if (running) {
                    job.cancel()
                    cancelled.set(true)
                    return@Thread
                }
                Thread.yield()
            }
        }.apply {
            isDaemon = true
            start()
        }

    private fun assertCancelledBackupLeavesNoTrace(
        backups: Path,
        cancelled: AtomicBoolean,
        world: Path,
    ) {
        assertTrue(cancelled.get(), "watcher did not cancel the backup")
        assertTrue(leftovers(backups).isEmpty(), "取消后不得留下 .part")
        assertTrue(
            runCatching { backups.listDirectoryEntries("*.zip").isEmpty() }.getOrDefault(true),
            "取消后不得留下压缩包",
        )
        assertTrue(world.resolve("level.dat").isRegularFile(), "取消不得改动原存档")
    }

    @Test
    fun `list shows vanilla made backups with world name and time from the file name`() {
        val store = store()
        val manager = SaveManager(store)
        val fixture = instance(store)
        val directory = backupDirectory(store, fixture)
        val vanilla = directory.resolve("2026-10-29_18-45-41_喵.zip")
        writeZip(vanilla, listOf("level.dat" to "vanilla", "region/r.0.0.mca" to "r"))
        // 原版自己写的备份和启动器写的备份共用 backups/，一起列出。
        val ours = directory.resolve("2026-10-28_09-00-00_World.zip")
        writeZip(ours, listOf("level.dat" to "ours"))

        val backups = manager.listBackups(fixture.id)

        assertEquals(
            listOf("2026-10-29_18-45-41_喵", "2026-10-28_09-00-00_World"),
            backups.map { it.id },
        )
        val cat = backups.first()
        assertEquals("喵", cat.saveName)
        assertEquals(
            LocalDateTime
                .of(2026, 10, 29, 18, 45, 41)
                .atZone(ZoneId.systemDefault())
                .toInstant(),
            cat.createdAt,
        )
        assertEquals(vanilla.fileSize(), cat.size)
        assertTrue(cat.restorable)
        assertTrue(backups.last().restorable)
    }

    @Test
    fun `list keeps a non world zip but marks it as not restorable`() {
        val store = store()
        val manager = SaveManager(store)
        val fixture = instance(store)
        val directory = backupDirectory(store, fixture)
        val notes = directory.resolve("随便起名.zip")
        writeZip(notes, listOf("a.png" to "png"))
        val altered =
            LocalDateTime
                .of(
                    2026,
                    10,
                    1,
                    8,
                    30,
                ).atZone(ZoneId.systemDefault())
                .toInstant()
        Files.setLastModifiedTime(
            notes,
            java.nio.file.attribute.FileTime
                .from(altered),
        )

        val backup = manager.listBackups(fixture.id).single()

        assertEquals("随便起名", backup.saveName)
        assertFalse(backup.restorable, "没有 level.dat 的压缩包不能提供恢复")
        assertEquals(altered, backup.createdAt, "不符合原版命名时退回文件修改时间")
    }

    // ---- 恢复（非破坏性复制） ----

    @Test
    fun `restore creates a sibling world and leaves the original untouched`() =
        runBlocking {
            val store = store()
            val manager = SaveManager(store)
            val fixture = instance(store)
            val root = store.instancePath(fixture.id)
            world(root, "World")
            val info = manager.backupSave(fixture.id, "World")
            val original = root.resolve("saves/World")
            write(original.resolve("level.dat"), "changed")
            write(original.resolve("extra.txt"), "user content")

            val restored = manager.restoreSave(fixture.id, info.id)

            assertNotEquals(original.fileName, restored.fileName, "恢复必须新建同级目录")
            assertEquals(
                root.resolve("saves").toAbsolutePath().normalize(),
                restored.parent.toAbsolutePath().normalize(),
            )
            assertTrue(
                restored.fileName.toString().startsWith("World（备份 "),
                "恢复目录名应为「世界名（备份 时间）」：${restored.fileName}",
            )
            assertEquals("level", restored.resolve("level.dat").readText())
            assertEquals("level-region", restored.resolve("region/r.0.0.mca").readText())
            // 原世界一个字节都不动。
            assertEquals("changed", original.resolve("level.dat").readText())
            assertEquals("user content", original.resolve("extra.txt").readText())
            assertEquals("level-player", original.resolve("playerdata/player.dat").readText())
            assertFalse(restored.resolve("extra.txt").exists())
            assertEquals(1, manager.listBackups(fixture.id).size, "恢复不得新增或删除备份")
        }

    @Test
    fun `restore skips session lock`() =
        runBlocking {
            val store = store()
            val manager = SaveManager(store)
            val fixture = instance(store)
            val root = store.instancePath(fixture.id)
            world(root, "World")
            write(root.resolve("saves/World/session.lock"), "stale")
            val info = manager.backupSave(fixture.id, "World")

            val restored = manager.restoreSave(fixture.id, info.id)

            assertFalse(restored.resolve("session.lock").exists(), "复制出来的世界不得带 session.lock")
            assertEquals("level", restored.resolve("level.dat").readText())
        }

    @Test
    fun `restore adds a numeric suffix when the sibling name is taken`() =
        runBlocking {
            val store = store()
            val manager = SaveManager(store)
            val fixture = instance(store)
            world(store.instancePath(fixture.id), "World")
            val info = manager.backupSave(fixture.id, "World")

            val first = manager.restoreSave(fixture.id, info.id)
            val second = manager.restoreSave(fixture.id, info.id)

            assertNotEquals(first, second)
            assertEquals("${first.fileName} 2", second.fileName.toString())
            assertTrue(first.isDirectory() && second.isDirectory())
            assertEquals("level", second.resolve("level.dat").readText())
        }
}
