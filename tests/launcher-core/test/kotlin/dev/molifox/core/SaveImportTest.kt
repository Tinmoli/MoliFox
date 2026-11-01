// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证存档 ZIP 校验、包装目录剥离、重名序号与无效输入不留残留。

package dev.molifox.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SaveImportTest {
    private val temporary: Path = workTestDirectory("launcher-core-save-import")

    @AfterEach
    fun cleanUp() {
        removeTestTree(temporary)
    }

    private fun store(): LauncherStore {
        val platform =
            object : PlatformServices by DesktopPlatform() {
                override val defaultDataDirectory =
                    temporary.resolve(
                        "profile-${UUID.randomUUID()}",
                    )

                override fun moveToTrash(file: Path) {
                    val target = temporary.resolve("trash/${UUID.randomUUID()}-${file.fileName}")
                    target.parent.createDirectories()
                    Files.move(file, target)
                }
            }
        return LauncherStore(platform)
    }

    private fun instance(store: LauncherStore): GameInstance {
        val instance =
            GameInstance(
                name = "Saves",
                gameVersion = "1.20.1",
                versionId = "fixture",
                loader = LoaderType.Fabric,
                installed = true,
                isolation = VersionIsolation.Enabled,
            )
        store.mutate { it.copy(instances = listOf(instance)) }
        store.instancePath(instance.id).resolve("saves").createDirectories()
        return store.instance(instance.id)
    }

    private fun zip(
        path: Path,
        entries: Map<String, ByteArray>,
    ): Path =
        path.also { target ->
            target.parent.createDirectories()
            ZipOutputStream(target.outputStream()).use { archive ->
                entries.forEach { (name, bytes) ->
                    archive.putNextEntry(ZipEntry(name))
                    archive.write(bytes)
                    archive.closeEntry()
                }
            }
        }

    private val level = "level".toByteArray()

    @Test
    fun `archive with level dat at root needs no wrapper`() {
        val archive =
            zip(
                temporary.resolve("Root.zip"),
                mapOf("level.dat" to level, "region/r.0.0.mca" to byteArrayOf(1, 2, 3)),
            )
        assertEquals("", SaveArchive.inspect(archive).prefix)
        assertFalse(SaveArchive.inspect(archive).wrapped)
    }

    @Test
    fun `archive with one wrapping folder is accepted and reports its prefix`() {
        val archive =
            zip(
                temporary.resolve("Wrapped.zip"),
                mapOf("MyWorld/level.dat" to level, "MyWorld/region/r.0.0.mca" to byteArrayOf(1)),
            )
        assertEquals("MyWorld/", SaveArchive.inspect(archive).prefix)
        assertTrue(SaveArchive.inspect(archive).wrapped)
    }

    @Test
    fun `archives without a world root are rejected`() {
        val missing =
            zip(temporary.resolve("Missing.zip"), mapOf("readme.txt" to "no".toByteArray()))
        assertFailsWith<IllegalArgumentException> { SaveArchive.inspect(missing) }
        val nested =
            zip(
                temporary.resolve("Nested.zip"),
                mapOf("Outer/Inner/level.dat" to level),
            )
        assertFailsWith<IllegalArgumentException> { SaveArchive.inspect(nested) }
        val empty = zip(temporary.resolve("EmptyLevel.zip"), mapOf("level.dat" to ByteArray(0)))
        assertFailsWith<IllegalArgumentException> { SaveArchive.inspect(empty) }
        val ambiguous =
            zip(
                temporary.resolve("Ambiguous.zip"),
                mapOf("A/level.dat" to level, "B/level.dat" to level),
            )
        assertFailsWith<IllegalArgumentException> { SaveArchive.inspect(ambiguous) }
        val escaping =
            zip(temporary.resolve("Escape.zip"), mapOf("../evil.txt" to "x".toByteArray()))
        assertFailsWith<IllegalArgumentException> { SaveArchive.inspect(escaping) }
    }

    @Test
    fun `importing a wrapped zip strips the wrapper and skips session lock`() =
        runBlocking {
            val store = store()
            val instance = instance(store)
            val archive =
                zip(
                    temporary.resolve("CoolWorld.zip"),
                    mapOf(
                        "CoolWorld/level.dat" to level,
                        "CoolWorld/region/r.0.0.mca" to byteArrayOf(1, 2, 3),
                        "CoolWorld/session.lock" to byteArrayOf(9),
                    ),
                )
            val saves = SaveManager(store)
            val world = saves.importSave(instance.id, archive)
            assertEquals("CoolWorld", world.name)
            assertTrue(world.resolve("level.dat").isRegularFile())
            assertTrue(world.resolve("region/r.0.0.mca").isRegularFile())
            assertFalse(world.resolve("session.lock").exists())
            // 再次导入同一个压缩包：重名加序号，不覆盖已有世界。
            val second = saves.importSave(instance.id, archive)
            assertEquals("CoolWorld (2)", second.name)
            assertEquals("level", world.resolve("level.dat").readText())
        }

    @Test
    fun `importing a folder copies the world and rejects folders without level dat`() =
        runBlocking {
            val store = store()
            val instance = instance(store)
            val saves = SaveManager(store)
            val folder = temporary.resolve("FolderWorld")
            folder.resolve("region").createDirectories()
            folder.resolve("level.dat").writeText("level")
            folder.resolve("region/r.0.0.mca").writeBytes(byteArrayOf(1, 2))
            val world = saves.importSave(instance.id, folder)
            assertEquals("FolderWorld", world.name)
            assertTrue(world.resolve("region").isDirectory())
            val broken = temporary.resolve("NotAWorld").apply { createDirectories() }
            broken.resolve("notes.txt").writeText("no")
            assertFailsWith<IllegalArgumentException> {
                runBlocking {
                    saves.importSave(
                        instance.id,
                        broken,
                    )
                }
            }
            assertFalse(
                store.instancePath(instance.id).resolve("saves").listDirectoryEntries().any {
                    it.name.startsWith(".molifox-saves-")
                },
            )
        }

    @Test
    fun `invalid zip leaves no staging directory behind`() =
        runBlocking {
            val store = store()
            val instance = instance(store)
            val saves = SaveManager(store)
            val invalid =
                zip(temporary.resolve("Bad.zip"), mapOf("notes/readme.txt" to "x".toByteArray()))
            assertFailsWith<IllegalArgumentException> { saves.importSave(instance.id, invalid) }
            val leftovers = store.instancePath(instance.id).resolve("saves").listDirectoryEntries()
            assertTrue(leftovers.isEmpty(), "不应留下临时目录：$leftovers")
        }
}
