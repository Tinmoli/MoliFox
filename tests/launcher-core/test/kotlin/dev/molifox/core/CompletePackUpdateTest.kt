// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验证更新完成后的报告同步：文件名、状态与提示字段都按新版本收敛。

package dev.molifox.core

import dev.molifox.core.domain.packs.completePackUpdateReport
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class CompletePackUpdateTest {
    private fun candidate(filename: String = "FoxPack-1.1.zip") =
        PackUpdateCandidate(
            projectId = "fox",
            projectTitle = "Fox Pack",
            versionId = "v-1.1",
            versionNumber = "1.1",
            filename = filename,
            downloadUrl = "https://example.invalid/$filename",
            provider = "modrinth",
            gameVersions = listOf("1.20.1"),
            size = 1024,
            sha512 = "a".repeat(128),
            sha1 = "",
        )

    private fun item(
        filename: String = "FoxPack-1.0.zip",
        folder: PackFolder = PackFolder.ResourcePacks,
    ) = PackUpdateItem(
        filename = filename,
        folder = folder,
        currentVersion = "1.0",
        status = PackUpdateStatus.Updatable,
        latestVersion = "1.1",
        candidate = candidate(),
        matchSource = PackMatchSource.Fingerprint,
        applicable = true,
        size = 1024,
        modified = 1_000,
        warning = "按名称匹配，可能不是同一项目",
    )

    @Test
    fun `completed item becomes latest and takes the new file name`() {
        val report = PackUpdateReport("instance", "1.20.1", listOf(item()))
        val updated = checkNotNull(completePackUpdateReport(report, item()))
        val only = updated.items.single()
        assertEquals("FoxPack-1.1.zip", only.filename)
        assertEquals("1.1", only.currentVersion)
        assertEquals(PackUpdateStatus.Latest, only.status)
        assertEquals("", only.latestVersion)
        assertNull(only.candidate)
        assertEquals(false, only.applicable)
        assertEquals(false, only.possibleMatch)
        assertEquals("", only.warning)
        assertEquals(0L, only.size)
        assertEquals(0L, only.modified)
    }

    @Test
    fun `items in another folder or with another name are untouched`() {
        val other = item("FoxPack-2.0.zip", PackFolder.ShaderPacks)
        val report = PackUpdateReport("instance", "1.20.1", listOf(item(), other))
        val updated = checkNotNull(completePackUpdateReport(report, item()))
        assertEquals(2, updated.items.size)
        val shader = updated.items.single { it.folder == PackFolder.ShaderPacks }
        assertEquals(PackUpdateStatus.Updatable, shader.status)
        val pack = updated.items.single { it.folder == PackFolder.ResourcePacks }
        assertEquals(PackUpdateStatus.Latest, pack.status)
    }

    @Test
    fun `completed item without a candidate leaves the report alone`() {
        val report = PackUpdateReport("instance", "1.20.1", listOf(item().copy(candidate = null)))
        assertSame(report, completePackUpdateReport(report, item().copy(candidate = null)))
    }

    @Test
    fun `a missing report stays missing`() {
        assertNull(completePackUpdateReport(null, item()))
    }
}
