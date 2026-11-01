// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 一键更新的候选筛选、按钮文案与结果提示，保持为可单独测试的纯逻辑。

package dev.molifox.desktop

import dev.molifox.core.DownloadJob
import dev.molifox.core.JobStatus
import dev.molifox.core.ModUpdateItem
import dev.molifox.core.ModUpdateReport
import dev.molifox.core.ModUpdateStatus
import dev.molifox.core.PackFolder
import dev.molifox.core.PackUpdateItem
import dev.molifox.core.PackUpdateReport
import dev.molifox.core.PackUpdateStatus

/** 当前页签可自动更新的资源包/光影：可能匹配项留给行内确认，不参与一键更新。 */
internal fun packUpdateCandidates(
    report: PackUpdateReport?,
    folder: PackFolder,
): List<PackUpdateItem> =
    report?.items(folder).orEmpty().filter {
        it.status == PackUpdateStatus.Updatable && it.applicable && !it.possibleMatch
    }

/** 模组页签的一键更新候选，规则与资源包一致。 */
internal fun modUpdateCandidates(report: ModUpdateReport?): List<ModUpdateItem> =
    report?.items.orEmpty().filter {
        it.status == ModUpdateStatus.Updatable && it.applicable && !it.possibleMatch
    }

/** 按钮文案：有候选时带数量，便于点击前确认范围。 */
internal fun oneClickUpdateLabel(candidates: Int): String =
    if (candidates > 0) {
        tr("pages.updateBatch.oneClick.count", candidates)
    } else {
        tr("pages.updateBatch.oneClick")
    }

/** 一键更新结束提示；任务仍在排队或运行时返回 null，由进度条负责表达。 */
internal fun oneClickUpdateOutcome(
    job: DownloadJob,
    applied: Int,
): String? =
    when (job.status) {
        JobStatus.Completed -> tr("pages.updateBatch.applied", applied)
        JobStatus.Failed -> job.error ?: tr("pages.updateBatch.failed")
        JobStatus.Cancelled -> tr("pages.updateBatch.cancelled")
        JobStatus.Queued, JobStatus.Running -> null
    }
