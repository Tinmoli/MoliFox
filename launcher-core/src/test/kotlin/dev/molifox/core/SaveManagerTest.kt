// 负责验证存档列表、备份、恢复、复制和回收的行为与边界。

package dev.molifox.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.*
import kotlin.random.Random
import kotlin.test.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SaveManagerTest {
    @TempDir lateinit var temporary: Path

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
                loader = LoaderType.Fabric,
                loaderVersion = "0.15.0",
                installed = true,
                managedGameDirectory = isolate,
                isolation = if (isolate) VersionIsolation.Enabled else VersionIsolation.Disabled,
                externalProfile = external,
            )
        store.mutate { it.copy(instances = it.instances + instance) }
        store.instancePath(instance.id).createDirectories()
        return store.instance(instance.id)
    }

    private fun write(file: Path, text: String) {
        file.parent.createDirectories()
        file.writeText(text)
    }

    /** 建一个像样的世界：level.dat、region/ 与 playerdata/。 */
    private fun world(root: Path, name: String, content: String = "level") {
        write(root.resolve("saves/$name/level.dat"), content)
        write(root.resolve("saves/$name/region/r.0.0.mca"), "$content-region")
        write(root.resolve("saves/$name/playerdata/player.dat"), "$content-player")
    }

    private fun zipEntries(archive: Path): Map<String, String> =
        ZipFile(archive.toFile()).use { zip ->
            Collections.list(zip.entries())
                .filter { !it.isDirectory }
                .associate { entry ->
                    entry.name to
                        zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                }
        }

    private fun writeZip(archive: Path, entries: List<Pair<String, String>>) {
        archive.parent.createDirectories()
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
    }

    private fun backupDirectory(store: LauncherStore, instance: GameInstance): Path =
        store.instancePath(instance.id).resolve(".molifox-saves-backups")

    private fun backupIds(store: LauncherStore, instance: GameInstance): List<String> =
        SaveManager(store).listBackups(instance.id).map { it.id }

    /** 备份区里本次操作留下的临时文件（`.part` 或未完成的半成品）。 */
    private fun leftovers(directory: Path): List<String> =
        if (!directory.exists()) emptyList()
        else directory.listDirectoryEntries().map { it.name }.filter { it.endsWith(".part") }

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
                it.path.parent.toAbsolutePath().normalize() ==
                    root.resolve("saves").toAbsolutePath().normalize()
            }
        )
    }

    /** 先试符号链接，失败再退到目录联接（Windows 普通账户可用）；都不行时返回 false。 */
    private fun linkDirectory(link: Path, target: Path): Boolean {
        if (runCatching { Files.createSymbolicLink(link, target) }.isSuccess) return true
        return runCatching {
                val process =
                    ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                        .redirectErrorStream(true)
                        .start()
                process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) &&
                    process.exitValue() == 0
            }
            .getOrDefault(false)
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
    fun `backup writes a readable archive and keeps the original save`() = runBlocking {
        val store = store()
        val manager = SaveManager(store)
        val fixture = instance(store)
        val root = store.instancePath(fixture.id)
        world(root, "World")
        val original = root.resolve("saves/World")

        val info = manager.backupSave(fixture.id, "World", JobContext(store, "fixture"))

        assertTrue(info.archive.startsWith(backupDirectory(store, fixture)))
        assertTrue(info.archive.name.endsWith("-World.zip"))
        assertEquals("World", info.saveName)
        assertEquals(info.archive.fileName.toString().removeSuffix(".zip"), info.id)
        assertEquals(info.archive.fileSize(), info.size)
        assertEquals(digest(info.archive), info.sha1)
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
    }

    @Test
    fun `backup twice in a row does not collide`() = runBlocking {
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
    fun `backup of a zip save copies the archive as is`() = runBlocking {
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
    fun `cancelled backup leaves no part file`() = runBlocking {
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
        val watcher = Thread {
            val deadline = System.nanoTime() + 15_000_000_000L
            while (System.nanoTime() < deadline) {
                val running = runCatching {
                    backups.listDirectoryEntries("*.part").isNotEmpty()
                }
                    .getOrDefault(false)
                if (running) {
                    job.cancel()
                    cancelled.set(true)
                    return@Thread
                }
                Thread.yield()
            }
        }
            .apply {
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

    // ---- 恢复 ----

    @Test
    fun `restore refuses to overwrite an existing save`() = runBlocking {
        val store = store()
        val manager = SaveManager(store)
        val fixture = instance(store)
        val root = store.instancePath(fixture.id)
        world(root, "World")
        val info = manager.backupSave(fixture.id, "World")
        write(root.resolve("saves/World/level.dat"), "changed")

        val error =
            assertFailsWith<IllegalArgumentException> {
                manager.restoreSave(fixture.id, info.id, overwrite = false)
            }

        assertTrue(error.message.orEmpty().contains("已存在"))
        assertEquals("changed", root.resolve("saves/World/level.dat").readText())
        assertEquals(listOf(info.id), backupIds(store, fixture))
    }

    @Test
    fun `restore with overwrite keeps the replaced save as a backup`() = runBlocking {
        val store = store()
        val manager = SaveManager(store)
        val fixture = instance(store)
        val root = store.instancePath(fixture.id)
        world(root, "World")
        val info = manager.backupSave(fixture.id, "World")
        write(root.resolve("saves/World/level.dat"), "changed")
        write(root.resolve("saves/World/extra.txt"), "user content")

        val restored = manager.restoreSave(fixture.id, info.id, overwrite = true)

        assertEquals(
            root.resolve("saves/World").toAbsolutePath().normalize(),
            restored.toAbsolutePath().normalize(),
        )
        assertEquals("level", restored.resolve("level.dat").readText())
        assertEquals("level-region", restored.resolve("region/r.0.0.mca").readText())
        assertFalse(restored.resolve("extra.txt").exists(), "恢复后不应残留被替换的内容")
        val backups = manager.listBackups(fixture.id)
        val replaced = backups.single { it.id.endsWith(".replaced") }
        assertEquals("World", replaced.saveName)
        assertEquals(
            mapOf(
                "level.dat" to "changed",
                "extra.txt" to "user content",
                "playerdata/player.dat" to "level-player",
                "region/r.0.0.mca" to "level-region",
            ),
            zipEntries(replaced.archive),
        )
        assertEquals(2, backups.size)
    }

    @Test
    fun `restore rejects entries that escape the saves directory`() = runBlocking {
        val store = store()
        val manager = SaveManager(store)
        val fixture = instance(store)
        val root = store.instancePath(fixture.id)
        root.resolve("saves").createDirectories()
        val archive = backupDirectory(store, fixture).resolve("20261027-120000-000-Evil.zip")
        writeZip(
            archive,
            listOf("level.dat" to "evil", "../escape.txt" to "escaped", "region/r.0.0.mca" to "r"),
        )

        val error =
            assertFailsWith<IllegalArgumentException> {
                manager.restoreSave(fixture.id, "20261027-120000-000-Evil")
            }

        assertTrue(error.message.orEmpty().contains("向上跳转"))
        assertFalse(root.resolve("saves/Evil").exists())
        assertFalse(root.resolve("escape.txt").exists(), "不得写出 saves 目录")
        assertTrue(manager.listSaves(fixture.id).isEmpty())
        assertTrue(
            root.resolve("saves").listDirectoryEntries().none { it.name.startsWith(".molifox-") },
            "失败后不得留下临时目录",
        )
    }

    @Test
    fun `restore rolls back when the archive is damaged`() = runBlocking {
        val store = store()
        val manager = SaveManager(store)
        val fixture = instance(store)
        val root = store.instancePath(fixture.id)
        world(root, "World")
        val info = manager.backupSave(fixture.id, "World")
        write(root.resolve("saves/World/level.dat"), "changed")
        info.archive.writeBytes("not a zip anymore".toByteArray())

        assertFailsWith<Exception> { manager.restoreSave(fixture.id, info.id, overwrite = true) }

        val restored = root.resolve("saves/World")
        assertEquals("changed", restored.resolve("level.dat").readText(), "失败后原存档必须完好")
        assertEquals("level-player", restored.resolve("playerdata/player.dat").readText())
        assertTrue(
            root.resolve("saves").listDirectoryEntries().none { it.name.startsWith(".molifox-") },
            "失败后不得留下半解压目录",
        )
        assertEquals(listOf(info.id), backupIds(store, fixture), "回滚不得留下 replaced 备份")
    }

    @Test
    fun `backup does not follow a junction inside the world`() = runBlocking {
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

    @Test
    fun `restore extracts a zip backup as a world directory`() = runBlocking {
        val store = store()
        val manager = SaveManager(store)
        val fixture = instance(store)
        val root = store.instancePath(fixture.id)
        writeZip(
            root.resolve("saves/Legacy.zip"),
            listOf("level.dat" to "legacy", "region/r.0.0.mca" to "r"),
        )
        val info = manager.backupSave(fixture.id, "Legacy.zip")

        val restored = manager.restoreSave(fixture.id, info.id)

        assertTrue(restored.isDirectory())
        assertEquals("Legacy", restored.fileName.toString())
        assertEquals("legacy", restored.resolve("level.dat").readText())
        assertEquals("r", restored.resolve("region/r.0.0.mca").readText())
    }

    // ---- 跨实例复制 ----

    @Test
    fun `copy duplicates a world into another isolated instance`() = runBlocking {
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
    fun `copy renames with a sequence when the target already has the same save`() = runBlocking {
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
    fun `copy rejects external and shared instances with a Chinese message`() = runBlocking {
        val store = store()
        val manager = SaveManager(store)
        val source = instance(store, "Source")
        val external = instance(store, "External", external = true)
        world(store.instancePath(source.id), "World")

        val externalError =
            assertFailsWith<IllegalArgumentException> {
                manager.copySaveToInstance(source.id, "World", external.id)
            }
        assertTrue(externalError.message.orEmpty().contains("外部游戏目录"))

        val shared = instance(store, "Shared", isolate = false)
        val sharedError =
            assertFailsWith<IllegalArgumentException> {
                manager.copySaveToInstance(source.id, "World", shared.id)
            }
        assertTrue(sharedError.message.orEmpty().contains("版本隔离"))

        val sameError =
            assertFailsWith<IllegalArgumentException> {
                manager.copySaveToInstance(source.id, "World", source.id)
            }
        assertTrue(sameError.message.orEmpty().contains("相同"))
    }

    @Test
    fun `copy of a zip save keeps the archive extension`() = runBlocking {
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
    fun `deleteBackup moves the archive into the trash`() = runBlocking {
        val store = store()
        val manager = SaveManager(store)
        val fixture = instance(store)
        world(store.instancePath(fixture.id), "World")
        val info = manager.backupSave(fixture.id, "World")

        // 测试 JVM 通常是无头的，回收站会退回到启动器数据目录；把它限制在 @TempDir 内。
        val previous = System.getProperty("molifox.data.dir")
        System.setProperty("molifox.data.dir", temporary.resolve("platform").toString())
        val deleted =
            try {
                manager.deleteBackup(fixture.id, info.id)
            } finally {
                if (previous == null) System.clearProperty("molifox.data.dir")
                else System.setProperty("molifox.data.dir", previous)
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
                if (previous == null) System.clearProperty("molifox.data.dir")
                else System.setProperty("molifox.data.dir", previous)
            }

        assertTrue(deleted)
        assertFalse(root.resolve("saves/World").exists())
        assertFalse(manager.deleteSave(fixture.id, "World"), "找不到的存档返回 false")
    }
}
