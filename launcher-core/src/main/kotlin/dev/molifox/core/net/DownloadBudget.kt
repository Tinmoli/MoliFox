// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责按内存预算对齐并发下载数与文件大小，并给出可记日志的有效值。

package dev.molifox.core.net

import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Semaphore

/** 单任务下载预算：并发数 × 平均文件大小不超过该值。 */
const val DOWNLOAD_BUDGET_BYTES = 256L * 1024 * 1024

/** 预算换算后的有效并发：[note] 只在真的被压低时给出，供任务开始时记一行日志。 */
data class DownloadConcurrency(val files: Int, val note: String?) {
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
    val files = requested.coerceIn(1, 32)
    if (averageFileBytes <= 0L || budgetBytes <= 0L) return DownloadConcurrency(files, null)
    val fits = (budgetBytes / averageFileBytes).toInt().coerceAtLeast(1)
    if (fits >= files) return DownloadConcurrency(files, null)
    return DownloadConcurrency(
        fits,
        "并发下载数 $files → $fits（文件 ${megabytes(averageFileBytes)}，内存预算 ${megabytes(budgetBytes)}）",
    )
}

private fun megabytes(bytes: Long): String =
    String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)

/**
 * 传输闸门：总许可 = 请求的并发数，每个文件按大小占用多份许可，因此
 * 「在途文件数 × 文件大小」被压在预算内。小文件各占一份，等于原来的并发上限。
 */
internal class TransferBudget(val units: Int, private val budgetBytes: Long) {
    private val semaphore = Semaphore(units)
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
        if (permits > 1 && reported.compareAndSet(false, true))
            onClamped(downloadConcurrency(units, fileBytes, budgetBytes))
        repeat(permits) { semaphore.acquire() }
        try {
            return block()
        } finally {
            repeat(permits) { semaphore.release() }
        }
    }
}