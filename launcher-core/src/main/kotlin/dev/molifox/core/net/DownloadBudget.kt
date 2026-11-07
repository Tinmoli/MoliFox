// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责按内存预算对齐并发下载数与文件大小，并给出可记日志的有效值。

package dev.molifox.core.net

import dev.molifox.core.MAX_DOWNLOAD_THREADS
import dev.molifox.core.i18n.I18n
import dev.molifox.core.megabytesText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/** 单任务传输缓冲预算；流式下载不把整个文件大小当作内存占用。 */
const val DOWNLOAD_BUDGET_BYTES = 256L * 1024 * 1024

/** 预算换算后的有效并发：[note] 只在真的被压低时给出，供任务开始时记一行日志。 */
data class DownloadConcurrency(
    val files: Int,
    val note: String?,
) {
    val clamped: Boolean get() = note != null
}

/**
 * 把「并发下载数 × 平均文件大小」压到 [budgetBytes] 以内；至少保留 1 路。
 * 例：请求 8 路、文件 64MB、预算 256MB → 4 路。
 */
fun downloadConcurrency(
    requested: Int,
    averageFileBytes: Long,
    budgetBytes: Long = DOWNLOAD_BUDGET_BYTES,
): DownloadConcurrency {
    val files = requested.coerceIn(1, MAX_DOWNLOAD_THREADS)
    if (averageFileBytes <= 0L || budgetBytes <= 0L) return DownloadConcurrency(files, null)
    val fits = (budgetBytes / averageFileBytes).toInt().coerceAtLeast(1)
    if (fits >= files) return DownloadConcurrency(files, null)
    return DownloadConcurrency(
        fits,
        I18n.t(
            "networkA.budget.clamped",
            files,
            fits,
            megabytesText(averageFileBytes),
            megabytesText(budgetBytes),
        ),
    )
}

/**
 * 传输闸门：调用方按实际传输缓冲估算占用，多许可取得与取消均受保护。
 */
internal class TransferBudget(
    val units: Int,
    private val budgetBytes: Long,
    val sourceConcurrency: Int = units,
    val segmentLimit: Int = 1,
) {
    private val semaphore = Semaphore(units)
    private val allocation = Mutex()
    private val reported = AtomicBoolean(false)

    /** 该文件占用的许可数；单位大小 = 预算 / 并发数。 */
    fun permitsFor(fileBytes: Long): Int {
        if (fileBytes <= 0L || budgetBytes <= 0L) return 1
        val unit = maxOf(budgetBytes / units, 1L)
        return ((fileBytes + unit - 1) / unit).toInt().coerceIn(1, units)
    }

    /** 首个被压低并发的大文件触发一次回调，避免每个文件都刷日志。 */
    suspend fun <T> run(
        fileBytes: Long,
        onClamped: (DownloadConcurrency) -> Unit,
        block: suspend () -> T,
    ): T {
        val permits = permitsFor(fileBytes)
        if (permits > 1 && reported.compareAndSet(false, true)) {
            onClamped(downloadConcurrency(units, fileBytes, budgetBytes))
        }
        var acquired = 0
        try {
            // 一次只让一个申请者取得多份许可，避免彼此各持部分许可后互等。
            allocation.withLock {
                repeat(permits) {
                    semaphore.acquire()
                    acquired++
                }
            }
            return block()
        } finally {
            repeat(acquired) { semaphore.release() }
        }
    }
}
