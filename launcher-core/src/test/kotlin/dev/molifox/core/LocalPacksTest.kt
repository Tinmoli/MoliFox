// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证资源包/光影目录的列出、重名序号与导入校验。

package dev.molifox.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class LocalPacksTest {
    private val temporary: Path = workTestDirectory("launcher-core-local-packs")

    @AfterEach
    fun cleanUp() {
        removeTestTree(temporary)
    }

    private fun store(): LauncherStore {
        val platform =
            object : PlatformServices by DesktopPlatform() {
                override val defaultDataDirectory = temporary.resolve("profile-${UUID.randomUUID()}")

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
                name = "Packs",
                gameVersion = "1.20.1",
                versionId = "fixture",
                loader = LoaderType.Fabric,
                installed = true,
                isolation = VersionIsolation.Enabled,
            )
        store.mutate { it.copy(instances = listOf(instance)) }
        store.instancePath(instance.id).createDirectories()
        return store.instance(instance.id)
    }

    private fun zip(path: Path): Path =
        path.also { target ->
            target.parent.createDirectories()
            ZipOutputStream(target.outputStream()).use { archive ->
                archive.putNextEntry(ZipEntry("shaders/final.fsh"))
                archive.write("void main() {}".toByteArray())
                archive.closeEntry()
            }
        }

    @Test
    fun `shader listing reports zip files and folders but skips hidden and other files`() {
        val store = store()
        val instance = instance(store)
        val shaderpacks = store.instancePath(instance.id).resolve("shaderpacks")
        shaderpacks.createDirectories()
        zip(shaderpacks.resolve("Sildurs.zip"))
        shaderpacks.resolve("Complementary").createDirectories()
        shaderpacks.resolve("Complementary/shaders").createDirectories()
        zip(shaderpacks.resolve(".molifox-import-${UUID.randomUUID()}"))
        shaderpacks.resolve("readme.txt").writeText("hi")
        val entries = LocalPackStore(store).list(instance.id, PackFolder.ShaderPacks)
        assertEquals(listOf("Complementary", "Sildurs.zip"), entries.map { it.filename })
        assertTrue(entries.first().isDirectory)
        assertFalse(entries.last().isDirectory)
        assertTrue(entries.last().size > 0)
    }

    @Test
    fun `collision suffixing keeps the extension and never overwrites`() {
        val directory = temporary.resolve("names")
        directory.createDirectories()
        assertEquals("Pack.zip", uniqueChildName(directory, "Pack.zip"))
        directory.resolve("Pack.zip").writeText("one")
        assertEquals("Pack (2).zip", uniqueChildName(directory, "Pack.zip"))
        directory.resolve("Pack (2).zip").writeText("two")
        assertEquals("Pack (3).zip", uniqueChildName(directory, "Pack.zip"))
        directory.resolve("World").createDirectories()
        assertEquals("World (2)", uniqueChildName(directory, "World"))
    }

    @Test
    fun `importing the same zip twice adds a numeric suffix`() = runBlocking {
        val store = store()
        val instance = instance(store)
        val source = zip(temporary.resolve("Fancy.zip"))
        val packs = LocalPackStore(store)
        val first = packs.import(instance.id, PackFolder.ResourcePacks, listOf(source))
        val second = packs.import(instance.id, PackFolder.ResourcePacks, listOf(source))
        assertEquals(listOf("Fancy.zip"), first.map { it.filename })
        assertEquals(listOf("Fancy (2).zip"), second.map { it.filename })
        assertTrue(store.instancePath(instance.id).resolve("resourcepacks/Fancy (2).zip").isRegularFile())
    }

    @Test
    fun `folder import copies contents and rejects non zip files without leftovers`() = runBlocking {
        val store = store()
        val instance = instance(store)
        val folder = temporary.resolve("ShaderPack")
        folder.resolve("shaders").createDirectories()
        folder.resolve("shaders/final.fsh").writeText("void main() {}")
        val packs = LocalPackStore(store)
        val imported = packs.import(instance.id, PackFolder.ShaderPacks, listOf(folder))
        assertEquals(listOf("ShaderPack"), imported.map { it.filename })
        assertTrue(
            store.instancePath(instance.id).resolve("shaderpacks/ShaderPack/shaders/final.fsh").isRegularFile()
        )
        val other = temporary.resolve("notes.txt").apply { writeText("no") }
        val failure =
            assertFailsWith<IllegalArgumentException> {
                runBlocking { packs.import(instance.id, PackFolder.ShaderPacks, listOf(other)) }
            }
        assertTrue(failure.message.orEmpty().contains("只能导入"))
        assertEquals(
            listOf("ShaderPack"),
            store.instancePath(instance.id)
                .resolve("shaderpacks")
                .listDirectoryEntries()
                .map { it.name },
        )
    }
}