// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val RATE_TEST_CHUNK_BYTES = 1024L
private const val RATE_TEST_STEADY_SPEED = RATE_TEST_CHUNK_BYTES * 4
private const val RATE_TEST_IDLE_NANOS = 3_000_000_000L
private const val RATE_TEST_WAIT_MILLIS = 300L

class TransferRateTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `rapid completion notifications cannot shorten the sampling interval`() {
        var now = 0L
        val rate = TransferRate { now }
        assertEquals(0L, rate.record(RATE_TEST_CHUNK_BYTES))
        now = TRANSFER_SAMPLE_INTERVAL_NANOS
        assertEquals(RATE_TEST_STEADY_SPEED, rate.record(0))
        now++
        assertEquals(RATE_TEST_STEADY_SPEED, rate.record(0))
        now++
        assertEquals(RATE_TEST_STEADY_SPEED, rate.record(RATE_TEST_CHUNK_BYTES))
        now = TRANSFER_SAMPLE_INTERVAL_NANOS * 2
        assertEquals(RATE_TEST_STEADY_SPEED, rate.record(0))
    }

    @Test
    fun `recent rates are time weighted and expire after inactivity`() {
        var now = 0L
        val rate = TransferRate { now }
        rate.record(RATE_TEST_CHUNK_BYTES)
        now = TRANSFER_SAMPLE_INTERVAL_NANOS
        rate.record(0)
        now *= 2
        val slower = rate.record(0)
        assertTrue(slower in 1 until RATE_TEST_STEADY_SPEED)
        now += RATE_TEST_IDLE_NANOS
        assertEquals(0L, rate.record(0))
        rate.reset()
        assertEquals(0L, rate.record(0))
    }

    @Test
    fun `planned cached files and resumed bytes are not network speed`() {
        val store = LauncherStore(DesktopPlatform(), temporary)
        store.updateState { it.copy(jobs = listOf(DownloadJob("rate", "fixture"))) }
        val context = JobContext(store, "rate")
        context.beginTransfers(2)
        context.planTransfer("cached", RATE_TEST_CHUNK_BYTES)
        Thread.sleep(RATE_TEST_WAIT_MILLIS)
        context.transfer("cached", RATE_TEST_CHUNK_BYTES, RATE_TEST_CHUNK_BYTES, completed = true)
        assertEquals(0L, store.state.value.jobs.single().bytesPerSecond)
        context.planTransfer("resumed", RATE_TEST_CHUNK_BYTES * 2)
        context.transfer("resumed", RATE_TEST_CHUNK_BYTES, RATE_TEST_CHUNK_BYTES * 2)
        Thread.sleep(RATE_TEST_WAIT_MILLIS)
        context.transfer("resumed", RATE_TEST_CHUNK_BYTES, RATE_TEST_CHUNK_BYTES * 2)
        assertEquals(0L, store.state.value.jobs.single().bytesPerSecond)
        assertEquals(1, store.state.value.jobs.single().completedFiles)
    }
}
