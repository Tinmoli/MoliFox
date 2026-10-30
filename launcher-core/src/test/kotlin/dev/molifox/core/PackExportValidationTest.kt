// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验证取消导出不留残留、写后重开校验与只校验已选条目。

package dev.molifox.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackExportValidationTest : PackExportTestSupport() {
    @Test
    fun `cancelling before the write leaves no target and no part file`() =
        runBlocking {
            fixture { store, instance, root ->
                write(root, "config/a.toml", "cfg")
                write(root, "mods/a.jar", "jar")
                NetworkClient().use { network ->
                    val service = service(store, network)
                    val plan = service.previewMrpackExport(instance.id, metadata, false)
                    for (
                    format in
                    listOf(
                        PackExportFormat.CurseForge,
                        PackExportFormat.MultiMc,
                        PackExportFormat.Mcbbs,
                        PackExportFormat.Backup,
                    )
                    ) {
                        val target = temporary.resolve("cancelled-${format.name}.zip")
                        val job = Job().apply { cancel() }
                        assertFailsWith<CancellationException> {
                            withContext(job) {
                                service.exportMrpack(
                                    plan,
                                    target,
                                    JobContext(store, "export"),
                                    plan.files.map { it.path }.toSet(),
                                    plan.files.map { it.path }.toSet(),
                                    format,
                                    PackExportOptions(includeLocalResources = true),
                                )
                            }
                        }
                        assertFalse(target.exists())
                        assertTrue(temporary.listDirectoryEntries("*.part").isEmpty())
                    }
                }
            }
        }

    @Test
    fun `written archives are re-opened and required entries asserted`() =
        runBlocking {
            fixture { store, instance, _ ->
                NetworkClient().use { network ->
                    val service = service(store, network)
                    val plan = service.previewMrpackExport(instance.id, metadata, false)
                    val layout =
                        buildPackExportLayout(
                            PackExportFormat.CurseForge,
                            plan,
                            PackExportOptions(),
                            emptyList(),
                            null,
                        )
                    assertEquals(listOf("manifest.json", "overrides/"), layout.requiredEntries)
                    val broken = temporary.resolve("broken.zip")
                    ZipOutputStream(broken.outputStream()).use { zip ->
                        zip.putNextEntry(ZipEntry("manifest.json"))
                        zip.write(layout.documents.single().second)
                        zip.closeEntry()
                    }
                    assertFailsWith<IllegalArgumentException> {
                        // 缺少 overrides/ 目录条目：写后校验必须拒绝。
                        validatePackExport(
                            broken,
                            PackExportFormat.CurseForge,
                            layout,
                            plan,
                            PackExportOptions(),
                            emptySet(),
                        )
                    }
                }
            }
        }

    @Test
    fun `validation only asserts the selected entries`() =
        runBlocking {
            fixture { store, instance, root ->
                write(root, "mods/a.jar", "jar")
                write(root, "config/a.toml", "cfg")
                NetworkClient().use { network ->
                    assertValidationScope(instance, root, service(store, network))
                }
            }
        }

    private suspend fun assertValidationScope(
        instance: GameInstance,
        root: Path,
        service: ContentService,
    ) {
        val plan = service.previewMrpackExport(instance.id, metadata, false)
        val onlyMod = plan.files.filter { it.path == "mods/a.jar" }
        val layout =
            buildPackExportLayout(
                PackExportFormat.MultiMc,
                plan,
                PackExportOptions(),
                onlyMod,
                null,
            )
        val archive = temporary.resolve("selected.zip")
        writeSelectedArchive(archive, layout, root)
        // 未勾选的 config/a.toml 不应被要求存在。
        validatePackExport(
            archive,
            PackExportFormat.MultiMc,
            layout,
            plan,
            PackExportOptions(),
            setOf("mods/a.jar"),
        )
        assertUnselectedRejected(archive, layout, plan)
    }

    private fun assertUnselectedRejected(
        archive: Path,
        layout: PackExportLayout,
        plan: MrpackExportPlan,
    ) {
        assertFailsWith<IllegalArgumentException> {
            validatePackExport(
                archive,
                PackExportFormat.MultiMc,
                layout,
                plan,
                PackExportOptions(),
                setOf("mods/a.jar", "config/a.toml"),
            )
        }
    }

    private fun writeSelectedArchive(
        archive: Path,
        layout: PackExportLayout,
        root: Path,
    ) {
        ZipOutputStream(archive.outputStream()).use { zip ->
            for ((name, bytes) in layout.documents) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            for (directory in layout.directories) {
                zip.putNextEntry(ZipEntry(directory))
                zip.closeEntry()
            }
            for ((archivePath, file) in layout.payload) {
                zip.putNextEntry(ZipEntry(archivePath))
                zip.write(root.resolve(file.path).readBytes())
                zip.closeEntry()
            }
        }
    }
}
