// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 顺序执行一批内容更新：单项失败只记录，取消时立即停止并保留已完成的部分。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.CancellationException
import java.io.IOException

private const val FAILURE_MAX_CHARS = 120

/** 批量更新的单项：界面文案与真正的应用动作。 */
internal class ContentUpdateTask(
    val label: String,
    val apply: suspend (JobContext) -> Unit,
)

/** 批量更新结果：成功数量与逐项失败摘要，供任务中心与界面提示复用。 */
internal data class ContentUpdateBatchResult(
    val updated: Int,
    val failures: List<String>,
) {
    val total: Int
        get() = updated + failures.size

    /** 全部成功时只报数量；有失败时逐项列出文件与原因。 */
    fun summary(): String =
        if (failures.isEmpty()) {
            I18n.t("pages.updateBatch.applied", updated)
        } else {
            I18n.t(
                "contentA2.contentUpdateBatch.summaryWithFailures",
                updated,
                failures.size,
                failures.joinToString("；"),
            )
        }
}

/**
 * 逐项应用更新且互不影响：单项失败记录原因后继续，下一项照常执行。
 *
 * 取消属于用户主动中止：立即抛出，已完成的更新保留，未执行的项目不再继续。
 */
internal suspend fun applyContentUpdateBatch(
    context: JobContext,
    tasks: List<ContentUpdateTask>,
): ContentUpdateBatchResult {
    var updated = 0
    val failures = mutableListOf<String>()
    for ((index, task) in tasks.withIndex()) {
        context.checkCancelled()
        context.progress(
            index.toFloat() / tasks.size,
            I18n.t("contentA2.contentUpdateBatch.updatingItem", task.label, index + 1, tasks.size),
        )
        try {
            task.apply(context)
            updated++
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            failures += failureEntry(task.label, failure)
        } catch (failure: IllegalStateException) {
            failures += failureEntry(task.label, failure)
        } catch (failure: IllegalArgumentException) {
            failures += failureEntry(task.label, failure)
        }
    }
    context.progress(1f, I18n.t("contentA2.contentUpdateBatch.completed"))
    return ContentUpdateBatchResult(updated, failures)
}

/** 单项失败摘要：保留用户能看懂的原因，过长的服务端信息截断。 */
private fun failureEntry(
    label: String,
    error: Exception,
): String =
    "$label：" +
        error.message
            .orEmpty()
            .take(FAILURE_MAX_CHARS)
            .ifBlank { I18n.t("pages.instance.mods.updateFailed") }
