// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core

internal const val TRANSFER_SAMPLE_INTERVAL_NANOS = 250_000_000L
private const val TRANSFER_RATE_WINDOW_NANOS = 2_000_000_000L
private const val NANOS_PER_SECOND = 1_000_000_000.0

/** 用时间加权的近期字节计算速度，文件完成不会把采样窗口缩到几毫秒。 */
internal class TransferRate(private val clock: () -> Long = System::nanoTime) {
    private class Sample(val end: Long, val elapsed: Long, val bytes: Long)

    private val samples = ArrayDeque<Sample>()
    private var start = clock()
    private var pendingBytes = 0L
    private var speed = 0L

    fun reset() {
        samples.clear()
        start = clock()
        pendingBytes = 0
        speed = 0
    }

    fun record(bytes: Long): Long {
        pendingBytes += bytes.coerceAtLeast(0)
        val now = clock()
        val elapsed = now - start
        if (elapsed < TRANSFER_SAMPLE_INTERVAL_NANOS) return speed
        samples.addLast(Sample(now, elapsed, pendingBytes))
        while (samples.size > 1 && now - samples.first().end >= TRANSFER_RATE_WINDOW_NANOS) {
            samples.removeFirst()
        }
        speed = (samples.sumOf { it.bytes } * NANOS_PER_SECOND / samples.sumOf { it.elapsed }).toLong()
        start = now
        pendingBytes = 0
        return speed
    }
}
