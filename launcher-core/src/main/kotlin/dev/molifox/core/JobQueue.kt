package dev.molifox.core

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class JobContext internal constructor(private val store: LauncherStore, val id: String) {
    private var lastUpdate = 0L

    suspend fun checkCancelled() {
        coroutineContext.ensureActive()
    }

    fun progress(value: Float, detail: String) {
        val now = System.currentTimeMillis()
        if (now - lastUpdate < 80 && value < 1f) return
        lastUpdate = now
        store.updateState { state ->
            state.copy(
                jobs =
                    state.jobs.map {
                        if (it.id == id) it.copy(progress = value.coerceIn(0f, 1f), detail = detail)
                        else it
                    }
            )
        }
    }
}

class JobQueue(private val store: LauncherStore, private val scope: CoroutineScope) {
    private val mutex = Mutex()
    private val workers = ConcurrentHashMap<String, Job>()
    val busy: Boolean
        get() =
            store.state.value.jobs.any {
                it.status == JobStatus.Queued || it.status == JobStatus.Running
            }

    fun enqueue(title: String, action: suspend JobContext.() -> Unit): String {
        val id = UUID.randomUUID().toString()
        store.updateState { it.copy(jobs = listOf(DownloadJob(id, title)) + it.jobs) }
        val worker =
            scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                try {
                    mutex.withLock {
                        coroutineContext.ensureActive()
                        update(id, JobStatus.Running, "准备中…")
                        store.log("任务开始：$title")
                        JobContext(store, id).action()
                        coroutineContext.ensureActive()
                        update(id, JobStatus.Completed, "已完成")
                        store.log("任务完成：$title")
                    }
                } catch (_: CancellationException) {
                    update(id, JobStatus.Cancelled, "已取消")
                    store.log("任务已取消：$title")
                } catch (error: Exception) {
                    val message = error.message ?: error.javaClass.simpleName
                    update(id, JobStatus.Failed, message, message)
                    store.log("$title：$message", true)
                    store.log(error.stackTraceToString(), true)
                    store.notify(message)
                } finally {
                    workers.remove(id)
                }
            }
        workers[id] = worker
        worker.start()
        return id
    }

    private fun update(id: String, status: JobStatus, detail: String, error: String? = null) {
        store.updateState { state ->
            state.copy(
                jobs =
                    state.jobs.map {
                        if (it.id == id)
                            it.copy(
                                status = status,
                                detail = detail,
                                error = error,
                                progress = if (status == JobStatus.Completed) 1f else it.progress,
                            )
                        else it
                    }
            )
        }
    }

    fun cancel(id: String) {
        workers[id]?.cancel()
    }

    fun clearFinished() {
        store.updateState { state ->
            state.copy(
                jobs =
                    state.jobs.filter {
                        it.status == JobStatus.Queued || it.status == JobStatus.Running
                    }
            )
        }
    }

    suspend fun awaitIdle() {
        while (workers.isNotEmpty()) workers.values.toList().joinAll()
    }

    fun cancelAll() {
        workers.values.forEach { it.cancel() }
    }
}
