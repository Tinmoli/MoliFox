// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验证一键更新的候选筛选、按钮文案与结果提示等界面纯逻辑。

package dev.molifox.desktop

import dev.molifox.core.DownloadJob
import dev.molifox.core.JobStatus
import dev.molifox.core.ModMatchSource
import dev.molifox.core.ModUpdateItem
import dev.molifox.core.ModUpdateReport
import dev.molifox.core.ModUpdateStatus
import dev.molifox.core.PackFolder
import dev.molifox.core.PackMatchSource
import dev.molifox.core.PackUpdateCandidate
import dev.molifox.core.PackUpdateItem
import dev.molifox.core.PackUpdateReport
import dev.molifox.core.PackUpdateStatus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateBatchUiTest {
    private val packCandidate =
        PackUpdateCandidate(
            projectId = "fox",
            projectTitle = "Fox Pack",
            versionId = "v-1.1",
            versionNumber = "1.1",
            filename = "FoxPack-1.1.zip",
            downloadUrl = "https://example.invalid/FoxPack-1.1.zip",
            provider = "modrinth",
            gameVersions = listOf("1.20.1"),
            size = 1024,
            sha512 = "a".repeat(128),
            sha1 = "",
        )

    private fun packItem(
        filename: String,
        status: PackUpdateStatus,
        applicable: Boolean = status == PackUpdateStatus.Updatable,
        possibleMatch: Boolean = false,
        folder: PackFolder = PackFolder.ResourcePacks,
    ) = PackUpdateItem(
        filename = filename,
        folder = folder,
        status = status,
        latestVersion = "1.1",
        candidate = if (applicable) packCandidate else null,
        matchSource = PackMatchSource.Fingerprint,
        possibleMatch = possibleMatch,
        applicable = applicable,
    )

    private fun modItem(
        filename: String,
        status: ModUpdateStatus,
        applicable: Boolean = status == ModUpdateStatus.Updatable,
        possibleMatch: Boolean = false,
    ) = ModUpdateItem(
        filename = filename,
        title = filename,
        currentVersion = "1.0",
        enabled = true,
        size = 1024,
        modified = 1_000,
        status = status,
        latestVersion = "1.1",
        matchSource = ModMatchSource.Fingerprint,
        possibleMatch = possibleMatch,
        applicable = applicable,
    )

    @Test
    fun `pack candidates keep only automatic updates in the current folder`() {
        val report =
            PackUpdateReport(
                "instance",
                "1.20.1",
                listOf(
                    packItem("auto.zip", PackUpdateStatus.Updatable),
                    packItem("latest.zip", PackUpdateStatus.Latest, applicable = false),
                    packItem("unknown.zip", PackUpdateStatus.Unknown, applicable = false),
                    packItem("uncertain.zip", PackUpdateStatus.Updatable, possibleMatch = true),
                    packItem(
                        "shader.zip",
                        PackUpdateStatus.Updatable,
                        folder = PackFolder.ShaderPacks,
                    ),
                ),
            )
        val candidates = packUpdateCandidates(report, PackFolder.ResourcePacks)
        assertEquals(listOf("auto.zip"), candidates.map { it.filename })
    }

    @Test
    fun `pack candidates are empty without a report`() {
        assertTrue(packUpdateCandidates(null, PackFolder.ShaderPacks).isEmpty())
    }

    @Test
    fun `mod candidates skip possible matches and non applicable items`() {
        val report =
            ModUpdateReport(
                instanceId = "instance",
                gameVersion = "1.20.1",
                loader = dev.molifox.core.LoaderType.Fabric,
                items =
                    listOf(
                        modItem("auto.jar", ModUpdateStatus.Updatable),
                        modItem("done.jar", ModUpdateStatus.Latest, applicable = false),
                        modItem("named.jar", ModUpdateStatus.Updatable, possibleMatch = true),
                    ),
            )
        assertEquals(listOf("auto.jar"), modUpdateCandidates(report).map { it.filename })
        assertTrue(modUpdateCandidates(null).isEmpty())
    }

    @Test
    fun `one click label shows the candidate count`() {
        assertEquals("一键更新 (3)", oneClickUpdateLabel(3))
        assertEquals("一键更新", oneClickUpdateLabel(0))
    }

    @Test
    fun `one click outcome reports success failure and cancellation`() {
        assertEquals(
            "已更新 2 项",
            oneClickUpdateOutcome(job(JobStatus.Completed), applied = 2),
        )
        assertEquals(
            "更新失败：已更新 1 项，1 项失败：FoxPack.zip：哈希不符",
            oneClickUpdateOutcome(
                job(JobStatus.Failed, error = "更新失败：已更新 1 项，1 项失败：FoxPack.zip：哈希不符"),
                applied = 2,
            ),
        )
        assertTrue(
            oneClickUpdateOutcome(job(JobStatus.Cancelled), applied = 2).orEmpty().contains("已取消"),
        )
    }

    @Test
    fun `one click outcome stays silent while the job is still running`() {
        assertNull(oneClickUpdateOutcome(job(JobStatus.Queued), applied = 2))
        assertNull(oneClickUpdateOutcome(job(JobStatus.Running), applied = 2))
    }

    @Test
    fun `single update success writes the new file name back to the report`() {
        val report =
            PackUpdateReport(
                "instance",
                "1.20.1",
                listOf(packItem("FoxPack-1.0.zip", PackUpdateStatus.Updatable)),
            )
        val change =
            packUpdateJobChange(report, "job" to report.items.single(), job(JobStatus.Completed))
        assertEquals(
            "FoxPack-1.1.zip",
            change
                ?.report
                ?.items
                ?.single()
                ?.filename,
        )
        assertTrue(change?.notice.orEmpty().contains("已更新"))
    }

    @Test
    fun `single update failure keeps the report and shows the error`() {
        val change =
            packUpdateJobChange(
                null,
                "job" to packItem("FoxPack.zip", PackUpdateStatus.Updatable),
                job(JobStatus.Failed, error = "更新文件哈希不符"),
            )
        assertEquals("更新文件哈希不符", change?.error)
        assertNull(change?.report)
    }

    @Test
    fun `batch success completes the applied items`() {
        val item = packItem("FoxPack-1.0.zip", PackUpdateStatus.Updatable)
        val report = PackUpdateReport("instance", "1.20.1", listOf(item))
        val change = packBatchJobChange(report, "job" to listOf(item), job(JobStatus.Completed))
        assertEquals(
            PackUpdateStatus.Latest,
            change
                ?.report
                ?.items
                ?.single()
                ?.status,
        )
        assertEquals(false, change?.recheck)
    }

    @Test
    fun `batch failure asks for a recheck instead of marking items latest`() {
        val item = packItem("FoxPack.zip", PackUpdateStatus.Updatable)
        val report = PackUpdateReport("instance", "1.20.1", listOf(item))
        val change =
            packBatchJobChange(
                report,
                "job" to listOf(item),
                job(JobStatus.Failed, error = "已更新 0 项，1 项失败：FoxPack.zip：网络中断"),
            )
        assertEquals(true, change?.recheck)
        assertEquals(
            PackUpdateStatus.Updatable,
            change
                ?.report
                ?.items
                ?.single()
                ?.status,
        )
        assertTrue(change?.error.orEmpty().contains("1 项失败"))
    }

    @Test
    fun `one click needs candidates and an idle instance`() {
        assertTrue(canStartOneClick(2, false, false, false, false))
        assertFalse(canStartOneClick(0, false, false, false, false))
        assertFalse(canStartOneClick(2, true, false, false, false))
        assertFalse(canStartOneClick(2, false, true, false, false))
        assertFalse(canStartOneClick(2, false, false, true, false))
        assertFalse(canStartOneClick(2, false, false, false, true))
    }

    private fun job(
        status: JobStatus,
        error: String? = null,
    ) = DownloadJob(id = "job", title = "更新", status = status, error = error)
}
