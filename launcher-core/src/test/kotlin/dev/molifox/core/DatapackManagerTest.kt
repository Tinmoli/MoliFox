// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证存档数据包列表、安装校验、删除与 level.dat 里的启用/禁用往返。

package dev.molifox.core

import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.*
import kotlin.test.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class DatapackManagerTest {
    /** 本次测试的数据目录：项目 `.work/` 下，测试结束（含失败）递归删除。 */
    private val temporary: Path = workTestDirectory("launcher-core-datapacks")

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
        store.mutate {
            it.copy(
                instances = it.instances + instance,
                settings = it.settings.copy(isolateVersions = true),
            )
        }
        store.instancePath(instance.id).createDirectories()
        return store.instance(instance.id)
    }

    /** 一个带 Data.Packs 的真实 level.dat（gzip NBT），额外标签用于验证「其他标签原样保留」。 */
    private fun writeLevelDat(
        world: Path,
        enabled: List<String> = listOf("vanilla"),
        disabled: List<String> = emptyList(),
    ) {
        val bytes =
            gzipBytes(
                TestNbtWriter().root {
                    nested("Data") {
                        string("LevelName", "我的世界")
                        long("RandomSeed", 42L)
                        nested("Version") { string("Id", "1.20.1") }
                        nested("Packs") {
                            list("Enabled", 8, enabled.size) {
                                enabled.forEach { stringElement(it) }
                            }
                            list("Disabled", 8, disabled.size) {
                                disabled.forEach { stringElement(it) }
                            }
                        }
                    }
                }
            )
        world.createDirectories()
        world.resolve("level.dat").writeBytes(bytes)
    }

    /** 建一个世界：真实 level.dat + 一个区域文件。 */
    private fun world(
        store: LauncherStore,
        name: String,
        enabled: List<String> = listOf("vanilla"),
        disabled: List<String> = emptyList(),
    ): Path {
        // 每个测试只建一个实例，这里直接取它的存档目录。
        val world = store.instancePath(store.data.instances.single().id).resolve("saves").resolve(name)
        writeLevelDat(world, enabled, disabled)
        world.resolve("region/r.0.0.mca").parent.createDirectories()
        world.resolve("region/r.0.0.mca").writeText("region")
        return world
    }

    private fun zip(path: Path, entries: List<Pair<String, String>>) {
        path.parent.createDirectories()
        ZipOutputStream(path.outputStream().buffered()).use { output ->
            entries.forEach { (name, text) ->
                output.putNextEntry(ZipEntry(name))
                output.write(text.toByteArray())
                output.closeEntry()
            }
        }
    }

    private fun validPack(path: Path, format: Int = 15) {
        zip(
            path,
            listOf(
                "pack.mcmeta" to """{"pack":{"pack_format":$format,"description":"test"}}""",
                "data/demo/function/tick.mcfunction" to "say hi",
            ),
        )
    }

    private fun enabledOf(world: Path): List<String> {
        val packs = readCompound(world.resolve("level.dat"))!!.compound("Data")!!.compound("Packs")!!
        return packs.list("Enabled")!!.elements.map { (it as NbtString).value }
    }

    private fun disabledOf(world: Path): List<String> {
        val packs = readCompound(world.resolve("level.dat"))!!.compound("Data")!!.compound("Packs")!!
        return packs.list("Disabled")!!.elements.map { (it as NbtString).value }
    }

    private fun <T> withSessionLock(world: Path, block: () -> T): T {
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
    fun `list reports zip files and folders with enabled state`() {
        val store = store()
        val manager = DatapackManager(store)
        val fixture = instance(store)
        val world = world(store, "World", enabled = listOf("vanilla", "file/on.zip"), disabled = listOf("file/off.zip"))
        val directory = world.resolve("datapacks").apply { createDirectories() }
        validPack(directory.resolve("on.zip"))
        validPack(directory.resolve("off.zip"))
        directory.resolve("folder").createDirectories()
        directory.resolve("folder/pack.mcmeta").writeText("""{"pack":{"pack_format":15}}""")
        directory.resolve("notes.txt").writeText("ignore")
        directory.resolve(".molifox-staging.tmp").writeText("ignore")

        val listing = manager.list(fixture.id, "World")

        assertEquals("World", listing.world)
        assertTrue(listing.toggleAvailable)
        assertEquals("", listing.note)
        assertEquals(listOf("folder", "off.zip", "on.zip"), listing.entries.map { it.name })
        val byName = listing.entries.associateBy { it.name }
        assertEquals(true, byName.getValue("on.zip").enabled)
        assertEquals(false, byName.getValue("off.zip").enabled)
        // 两条列表都没提到的数据包按原版规则默认启用。
        assertEquals(true, byName.getValue("folder").enabled)
        assertTrue(byName.getValue("folder").directory)
        assertTrue(byName.getValue("on.zip").size > 0)
        assertTrue(byName.getValue("folder").size > 0)
    }

    @Test
    fun `list falls back to read only when level dat cannot be parsed`() {
        val store = store()
        val manager = DatapackManager(store)
        val fixture = instance(store)
        val world = world(store, "World")
        world.resolve("level.dat").writeText("not nbt at all")
        val directory = world.resolve("datapacks").apply { createDirectories() }
        validPack(directory.resolve("on.zip"))

        val listing = manager.list(fixture.id, "World")

        assertFalse(listing.toggleAvailable, "解析失败的 level.dat 不应提供开关")
        assertTrue(listing.note.contains("只读"))
        assertEquals(1, listing.entries.size)
        assertNull(listing.entries.single().enabled, "状态未知时不假装已启用")

        val before = world.resolve("level.dat").readBytes()
        assertFailsWith<IllegalArgumentException> {
            manager.setEnabled(fixture.id, "World", "on.zip", false)
        }
        assertContentEquals(before, world.resolve("level.dat").readBytes(), "解析失败时不得改写 level.dat")
    }

    // ---- 安装 ----

    @Test
    fun `install rejects a zip without pack mcmeta`() {
        val store = store()
        val manager = DatapackManager(store)
        val fixture = instance(store)
        world(store, "World")
        val archive = temporary.resolve("no-manifest.zip")
        zip(archive, listOf("data/demo/function/tick.mcfunction" to "say hi"))

        val error = assertFailsWith<IllegalArgumentException> {
            manager.install(fixture.id, "World", archive)
        }

        assertTrue(error.message.orEmpty().contains("pack.mcmeta"))
        assertTrue(manager.list(fixture.id, "World").entries.isEmpty(), "校验失败不得安装文件")
    }

    @Test
    fun `install rejects a manifest without a numeric pack format`() {
        val store = store()
        val manager = DatapackManager(store)
        val fixture = instance(store)
        world(store, "World")
        val archive = temporary.resolve("bad-format.zip")
        zip(archive, listOf("pack.mcmeta" to """{"pack":{"description":"no format"}}"""))

        val error = assertFailsWith<IllegalArgumentException> {
            manager.install(fixture.id, "World", archive)
        }

        assertTrue(error.message.orEmpty().contains("pack_format"))
        assertTrue(manager.list(fixture.id, "World").entries.isEmpty())
    }

    @Test
    fun `install rejects a manifest outside the archive root`() {
        val store = store()
        val manager = DatapackManager(store)
        val fixture = instance(store)
        world(store, "World")
        val archive = temporary.resolve("nested.zip")
        zip(archive, listOf("wrapped/pack.mcmeta" to """{"pack":{"pack_format":15}}"""))

        assertFailsWith<IllegalArgumentException> { manager.install(fixture.id, "World", archive) }
        assertTrue(manager.list(fixture.id, "World").entries.isEmpty())
    }

    @Test
    fun `install copies a valid datapack into the world`() {
        val store = store()
        val manager = DatapackManager(store)
        val fixture = instance(store)
        world(store, "World")
        val archive = temporary.resolve("demo.zip")
        validPack(archive)

        val installed = manager.install(fixture.id, "World", archive)

        assertEquals("demo.zip", installed)
        val target = store.instancePath(fixture.id).resolve("saves/World/datapacks/demo.zip")
        assertTrue(target.isRegularFile())
        assertEquals(archive.readBytes().size, target.readBytes().size)
        val entry = manager.list(fixture.id, "World").entries.single()
        assertEquals("demo.zip", entry.name)
        assertEquals(true, entry.enabled, "未列入 Disabled 的数据包默认启用")
    }

    @Test
    fun `install refuses when a datapack with the same name exists`() {
        val store = store()
        val manager = DatapackManager(store)
        val fixture = instance(store)
        val world = world(store, "World")
        val directory = world.resolve("datapacks").apply { createDirectories() }
        validPack(directory.resolve("demo.zip"))
        val archive = temporary.resolve("demo.zip")
        validPack(archive)

        val error = assertFailsWith<IllegalArgumentException> {
            manager.install(fixture.id, "World", archive)
        }

        assertTrue(error.message.orEmpty().contains("同名"))
    }

    // ---- 启用/禁用 ----

    @Test
    fun `enabling and disabling round trips through level dat`() {
        val store = store()
        val manager = DatapackManager(store)
        val fixture = instance(store)
        val world = world(store, "World")
        val directory = world.resolve("datapacks").apply { createDirectories() }
        validPack(directory.resolve("demo.zip"))
        val before = readCompound(world.resolve("level.dat"))!!
        assertEquals(listOf("vanilla"), enabledOf(world))
        assertTrue(disabledOf(world).isEmpty())

        manager.setEnabled(fixture.id, "World", "demo.zip", false)

        assertEquals(listOf("file/demo.zip"), disabledOf(world))
        assertEquals(listOf("vanilla"), enabledOf(world), "启用列表里的其他条目必须保留")
        assertEquals(false, manager.list(fixture.id, "World").entries.single().enabled)

        manager.setEnabled(fixture.id, "World", "demo.zip", true)

        assertTrue(disabledOf(world).isEmpty())
        assertTrue(enabledOf(world).contains("file/demo.zip"))
        assertEquals(true, manager.list(fixture.id, "World").entries.single().enabled)
        // 其他标签原样保留。
        val after = readCompound(world.resolve("level.dat"))!!
        assertEquals("我的世界", after.compound("Data")?.string("LevelName"))
        assertEquals(42L, (after.compound("Data")!!["RandomSeed"] as NbtLong).value)
        assertEquals("1.20.1", after.compound("Data")?.compound("Version")?.string("Id"))
        assertEquals(before.entries.keys, after.entries.keys)
        assertEquals(before.compound("Data")!!.entries.keys, after.compound("Data")!!.entries.keys)
    }

    @Test
    fun `enabling a fresh pack keeps the vanilla entry`() {
        val store = store()
        val manager = DatapackManager(store)
        val fixture = instance(store)
        // 没有 Packs 标签的老 level.dat：写入时补上 vanilla，避免把原版数据包关掉。
        val world = store.instancePath(fixture.id).resolve("saves/World")
        world.createDirectories()
        world.resolve("level.dat").writeBytes(
            gzipBytes(
                TestNbtWriter().root {
                    nested("Data") { string("LevelName", "旧世界") }
                }
            )
        )
        val directory = world.resolve("datapacks").apply { createDirectories() }
        validPack(directory.resolve("demo.zip"))

        manager.setEnabled(fixture.id, "World", "demo.zip", false)

        assertEquals(listOf("vanilla"), enabledOf(world))
        assertEquals(listOf("file/demo.zip"), disabledOf(world))
    }

    @Test
    fun `setEnabled refuses while the world is held by the game`() {
        val store = store()
        val manager = DatapackManager(store)
        val fixture = instance(store)
        val world = world(store, "World")
        val directory = world.resolve("datapacks").apply { createDirectories() }
        validPack(directory.resolve("demo.zip"))

        withSessionLock(world) {
            val error = assertFailsWith<IllegalArgumentException> {
                manager.setEnabled(fixture.id, "World", "demo.zip", false)
            }
            assertTrue(error.message.orEmpty().contains("正在游戏中打开"))
            assertFailsWith<IllegalArgumentException> {
                manager.install(fixture.id, "World", temporary.resolve("demo.zip").also { validPack(it) })
            }
        }
        assertTrue(disabledOf(world).isEmpty(), "被拒绝时不得写 level.dat")
    }

    // ---- 删除 ----

    @Test
    fun `delete moves the datapack to trash and prunes level dat`() {
        val store = store()
        val manager = DatapackManager(store)
        val fixture = instance(store)
        val world = world(store, "World", enabled = listOf("vanilla"), disabled = listOf("file/demo.zip"))
        val archive = world.resolve("datapacks/demo.zip")
        validPack(archive)
        val previous = System.getProperty("molifox.data.dir")
        System.setProperty("molifox.data.dir", temporary.resolve("platform").toString())
        val deleted =
            try {
                manager.delete(fixture.id, "World", "demo.zip")
            } finally {
                if (previous == null) System.clearProperty("molifox.data.dir")
                else System.setProperty("molifox.data.dir", previous)
            }

        assertTrue(deleted)
        assertFalse(archive.exists())
        assertTrue(disabledOf(world).isEmpty(), "删除后应清掉 level.dat 里的旧条目")
        assertTrue(manager.list(fixture.id, "World").entries.isEmpty())
        assertFalse(manager.delete(fixture.id, "World", "demo.zip"), "重复删除返回 false")
    }

    @Test
    fun `missing world or datapack is rejected`() {
        val store = store()
        val manager = DatapackManager(store)
        val fixture = instance(store)
        world(store, "World")

        assertFailsWith<IllegalArgumentException> { manager.list(fixture.id, "Missing") }
        assertFailsWith<IllegalArgumentException> {
            manager.setEnabled(fixture.id, "World", "missing.zip", false)
        }
        assertTrue(manager.list(fixture.id, "World").entries.isEmpty())
    }
}