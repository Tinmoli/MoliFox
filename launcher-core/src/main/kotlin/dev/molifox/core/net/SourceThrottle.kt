// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责按源限制并发与请求间隔：镜像类默认 4 并发 + 100ms 间隔，官方源用更高的上限。

package dev.molifox.core.net

import java.util.concurrent.ConcurrentHashMap
import dev.molifox.core.HostGate

/**
 * 源闸门：镜像类主机（BMCLAPI、Maven 镜像）默认 4 并发、相邻两次开始至少 100ms；
 * 官方源可用更高的上限。[mirrorConcurrency] 来自设置，改动只对下一个任务生效。
 */
internal object SourceThrottle {
    /** 官方站点允许的同时文件数。 */
    const val OFFICIAL_LIMIT = 8

    /** 镜像站默认并发；BMCLAPI 高并发会返回 403/429。 */
    const val MIRROR_LIMIT = 4

    /** 镜像站相邻请求的最小间隔。 */
    const val MIRROR_SPACING_MILLIS = 100L

    private val gates = ConcurrentHashMap<String, HostGate>()

    internal fun gateFor(source: DownloadSource, mirrorConcurrency: Int): HostGate {
        val limits =
            if (source.mirror)
                mirrorConcurrency.coerceIn(1, 16) to MIRROR_SPACING_MILLIS
            else OFFICIAL_LIMIT to 0L
        return gates.computeIfAbsent("${source.key}:${limits.first}:${limits.second}") {
            HostGate(limits.first, limits.second)
        }
    }

    suspend fun <T> run(
        source: DownloadSource,
        mirrorConcurrency: Int = MIRROR_LIMIT,
        block: suspend () -> T,
    ): T = gateFor(source, mirrorConcurrency).run(block)

    internal fun resetForTests() {
        gates.clear()
    }
}