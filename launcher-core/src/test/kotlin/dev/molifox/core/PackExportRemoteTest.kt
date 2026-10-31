// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验证远程资源开关：开启时写下载清单，关闭时强制把同一份快照写成本地 overrides。

package dev.molifox.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackExportRemoteTest : PackExportTestSupport() {
    @Test
    fun `offered formats keep modrinth and universal zip`() {
        assertEquals(
            listOf(PackExportFormat.Modrinth, PackExportFormat.CurseForge),
            PackExportFormat.offeredForExport,
        )
    }

    @Test
    fun `remote resources toggle changes modrinth archive layout`() =
        runBlocking {
            fixture { store, instance, root ->
                write(root, "mods/remote.jar", "remote-bytes")
                NetworkClient().use { network ->
                    val service = service(store, network)
                    val plan = service.previewMrpackExport(instance.id, metadata, false)
                    val file = plan.files.single()
                    val remoteFile =
                        file.copy(
                            downloadUrl = "https://example.invalid/mods/remote.jar",
                            selectedByDefault = true,
                            requiresPermission = false,
                        )
                    val remotePlan =
                        MrpackExportPlan(
                            instance.id,
                            metadata,
                            listOf(remoteFile),
                            emptyList(),
                            false,
                            plan.root,
                            plan.realRoot,
                            plan.components,
                            instance,
                        )
                    assertRemoteExport(service, store, remotePlan, remoteFile.path)
                    assertLocalExport(service, store, remotePlan, remoteFile.path)
                }
            }
        }

    private suspend fun assertRemoteExport(
        service: ContentService,
        store: LauncherStore,
        plan: MrpackExportPlan,
        path: String,
    ) {
        val target = temporary.resolve("remote.mrpack")
        service.exportMrpack(
            plan,
            target,
            JobContext(store, "export-remote"),
            setOf(path),
            emptySet(),
            PackExportFormat.Modrinth,
            PackExportOptions(),
        )
        ZipFile(target.toFile()).use { zip ->
            assertTrue("modrinth.index.json" in listing(zip))
            assertNull(zip.getEntry("client-overrides/$path"))
            val index = assertNotNull(packJson(zip, "modrinth.index.json"))
            assertEquals(1, index.array("files").size())
        }
    }

    private suspend fun assertLocalExport(
        service: ContentService,
        store: LauncherStore,
        plan: MrpackExportPlan,
        path: String,
    ) {
        val target = temporary.resolve("local.mrpack")
        service.exportMrpack(
            plan,
            target,
            JobContext(store, "export-local"),
            setOf(path),
            setOf(path),
            PackExportFormat.Modrinth,
            PackExportOptions(remoteResources = false, includeLocalResources = true),
        )
        ZipFile(target.toFile()).use { zip ->
            assertTrue("modrinth.index.json" in listing(zip))
            assertNotNull(zip.getEntry("client-overrides/$path"))
            val index = assertNotNull(packJson(zip, "modrinth.index.json"))
            assertEquals(0, index.array("files").size())
        }
    }
}
