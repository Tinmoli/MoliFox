// 负责后台任务调度、进度和取消。

package dev.molifox.core

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class JobContext internal constructor(private val store: LauncherStore, val id: String) {
    private var lastUpdate = 0L
    private val transfers = mutableMapOf<String, Triple<Long, Long, Boolean>>()
    private var transferCount = 0
    private var sampleTime = System.nanoTime()
    private var sampleBytes = 0L
    private var transferSpeed = 0L

    @Synchronized
    fun beginTransfers(count: Int) {
        transfers.clear()
        transferCount = count
        sampleTime = System.nanoTime()
        sampleBytes = 0
        transferSpeed = 0
        publishTransfers()
    }

    /** 读取实际下载回调；首个偏移是已有断点，不计入本次网络速度。 */
    @Synchronized
    fun transfer(key: String, bytes: Long, total: Long, completed: Boolean = false) {
        val previous = transfers[key]
        if (previous != null) sampleBytes += (bytes - previous.first).coerceAtLeast(0)
        transfers[key] = Triple(bytes.coerceAtLeast(0), total.coerceAtLeast(0), completed)
        val elapsed = System.nanoTime() - sampleTime
        if (elapsed >= 250_000_000L || completed) {
            if (elapsed > 0) transferSpeed = (sampleBytes * 1_000_000_000.0 / elapsed).toLong()
            sampleBytes = 0
            sampleTime = System.nanoTime()
            publishTransfers()
        }
    }

    private fun publishTransfers() {
        val completed = transfers.values.count { it.third }
        store.updateState { state ->
            state.copy(
                jobs =
                    state.jobs.map { job ->
                        if (job.id != id) job
                        else
                            job.copy(
                                downloadedBytes = transfers.values.sumOf { it.first },
                                totalBytes = transfers.values.sumOf { it.second },
                                completedFiles = completed,
                                totalFiles = transferCount,
                                bytesPerSecond =
                                    if (completed == transferCount) 0 else transferSpeed,
                            )
                    }
            )
        }
    }

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
                                bytesPerSecond = 0,
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
