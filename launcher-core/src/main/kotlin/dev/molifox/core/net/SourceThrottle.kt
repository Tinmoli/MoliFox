// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责按来源和当前设置限制文件并发。

package dev.molifox.core.net

import dev.molifox.core.MAX_DOWNLOAD_THREADS
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * 源闸门：每个源一个独立闸门，上限由调用方按设置项「下载线程数」给出。
 * 不再对镜像强制串行间隔——403/429 交给失败重试与源健康度处理。
 */
internal object SourceThrottle {
    private val gates = ConcurrentHashMap<String, Semaphore>()

    private fun gateFor(
        source: DownloadSource,
        mirrorConcurrency: Int,
    ): Semaphore {
        val limit = mirrorConcurrency.coerceIn(1, MAX_DOWNLOAD_THREADS)
        return gates.computeIfAbsent("${source.key}:$limit") { Semaphore(limit) }
    }

    suspend fun <T> run(
        source: DownloadSource,
        mirrorConcurrency: Int = MAX_DOWNLOAD_THREADS,
        block: suspend () -> T,
    ): T = gateFor(source, mirrorConcurrency).withPermit { block() }

    internal fun resetForTests() {
        gates.clear()
    }
}
