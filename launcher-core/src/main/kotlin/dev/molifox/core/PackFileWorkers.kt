// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/** 固定数量的工作协程领取文件；失败或取消时等待同批任务结束，之后才允许清理暂存目录。 */
internal suspend fun <T> stagePackFilesConcurrently(
    files: List<T>,
    concurrency: Int,
    context: JobContext,
    action: suspend (T) -> Unit,
) = coroutineScope {
    val next = AtomicInteger()
    repeat(minOf(files.size, concurrency.coerceIn(1, MAX_DOWNLOAD_THREADS))) {
        launch(Dispatchers.IO + TransferTrafficContext(context)) {
            while (true) {
                context.checkCancelled()
                val index = next.getAndIncrement()
                if (index >= files.size) break
                action(files[index])
            }
        }
    }
}
