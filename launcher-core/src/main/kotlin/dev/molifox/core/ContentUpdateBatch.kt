// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 并发执行一批内容更新：单项失败只记录，取消时立即停止并保留已完成的部分。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

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
 * 并发应用更新且互不影响：单项失败记录原因后继续，其余照常执行；并发度与批量下载
 * 共用设置里的「下载线程数」。
 *
 * 取消属于用户主动中止：立即抛出，已完成的更新保留，未执行的项目不再继续。
 */
internal suspend fun applyContentUpdateBatch(
    context: JobContext,
    tasks: List<ContentUpdateTask>,
    concurrency: Int,
): ContentUpdateBatchResult {
    val updated = AtomicInteger()
    val completed = AtomicInteger()
    val failures = Collections.synchronizedList(mutableListOf<String>())
    val permits = Semaphore(concurrency.coerceIn(1, MAX_DOWNLOAD_THREADS))
    coroutineScope {
        tasks
            .map { task ->
                async(Dispatchers.IO) {
                    permits.withPermit {
                        context.checkCancelled()
                        try {
                            task.apply(context)
                            updated.incrementAndGet()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: IOException) {
                            failures += failureEntry(task.label, failure)
                        } catch (failure: IllegalStateException) {
                            failures += failureEntry(task.label, failure)
                        } catch (failure: IllegalArgumentException) {
                            failures += failureEntry(task.label, failure)
                        } finally {
                            context.reportContentUpdate(task.label, completed.incrementAndGet(), tasks.size)
                        }
                    }
                }
            }.awaitAll()
    }
    context.progress(1f, I18n.t("contentA2.contentUpdateBatch.completed"))
    return ContentUpdateBatchResult(updated.get(), failures.toList())
}

private fun JobContext.reportContentUpdate(
    label: String,
    finished: Int,
    total: Int,
) {
    progress(
        finished.toFloat() / total,
        I18n.t("contentA2.contentUpdateBatch.updatingItem", label, finished, total),
    )
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
