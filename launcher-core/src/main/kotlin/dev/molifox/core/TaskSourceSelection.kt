// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 保存一次下载任务已选定的源顺序，随任务协程结束释放，不在任务之间复用。

package dev.molifox.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal class TaskSourceSelection : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TaskSourceSelection>

    private data class Group(
        val owner: Any,
        val setting: String,
        val urls: List<String>,
    )

    private val lock = Mutex()
    private val orders = mutableMapOf<Group, List<String>>()

    suspend fun order(
        owner: Any,
        setting: String,
        urls: List<String>,
        measure: suspend () -> List<String>,
    ): List<String> =
        lock.withLock {
            val group = Group(owner, setting, urls.toList())
            orders[group] ?: measure().toList().also { orders[group] = it }
        }
}
