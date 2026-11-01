// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验证原理图目录内文件/文件夹的重命名与回收站删除，不访问网络。

package dev.molifox.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SchematicEntryActionsTest {
    private val temporary: Path = workTestDirectory("launcher-core-schematic-entries")

    @AfterEach
    fun cleanUp() {
        removeTestTree(temporary)
    }

    private fun store(): LauncherStore {
        val platform =
            object : PlatformServices by DesktopPlatform() {
                override val defaultDataDirectory =
                    temporary.resolve("profile-${UUID.randomUUID()}")

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
                name = "Schematics",
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

    private fun fixture(action: (ContentService, GameInstance, Path) -> Unit) {
        val store = store()
        val instance = instance(store)
        val network = NetworkClient()
        try {
            val service =
                ContentService(
                    store,
                    network,
                    MinecraftInstaller(store, network, JavaService(store, network)),
                )
            action(service, instance, store.instancePath(instance.id))
        } finally {
            network.close()
        }
    }

    private fun root(instancePath: Path): Path = instancePath.resolve("schematics").also { it.createDirectories() }

    @Test
    fun `renames a folder and a nested file without leaving the original path`() =
        fixture { service, instance, instancePath ->
            val schematics = root(instancePath)
            schematics.resolve("Folder/b.litematic").apply {
                parent.createDirectories()
                writeText("b")
            }

            service.renameSchematicEntry(instance.id, "Folder", "Renamed")
            assertTrue(schematics.resolve("Renamed").isDirectory())
            assertEquals(false, schematics.resolve("Folder").exists())

            service.renameSchematicEntry(instance.id, "Renamed/b.litematic", "c.litematic")
            assertTrue(schematics.resolve("Renamed/c.litematic").exists())
            assertEquals(false, schematics.resolve("Renamed/b.litematic").exists())
        }

    @Test
    fun `renaming onto an existing entry is rejected with a readable message`() =
        fixture { service, instance, instancePath ->
            val schematics = root(instancePath)
            schematics.resolve("a.litematic").writeText("a")
            schematics.resolve("b.litematic").writeText("b")

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    service.renameSchematicEntry(instance.id, "a.litematic", "b.litematic")
                }
            assertContains(failure.message.orEmpty(), "已存在")
            assertEquals("a", schematics.resolve("a.litematic").toFile().readText())
        }

    @Test
    fun `rejects traversal and absolute paths`() =
        fixture { service, instance, instancePath ->
            val schematics = root(instancePath)
            schematics.resolve("a.litematic").writeText("a")

            assertFailsWith<IllegalArgumentException> {
                service.renameSchematicEntry(instance.id, "../outside", "x")
            }
            assertFailsWith<IllegalArgumentException> {
                service.deleteSchematicEntry(instance.id, "../outside")
            }
        }

    @Test
    fun `deleting a folder moves it and its contents to the trash`() =
        fixture { service, instance, instancePath ->
            val schematics = root(instancePath)
            schematics.resolve("Folder/b.litematic").apply {
                parent.createDirectories()
                writeText("b")
            }

            service.deleteSchematicEntry(instance.id, "Folder")

            assertEquals(false, schematics.resolve("Folder").exists())
            val moved = temporary.resolve("trash").listDirectoryEntries().single()
            assertTrue(moved.isDirectory())
            assertTrue(moved.resolve("b.litematic").exists())
        }
}
