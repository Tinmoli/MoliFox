// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core.net

import dev.molifox.core.MAX_DOWNLOAD_THREADS
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.coroutines.coroutineContext

/** 同一客户端的所有任务/来源共用文件上限；降低设置后先等待已有传输收尾。 */
internal class TransferSlots(private val configuredLimit: () -> Int) {
    private val changes = MutableStateFlow(0L)
    private var active = 0

    suspend fun <T> run(taskLimit: Int, block: suspend () -> T): T {
        while (true) {
            coroutineContext.ensureActive()
            val revision = changes.value
            if (acquire(taskLimit)) break
            changes.first { it != revision }
        }
        try {
            return block()
        } finally {
            release()
        }
    }

    @Synchronized
    private fun acquire(taskLimit: Int): Boolean {
        val limit = minOf(taskLimit, configuredLimit()).coerceIn(1, MAX_DOWNLOAD_THREADS)
        if (active >= limit) return false
        active++
        return true
    }

    @Synchronized
    private fun release() {
        active--
        changes.value++
    }
}
