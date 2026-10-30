// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证存档列表、原版备份命名、非破坏恢复、替换流程、跨实例复制和回收。

package dev.molifox.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.setLastModifiedTime
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SaveManagerTest {
    /** 本次测试的数据目录：项目 `.work/` 下，测试结束（含失败）递归删除。 */
    private val temporary: Path = workTestDirectory("launcher-core-saves")

    @AfterEach
    fun cleanUp() {
        removeTestTree(temporary)
    }

    private fun store() = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))

    /** 建一个实例：isolate=true 用版本隔离（独占目录），false 共用游戏目录；external 为外部实例。 */
    private fun instance(
        store: LauncherStore,
        name: String = "Fixture",
        isolate: Boolean = true,
        external: Boolean = false,
    ): GameInstance {
        val instance =
            GameInstance(
                name = name,
                gameVersion = "1.20.1",
                versionId = "1.20.1",
                gameDirectoryName = name,
                loader = LoaderType.Fabric,
                loaderVersion = "0.15.0",
                installed = true,
                managedGameDirectory = isolate,
                isolation = if (isolate) VersionIsolation.Enabled else VersionIsolation.Disabled,
                externalProfile = external,
            )
        store.mutate {
            it.copy(
                instances = it.instances + instance,
                settings = it.settings.copy(isolateVersions = isolate),
            )
        }
        store.instancePath(instance.id).createDirectories()
        return store.instance(instance.id)
    }

    private fun write(
        file: Path,
        text: String,
    ) {
        file.parent.createDirectories()
        file.writeText(text)
    }

    /** 建一个像样的世界：level.dat、region/ 与 playerdata/。 */
    private fun world(
        root: Path,
        name: String,
        content: String = "level",
    ) {
        write(root.resolve("saves/$name/level.dat"), content)
        write(root.resolve("saves/$name/region/r.0.0.mca"), "$content-region")
        write(root.resolve("saves/$name/playerdata/player.dat"), "$content-player")
    }

    private fun zipEntries(archive: Path): Map<String, String> =
        ZipFile(archive.toFile()).use { zip ->
            Collections
                .list(zip.entries())
                .filter { !it.isDirectory }
                .associate { entry ->
                    entry.name to
                        zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                }
        }

    private fun writeZip(
        archive: Path,
        entries: List<Pair<String, String>>,
    ) {
        archive.parent.createDirectories()
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
    }

    /** 与原版游戏共用的备份目录 `<游戏目录>/backups/`。 */
    private fun backupDirectory(
        store: LauncherStore,
        instance: GameInstance,
    ): Path = store.instancePath(instance.id).resolve("backups")

    private fun backupIds(
        store: LauncherStore,
        instance: GameInstance,
    ): List<String> = SaveManager(store).listBackups(instance.id).map { it.id }

    /** 备份目录里本次操作留下的临时文件（`.part` 或未完成的半成品）。 */
    private fun leftovers(directory: Path): List<String> =
        if (!directory.exists()) {
            emptyList()
        } else {
            directory.listDirectoryEntries().map { it.name }.filter { it.endsWith(".part") }
        }

    /** 原版命名：`yyyy-MM-dd_HH-mm-ss_<世界名>.zip`。 */
    private val vanillaBackupName =
        Regex("^\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}_.+\\.zip$")

    /** 在独立线程池里持有一个世界的 `session.lock`，模拟游戏正在打开该世界。 */
    private suspend fun <T> withSessionLock(
        world: Path,
        block: suspend () -> T,
    ): T {
        world.createDirectories()
        val lockFile = world.resolve("session.lock").apply { writeText("locked") }
        return FileChannel.open(lockFile, StandardOpenOption.WRITE).use { channel ->
            val lock = channel.lock()
            try {
                block()
            } finally {
                runCatching { lock.release() }
            }
        }
    }

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

    /** 先试符号链接，失败再退到目录联接（Windows 普通账户可用）；都不行时返回 false。 */
    private fun linkDirectory(
        link: Path,
        target: Path,
    ): Boolean {
        if (runCatching { Files.createSymbolicLink(link, target) }.isSuccess) return true
        return runCatching {
            val process =
                ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true)
                    .start()
            process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) &&
                process.exitValue() == 0
        }.getOrDefault(false)
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
            assertEquals(listOf(info.id), backupIds(store, fixture))
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
            val watcher =
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
            try {
                assertFailsWith<CancellationException> {
                    withContext(job) {
                        manager.backupSave(fixture.id, "Big", JobContext(store, "fixture"))
                    }
                }
            } finally {
                watcher.join(5_000)
            }
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
