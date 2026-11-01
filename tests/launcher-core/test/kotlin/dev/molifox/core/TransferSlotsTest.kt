// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core

import dev.molifox.core.net.TransferSlots
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.fail

private const val SLOT_HOLD_MILLIS = 10L
private const val SLOT_TEST_TIMEOUT_MILLIS = 3000L

class TransferSlotsTest {
    @Test
    fun `all task owners share the configured active file limit`() = runBlocking {
        val slots = TransferSlots { 2 }
        val active = AtomicInteger()
        val peak = AtomicInteger()
        coroutineScope {
            repeat(DEFAULT_DOWNLOAD_THREADS) {
                launch {
                    slots.run(DEFAULT_DOWNLOAD_THREADS) {
                        val current = active.incrementAndGet()
                        peak.accumulateAndGet(current, ::maxOf)
                        delay(SLOT_HOLD_MILLIS)
                        active.decrementAndGet()
                    }
                }
            }
        }
        assertEquals(2, peak.get())
    }

    @Test
    fun `cancelled waiters cannot consume an active slot`() = runBlocking {
        val slots = TransferSlots { 1 }
        val release = CompletableDeferred<Unit>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) { slots.run(1) { release.await() } }
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) { slots.run(1) { fail("Still occupied") } }
        waiting.cancelAndJoin()
        release.complete(Unit)
        first.join()
        withTimeout(SLOT_TEST_TIMEOUT_MILLIS) { slots.run(1) {} }
    }
}
