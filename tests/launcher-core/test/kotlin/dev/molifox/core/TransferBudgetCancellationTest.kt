// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core

import dev.molifox.core.net.TransferBudget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.fail

private const val BUDGET_TEST_TIMEOUT_MILLIS = 3000L

class TransferBudgetCancellationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `each task retains its concurrency snapshot when other tasks start`() = runBlocking {
        var limit = DEFAULT_DOWNLOAD_THREADS
        val store = LauncherStore(DesktopPlatform(), temporary)
        NetworkClient(maxConcurrent = { limit }, mirrorConcurrency = { limit }).use { network ->
            val first = TaskDownloadContext(JobContext(store, "first"))
            val second = TaskDownloadContext(JobContext(store, "second"))
            withContext(first) {
                val original = network.actions.transferBudget()
                limit = 2
                val next = withContext(second) { network.actions.transferBudget() }
                assertNotSame(original, next)
                assertSame(original, network.actions.transferBudget())
                assertEquals(DEFAULT_DOWNLOAD_THREADS, original.units)
                assertEquals(DEFAULT_DOWNLOAD_THREADS, original.sourceConcurrency)
                assertEquals(2, next.units)
                assertEquals(2, next.sourceConcurrency)
            }
        }
    }

    @Test
    fun `cancelling a partially acquired budget releases every permit`() = runBlocking {
        val budget = TransferBudget(2, 2)
        val release = CompletableDeferred<Unit>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            budget.run(1, {}) { release.await() }
        }
        // 第一项占 1 份，第二项先取走余下 1 份，再等待第 2 份；取消必须归还已取的部分。
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) {
            budget.run(2, {}) { fail("The second transfer must still be waiting") }
        }
        cancelled.cancelAndJoin()
        release.complete(Unit)
        first.join()
        withTimeout(BUDGET_TEST_TIMEOUT_MILLIS) { budget.run(2, {}) {} }
    }
}
