// Scaffolding 客户端请求/响应队列：保证响应按请求顺序交给等待者。
package dev.molifox.multiplayer.scaffolding

import dev.molifox.multiplayer.protocol.ScaffoldingResponse
import kotlinx.coroutines.CompletableDeferred

internal class ScaffoldingPendingResponses {
    private val lock = Any()
    private val queue = ArrayDeque<CompletableDeferred<ScaffoldingResponse>>()

    fun add(waiter: CompletableDeferred<ScaffoldingResponse>) {
        synchronized(lock) { queue.addLast(waiter) }
    }

    fun remove(waiter: CompletableDeferred<ScaffoldingResponse>) {
        synchronized(lock) { queue.remove(waiter) }
    }

    fun completeNext(response: ScaffoldingResponse): Boolean {
        val waiter = synchronized(lock) { queue.removeFirstOrNull() } ?: return false
        waiter.complete(response)
        return true
    }

    fun failAll(cause: Throwable) {
        val waiters =
            synchronized(lock) {
                val snapshot = queue.toList()
                queue.clear()
                snapshot
            }
        waiters.forEach { waiter -> waiter.completeExceptionally(cause) }
    }
}
