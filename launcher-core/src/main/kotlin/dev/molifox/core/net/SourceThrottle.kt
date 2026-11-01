// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责按源限制并发：与 HMCL 一致，只保留一个按可用核数换算的上限，不再给镜像额外降速。

package dev.molifox.core.net

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * 源闸门：每个源一个独立闸门，上限由调用方按设置项「下载线程数」给出（1..128）。
 * 不再对镜像强制串行间隔——403/429 交给失败重试与源健康度处理。
 */
internal object SourceThrottle {
    /** 单源最大并发，与设置项「下载线程数」的上限保持一致。 */
    const val MAX_CONCURRENCY = 128

    private val gates = ConcurrentHashMap<String, Semaphore>()

    private fun gateFor(
        source: DownloadSource,
        mirrorConcurrency: Int,
    ): Semaphore {
        val limit = mirrorConcurrency.coerceIn(1, MAX_CONCURRENCY)
        return gates.computeIfAbsent("${source.key}:$limit") { Semaphore(limit) }
    }

    suspend fun <T> run(
        source: DownloadSource,
        mirrorConcurrency: Int = MAX_CONCURRENCY,
        block: suspend () -> T,
    ): T = gateFor(source, mirrorConcurrency).withPermit { block() }

    internal fun resetForTests() {
        gates.clear()
    }
}
