// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 把资源包/光影更新任务的状态变化翻译成页签可执行的动作，保持为可测试的纯逻辑。

package dev.molifox.desktop

import dev.molifox.core.DownloadJob
import dev.molifox.core.JobStatus
import dev.molifox.core.PackUpdateItem
import dev.molifox.core.PackUpdateReport
import dev.molifox.core.domain.packs.completePackUpdateReport

/** 行内更新任务结束后的页签动作：更新报告、提示文案或失败原因。 */
internal class PackUpdateChange(
    val report: PackUpdateReport? = null,
    val error: String? = null,
    val notice: String? = null,
)

/** 一键更新任务结束后的页签动作；[recheck] 表示需要重新检查以同步报告。 */
internal class PackBatchChange(
    val report: PackUpdateReport?,
    val error: String?,
    val notice: String?,
    val recheck: Boolean,
)

/** 行内更新：成功写回报告，失败或取消给出原因；任务未结束时返回 null。 */
internal fun packUpdateJobChange(
    report: PackUpdateReport?,
    pending: Pair<String, PackUpdateItem>,
    job: DownloadJob,
): PackUpdateChange? =
    when (job.status) {
        JobStatus.Completed ->
            PackUpdateChange(
                report = completePackUpdateReport(report, pending.second),
                notice = tr("pages.updateBatch.updated", pending.second.filename),
            )
        JobStatus.Failed, JobStatus.Cancelled ->
            PackUpdateChange(error = updateOutcome(job))
        else -> null
    }

/** 一键更新：全部成功时逐项写回报告；有失败或取消时要求重新检查，避免误标为最新。 */
internal fun packBatchJobChange(
    report: PackUpdateReport?,
    pending: Pair<String, List<PackUpdateItem>>,
    job: DownloadJob,
): PackBatchChange? =
    when (job.status) {
        JobStatus.Completed ->
            PackBatchChange(
                report =
                    pending.second.fold(report) { current, item ->
                        completePackUpdateReport(current, item)
                    },
                error = null,
                notice = updateOutcome(job),
                recheck = false,
            )
        JobStatus.Failed, JobStatus.Cancelled ->
            PackBatchChange(
                report = report,
                error = updateOutcome(job),
                notice = null,
                recheck = true,
            )
        else -> null
    }

/** 一键更新按钮是否可点：没有候选、任务占用或正在检查时都禁用。 */
internal fun canStartOneClick(
    candidates: Int,
    busy: Boolean,
    checking: Boolean,
    applying: Boolean,
    batching: Boolean,
): Boolean = candidates > 0 && !busy && !checking && !applying && !batching

private fun updateOutcome(job: DownloadJob): String =
    job.error
        ?: if (job.status == JobStatus.Cancelled) {
            tr("pages.updateBatch.cancelledShort")
        } else {
            tr("pages.updateBatch.failed")
        }
