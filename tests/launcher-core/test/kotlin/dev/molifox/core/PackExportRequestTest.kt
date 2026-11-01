// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.WRITE
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackExportRequestTest : PackExportTestSupport() {
    @Test
    fun `task preparation only includes chosen entries and respects exclusions`(): Unit = runBlocking {
        fixture { store, instance, root ->
            write(root, "config/keep.toml")
            write(root, "config/skip.toml")
            write(root, "mods/unselected.jar")
            write(root, "saves/world/level.dat")
            NetworkClient().use { network ->
                val request =
                    PackExportRequest(
                        instance.id,
                        metadata,
                        temporary.resolve("selected.mrpack"),
                        setOf("config"),
                        setOf("config/skip.toml"),
                        options = PackExportOptions(remoteResources = false),
                    )
                val plan = service(store, network).prepareRequestedExport(request)
                assertEquals(listOf("config/keep.toml"), plan.files.map { it.path })
                assertEquals(setOf("config/keep.toml"), plan.fileStamps.keys)
            }
        }
    }

    @Test
    fun `deleted selected file preserves the existing archive`(): Unit = runBlocking {
        fixture { store, instance, root ->
            val source = write(root, "config/keep.toml")
            NetworkClient().use { network ->
                val service = service(store, network)
                val request = request(instance)
                val plan = service.prepareRequestedExport(request)
                Files.delete(source)
                request.destination.writeText("existing archive")
                val failure =
                    assertFailsWith<IllegalArgumentException> {
                        service.exportMrpack(plan, request.destination, JobContext(store, "export"))
                    }
                assertTrue(failure.message.orEmpty().contains("config/keep.toml"))
                assertPreserved(request)
            }
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a Windows locked selected file fails by name and preserves the existing archive`(): Unit = runBlocking {
        fixture { store, instance, root ->
            val source = write(root, "config/keep.toml", "locked configuration")
            NetworkClient().use { network ->
                val service = service(store, network)
                val request = request(instance)
                val plan = service.prepareRequestedExport(request)
                request.destination.writeText("existing archive")
                FileChannel.open(source, WRITE).use { channel ->
                    channel.lock().use {
                        val failure =
                            assertFailsWith<IOException> {
                                service.exportMrpack(plan, request.destination, JobContext(store, "export"))
                            }
                        assertTrue(failure.message.orEmpty().contains("keep.toml"))
                    }
                }
                assertPreserved(request)
            }
        }
    }

    @Test
    fun `large local file reports progress before the file is complete`(): Unit = runBlocking {
        fixture { store, instance, root ->
            write(root, "config/keep.toml").writeBytes(ByteArray(2 * 1024 * 1024) { it.toByte() })
            NetworkClient().use { network ->
                val service = service(store, network)
                val request = request(instance)
                val plan = service.prepareRequestedExport(request)
                val values = mutableListOf<Float>()
                val context = JobContext(store, "export")
                context.progressObserver = { value, _ -> values += value }
                service.exportMrpack(plan, request.destination, context)
                assertTrue(values.count { it > .05f && it < .9f } > 3)
                assertTrue(values.zipWithNext().all { (before, after) -> after >= before })
                assertEquals(1f, values.last())
            }
        }
    }

    @Test
    fun `an export blocks starting its own instance without blocking an independent instance`() {
        LauncherController(DesktopPlatform(), temporary.resolve("controller")).use { controller ->
            val first =
                GameInstance(
                    name = "First",
                    versionId = "First",
                    gameVersion = "1.20.1",
                    installed = true,
                    isolation = VersionIsolation.Enabled,
                )
            val other = first.copy(id = "other", name = "Other", versionId = "Other", gameVersion = "1.21")
            controller.store.mutate { it.copy(instances = listOf(first, other)) }
            val export = DownloadJob(
                "export",
                "Export",
                kind = LauncherTaskKind.ExportContent,
                status = JobStatus.Running,
                resources = taskResourcesForInstances(controller.store, first.id),
            )
            controller.store.updateState { it.copy(jobs = listOf(export)) }
            assertFailsWith<IllegalStateException> { controller.launch(first.id) }
            assertTrue(controller.isInstanceBusy(first.id))
            assertFalse(controller.isInstanceBusy(other.id))
            controller.requireIdle(other.id)
        }
    }

    private fun request(instance: GameInstance): PackExportRequest = PackExportRequest(
        instance.id,
        metadata,
        temporary.resolve("selected.mrpack"),
        setOf("config"),
        options = PackExportOptions(remoteResources = false),
    )

    private fun assertPreserved(request: PackExportRequest) {
        assertEquals("existing archive", request.destination.readText())
        assertTrue(temporary.listDirectoryEntries("*.part").isEmpty())
    }
}
