// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责按主机限制下载并发与请求间隔，避免镜像站被高并发打成 403/429。

package dev.molifox.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

private const val THROTTLE_NANOS_PER_MILLISECOND = 1_000_000L

/**
 * 单个主机的闸门：最多 [permits] 个并发请求，且相邻两次开始至少间隔 [spacingMillis]。
 * 许可证覆盖整个文件传输（请求 + 响应体），因此镜像上的实际连接数也被限制住。
 */
internal class HostGate(
    private val permits: Int,
    private val spacingMillis: Long = 0,
) {
    private val semaphore = Semaphore(permits)
    private val spacing = Mutex()
    private var nextStartNanos = 0L

    suspend fun <T> run(block: suspend () -> T): T =
        semaphore.withPermit {
            if (spacingMillis > 0) {
                val waitNanos =
                    spacing.withLock {
                        val now = System.nanoTime()
                        val start = maxOf(now, nextStartNanos)
                        nextStartNanos = start + spacingMillis * 1_000_000L
                        start - now
                    }
                if (waitNanos > 0) delay(waitNanos / THROTTLE_NANOS_PER_MILLISECOND + 1)
            }
            block()
        }
}

/**
 * 全局主机闸门：镜像站（BMCLAPI 一类）默认并发 4、每次开始间隔 100ms；官方站点可用满
 * 文件级并发。计划见 docs/DOWNLOAD_ENGINE_PLAN_2026-10-29.md 第四节。
 */
internal object DownloadThrottle {
    /** 官方站点允许的同时文件数，与文件级默认并发一致。 */
    const val OFFICIAL_LIMIT = 8

    /** 镜像站默认并发；BMCLAPI 高并发会返回 403/429。 */
    const val MIRROR_LIMIT = 4

    /** 镜像站相邻请求的最小间隔。 */
    const val MIRROR_SPACING_MILLIS = 100L

    private val gates = ConcurrentHashMap<String, HostGate>()

    fun isMirror(host: String): Boolean {
        val name = host.lowercase()
        return name == "bangbang93.com" ||
            name.endsWith(".bangbang93.com") ||
            name == "bmclapi.bangbang93.com"
    }

    internal fun gateFor(host: String): HostGate =
        gates.computeIfAbsent(host.lowercase()) { name ->
            if (isMirror(name)) {
                HostGate(MIRROR_LIMIT, MIRROR_SPACING_MILLIS)
            } else {
                HostGate(OFFICIAL_LIMIT, 0L)
            }
        }

    /** 按实际请求地址（可能是改写后的镜像地址）取闸门。 */
    suspend fun <T> run(
        url: String,
        block: suspend () -> T,
    ): T {
        val host =
            runCatching {
                java.net
                    .URI(url)
                    .host
                    .orEmpty()
            }.getOrNull().orEmpty()
        return gateFor(if (host.isBlank()) "unknown" else host).run(block)
    }

    internal fun resetForTests() {
        gates.clear()
    }
}
