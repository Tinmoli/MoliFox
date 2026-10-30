// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证存档恢复校验、替换流程、跨实例复制与回收的行为与边界。

package dev.molifox.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SaveManagerRecoveryTest : SaveManagerTestSupport("launcher-core-saves-recovery") {
    @Test
    fun `restore refuses an archive without level dat`() =
        runBlocking {
            val store = store()
            val manager = SaveManager(store)
            val fixture = instance(store)
            val root = store.instancePath(fixture.id)
            root.resolve("saves").createDirectories()
            writeZip(
                backupDirectory(store, fixture).resolve("2026-10-29_10-00-00_NoWorld.zip"),
                listOf("a.png" to "png"),
            )

            val error =
                assertFailsWith<IllegalArgumentException> {
                    manager.restoreSave(fixture.id, "2026-10-29_10-00-00_NoWorld")
                }

            assertTrue(error.message.orEmpty().contains("level.dat"))
            assertTrue(manager.listSaves(fixture.id).isEmpty())
            assertTrue(
                root
                    .resolve(
                        "saves",
                    ).listDirectoryEntries()
                    .none { it.name.startsWith(".molifox-") },
                "失败后不得留下临时目录",
            )
        }

    @Test
    fun `restore refuses while a world is held by the game`() =
        runBlocking {
            val store = store()
            val manager = SaveManager(store)
            val fixture = instance(store)
            val root = store.instancePath(fixture.id)
            world(root, "World")
            val info = manager.backupSave(fixture.id, "World")

            withSessionLock(root.resolve("saves/World")) {
                val error =
                    assertFailsWith<IllegalArgumentException> {
                        manager.restoreSave(fixture.id, info.id)
                    }
                assertTrue(error.message.orEmpty().contains("正在游戏中打开"))
            }
            assertEquals(listOf("World"), manager.listSaves(fixture.id).map { it.name })
        }

    @Test
    fun `restore rejects entries that escape the saves directory`() =
        runBlocking {
            val store = store()
            val manager = SaveManager(store)
            val fixture = instance(store)
            val root = store.instancePath(fixture.id)
            root.resolve("saves").createDirectories()
            val archive = backupDirectory(store, fixture).resolve("2026-10-27_12-00-00_Evil.zip")
            writeZip(
                archive,
                listOf(
                    "level.dat" to "evil",
                    "../escape.txt" to "escaped",
                    "region/r.0.0.mca" to "r",
                ),
            )

            val error =
                assertFailsWith<IllegalArgumentException> {
                    manager.restoreSave(fixture.id, "2026-10-27_12-00-00_Evil")
                }

            assertTrue(error.message.orEmpty().contains("向上跳转"))
            assertFalse(root.resolve("saves/Evil").exists())
            assertFalse(root.resolve("escape.txt").exists(), "不得写出 saves 目录")
            assertTrue(manager.listSaves(fixture.id).isEmpty())
            assertTrue(
                root
                    .resolve(
                        "saves",
                    ).listDirectoryEntries()
                    .none { it.name.startsWith(".molifox-") },
                "失败后不得留下临时目录",
            )
        }

    @Test
    fun `backup does not follow a junction inside the world`() =
        runBlocking {
            val store = store()
            val manager = SaveManager(store)
            val fixture = instance(store)
            val root = store.instancePath(fixture.id)
            world(root, "World")
            val outside = temporary.resolve("outside-region").apply { createDirectories() }
            write(outside.resolve("secret.mca"), "outside")
            val created = linkDirectory(root.resolve("saves/World/linked"), outside)
            assumeTrue(created, "当前环境不允许创建符号链接或目录联接")

            val info = manager.backupSave(fixture.id, "World")

            val entries = zipEntries(info.archive)
            assertTrue(entries.keys.none { it.startsWith("linked/") }, "备份不得跟随目录联接")
            assertEquals("outside", outside.resolve("secret.mca").readText())
            assertTrue(entries.keys.contains("level.dat"))
        }

    // ---- 替换流程 ----

    @Test
    fun `replace keeps the old world renamed and puts the backup in place`() =
        runBlocking {
            val store = store()
            val manager = SaveManager(store)
            val fixture = instance(store)
            val root = store.instancePath(fixture.id)
            world(root, "World", content = "level")
            val info = manager.backupSave(fixture.id, "World")
            write(root.resolve("saves/World/level.dat"), "changed")

            val replaced = manager.replaceWorldWithBackup(fixture.id, info.id)

            assertEquals(
                root.resolve("saves/World").toAbsolutePath().normalize(),
                replaced.toAbsolutePath().normalize(),
            )
            assertEquals("level", replaced.resolve("level.dat").readText())
            assertEquals("level-region", replaced.resolve("region/r.0.0.mca").readText())
            val kept =
                root.resolve("saves").listDirectoryEntries().single {
                    it.name.startsWith("World（替换前 ")
                }
            assertTrue(kept.isDirectory())
            assertEquals("changed", kept.resolve("level.dat").readText(), "旧世界必须保留")
            // 替换前会把当时的世界再备份一份。
            val backups = manager.listBackups(fixture.id)
            assertTrue(backups.size >= 2)
            assertTrue(backups.any { it.restorable })
            assertTrue(
                root
                    .resolve(
                        "saves",
                    ).listDirectoryEntries()
                    .none { it.name.startsWith(".molifox-") },
                "替换成功后不得留下临时目录",
            )
        }

    @Test
    fun `replace rolls back without touching the world when the archive is damaged`() =
        runBlocking {
            val store = store()
            val manager = SaveManager(store)
            val fixture = instance(store)
            val root = store.instancePath(fixture.id)
            world(root, "World")
            val info = manager.backupSave(fixture.id, "World")
            write(root.resolve("saves/World/level.dat"), "changed")
            info.archive.writeBytes("not a zip anymore".toByteArray())

            assertFailsWith<Exception> { manager.replaceWorldWithBackup(fixture.id, info.id) }

            assertEquals("changed", root.resolve("saves/World/level.dat").readText(), "失败后原存档必须完好")
            assertEquals(
                "level-player",
                root.resolve("saves/World/playerdata/player.dat").readText(),
            )
            assertTrue(
                root
                    .resolve(
                        "saves",
                    ).listDirectoryEntries()
                    .none { it.name.startsWith(".molifox-") },
                "失败后不得留下临时目录",
            )
            assertTrue(
                root.resolve("saves").listDirectoryEntries().none { it.name.contains("（替换前") },
                "失败后不得改名旧世界",
            )
        }

    @Test
    fun `replace rejects a backup without non empty region files`() =
        runBlocking {
            val store = store()
            val manager = SaveManager(store)
            val fixture = instance(store)
            val root = store.instancePath(fixture.id)
            world(root, "World")
            writeZip(
                backupDirectory(store, fixture).resolve("2026-10-29_11-00-00_World.zip"),
                listOf("level.dat" to "level", "region/r.0.0.mca" to ""),
            )

            val error =
                assertFailsWith<IllegalArgumentException> {
                    manager.replaceWorldWithBackup(fixture.id, "2026-10-29_11-00-00_World")
                }

            assertTrue(error.message.orEmpty().contains("区域文件"))
            assertEquals("level", root.resolve("saves/World/level.dat").readText())
            assertTrue(
                root.resolve("saves").listDirectoryEntries().none { it.name.contains("（替换前") },
                "校验失败不得改名旧世界",
            )
        }

    @Test
    fun `replace refuses while the world is held by the game`() =
        runBlocking {
            val store = store()
            val manager = SaveManager(store)
            val fixture = instance(store)
            val root = store.instancePath(fixture.id)
            world(root, "World")
            val info = manager.backupSave(fixture.id, "World")

            withSessionLock(root.resolve("saves/World")) {
                val error =
                    assertFailsWith<IllegalArgumentException> {
                        manager.replaceWorldWithBackup(fixture.id, info.id)
                    }
                assertTrue(error.message.orEmpty().contains("正在游戏中打开"))
            }
            assertEquals("level", root.resolve("saves/World/level.dat").readText())
        }

    // ---- 跨实例复制 ----

    @Test
    fun `copy duplicates a world into another isolated instance`() =
        runBlocking {
            val store = store()
            val manager = SaveManager(store)
            val source = instance(store, "Source")
            val target = instance(store, "Target")
            world(store.instancePath(source.id), "World")

            val copied =
                manager.copySaveToInstance(
                    source.id,
                    "World",
                    target.id,
                    context = JobContext(store, "fixture"),
                )

            val targetSaves = store.instancePath(target.id).resolve("saves")
            assertEquals(
                targetSaves.resolve("World").toAbsolutePath().normalize(),
                copied.toAbsolutePath().normalize(),
            )
            assertEquals("level", copied.resolve("level.dat").readText())
            assertEquals("level-region", copied.resolve("region/r.0.0.mca").readText())
            assertEquals("level-player", copied.resolve("playerdata/player.dat").readText())
            assertEquals(
                "level",
                store.instancePath(source.id).resolve("saves/World/level.dat").readText(),
                "复制不得移动源存档",
            )
            assertTrue(targetSaves.listDirectoryEntries().none { it.name.startsWith(".molifox-") })
        }

    @Test
    fun `copy renames with a sequence when the target already has the same save`() =
        runBlocking {
            val store = store()
            val manager = SaveManager(store)
            val source = instance(store, "Source")
            val target = instance(store, "Target")
            world(store.instancePath(source.id), "World", content = "source")
            write(store.instancePath(target.id).resolve("saves/World/level.dat"), "target")

            val copied = manager.copySaveToInstance(source.id, "World", target.id, rename = "World")

            assertEquals("World 2", copied.fileName.toString())
            val targetSaves = store.instancePath(target.id).resolve("saves")
            assertEquals("target", targetSaves.resolve("World/level.dat").readText())
            assertEquals("source", copied.resolve("level.dat").readText())
        }

    @Test
    fun `copy supports external instances but rejects a shared physical directory`() =
        runBlocking {
            val store = store()
            val manager = SaveManager(store)
            val source = instance(store, "Source")
            val external = instance(store, "External", external = true)
            world(store.instancePath(source.id), "World")

            val copied = manager.copySaveToInstance(source.id, "World", external.id)
            assertTrue(copied.resolve("level.dat").isRegularFile())

            // 隔离开启的源 → 共用游戏目录的目标：两者是不同物理目录，允许复制，存档落在游戏根的 saves。
            val shared = instance(store, "Shared", isolate = false)
            val intoShared = manager.copySaveToInstance(source.id, "World", shared.id)
            assertEquals(
                store.minecraftRoot
                    .resolve("saves")
                    .toAbsolutePath()
                    .normalize(),
                intoShared.parent.toAbsolutePath().normalize(),
            )

            // 两个共用实例实际指向同一个存档目录，必须拒绝。
            val sharedSource = instance(store, "Shared Source", isolate = false)
            world(store.instancePath(sharedSource.id), "World")
            val sharedTarget = instance(store, "Shared Target", isolate = false)
            val sharedError =
                assertFailsWith<IllegalArgumentException> {
                    manager.copySaveToInstance(sharedSource.id, "World", sharedTarget.id)
                }
            assertTrue(sharedError.message.orEmpty().contains("同一个存档目录"))

            val sameError =
                assertFailsWith<IllegalArgumentException> {
                    manager.copySaveToInstance(source.id, "World", source.id)
                }
            assertTrue(sameError.message.orEmpty().contains("相同"))
        }

    @Test
    fun `copy of a zip save keeps the archive extension`() =
        runBlocking {
            val store = store()
            val manager = SaveManager(store)
            val source = instance(store, "Source")
            val target = instance(store, "Target")
            writeZip(
                store.instancePath(source.id).resolve("saves/Legacy.zip"),
                listOf("level.dat" to "legacy"),
            )

            val copied = manager.copySaveToInstance(source.id, "Legacy.zip", target.id)

            assertEquals("Legacy.zip", copied.fileName.toString())
            assertTrue(copied.isRegularFile())
            assertEquals(mapOf("level.dat" to "legacy"), zipEntries(copied))
        }

    // ---- 删除 ----

    @Test
    fun `deleteBackup moves the archive into the trash`() =
        runBlocking {
            val store = store()
            val manager = SaveManager(store)
            val fixture = instance(store)
            world(store.instancePath(fixture.id), "World")
            val info = manager.backupSave(fixture.id, "World")

            // 测试 JVM 通常是无头的，回收站会退回到启动器数据目录；把它限制在临时目录内。
            val previous = System.getProperty("molifox.data.dir")
            System.setProperty("molifox.data.dir", temporary.resolve("platform").toString())
            val deleted =
                try {
                    manager.deleteBackup(fixture.id, info.id)
                } finally {
                    if (previous == null) {
                        System.clearProperty("molifox.data.dir")
                    } else {
                        System.setProperty("molifox.data.dir", previous)
                    }
                }

            assertTrue(deleted)
            assertFalse(info.archive.exists(), "删除备份后压缩包不应留在原地")
            assertTrue(manager.listBackups(fixture.id).isEmpty())
            assertFalse(manager.deleteBackup(fixture.id, info.id), "重复删除返回 false")
        }

    @Test
    fun `deleteSave moves the world into the trash`() {
        val store = store()
        val manager = SaveManager(store)
        val fixture = instance(store)
        val root = store.instancePath(fixture.id)
        world(root, "World")

        val previous = System.getProperty("molifox.data.dir")
        System.setProperty("molifox.data.dir", temporary.resolve("platform").toString())
        val deleted =
            try {
                manager.deleteSave(fixture.id, "World")
            } finally {
                if (previous == null) {
                    System.clearProperty("molifox.data.dir")
                } else {
                    System.setProperty("molifox.data.dir", previous)
                }
            }

        assertTrue(deleted)
        assertFalse(root.resolve("saves/World").exists())
        assertFalse(manager.deleteSave(fixture.id, "World"), "找不到的存档返回 false")
    }
}
