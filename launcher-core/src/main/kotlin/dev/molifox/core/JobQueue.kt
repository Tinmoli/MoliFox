// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责后台任务调度、进度和取消。

package dev.molifox.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

class JobContext
    internal constructor(
        private val store: LauncherStore,
        val id: String,
        private val bindResources: ((Set<String>) -> Unit)? = null,
    ) {
        private val cancellationActions = ConcurrentHashMap<Any, suspend () -> Unit>()
        private val completionActions = ConcurrentHashMap<Any, () -> Unit>()
        private val outputs = TaskOutputCleanup(this)
        private var references: Pair<List<GameInstance>, TaskFileReferences>? = null

        internal fun beforeDirectoryCreation(path: java.nio.file.Path) =
            outputs.beforeDirectoryCreation(path)

        internal fun writeOutput(
            path: java.nio.file.Path,
            action: () -> Unit,
        ) = outputs.write(path, action)

        internal fun trackOutput(path: java.nio.file.Path) = outputs.track(path)

        internal fun onFinished(
            key: Any,
            cancel: suspend () -> Unit,
            complete: () -> Unit,
        ) {
            cancellationActions.putIfAbsent(key, cancel)
            completionActions.putIfAbsent(key, complete)
        }

        internal suspend fun finish(cancelled: Boolean): Boolean {
            var cleaned = true
            try {
                if (cancelled) {
                    for (cleanup in cancellationActions.values) {
                        try {
                            cleanup()
                        } catch (error: Exception) {
                            cleaned = false
                            store.log("取消任务时清理文件失败：${error.message}", true)
                        }
                    }
                    try {
                        outputs.cleanFiles()
                        outputs.cleanEmptyDirectories()
                    } catch (error: Exception) {
                        cleaned = false
                        store.log("取消任务时清理产物失败：${error.message}", true)
                    }
                }
            } finally {
                completionActions.values.forEach { it() }
            }
            return cleaned
        }

        internal fun canRemoveDownload(path: java.nio.file.Path): Boolean {
            val resources =
                store.state.value.jobs
                    .find { it.id == id }
                    ?.resources
                    .orEmpty()
            val ownedIds =
                resources
                    .filter { it.startsWith("instance:") }
                    .map { it.removePrefix("instance:") }
                    .toSet()
            val normalized = path.toAbsolutePath().normalize()
            val instances = store.data.instances
            if (references?.first != instances) {
                references = instances to TaskFileReferences(store, ownedIds)
            }
            return !requireNotNull(references).second.protects(normalized) &&
                !normalized.startsWith(
                    store.dataDirectory
                        .resolve("runtimes")
                        .toAbsolutePath()
                        .normalize(),
                )
        }

        internal fun bindInstance(instanceId: String) {
            bindResources?.invoke(taskResourcesForInstances(store, instanceId))
        }

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

        /** 阶段推进时追加预计文件数（不清空已统计的传输），任务中心的「剩余文件」才准。 */
        @Synchronized
        internal fun expectFiles(count: Int) {
            if (count <= 0) return
            transferCount += count
            publishTransfers()
        }

        /** 读取实际下载回调；首个偏移是已有断点，不计入本次网络速度。 */
        @Synchronized
        fun transfer(
            key: String,
            bytes: Long,
            total: Long,
            completed: Boolean = false,
        ) {
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
                            if (job.id != id) {
                                job
                            } else {
                                job.copy(
                                    downloadedBytes = transfers.values.sumOf { it.first },
                                    totalBytes = transfers.values.sumOf { it.second },
                                    completedFiles = completed,
                                    totalFiles = transferCount,
                                    bytesPerSecond =
                                        if (completed == transferCount) 0 else transferSpeed,
                                )
                            }
                        },
                )
            }
        }

        suspend fun checkCancelled() {
            coroutineContext.ensureActive()
        }

        /** 测试与诊断用的观察点：进度模型每次提交都回调（早于节流，便于验证单调性）。 */
        internal var progressObserver: ((Float, String) -> Unit)? = null

        fun progress(
            value: Float,
            detail: String,
        ) {
            val clamped = value.coerceIn(0f, 1f)
            progressObserver?.invoke(clamped, detail)
            val now = System.currentTimeMillis()
            // 约 10 次/秒的节流；完成（1.0）永远立即上报。
            if (now - lastUpdate < 100 && value < 1f) return
            lastUpdate = now
            publish(clamped, detail)
        }

        /** 阶段切换与最终完成：不参与节流，避免长任务收尾时进度条停在旧值。 */
        internal fun progressImmediate(
            value: Float,
            detail: String,
        ) {
            val clamped = value.coerceIn(0f, 1f)
            progressObserver?.invoke(clamped, detail)
            lastUpdate = System.currentTimeMillis()
            publish(clamped, detail)
        }

        private fun publish(
            value: Float,
            detail: String,
        ) {
            store.updateState { state ->
                state.copy(
                    jobs =
                        state.jobs.map {
                            if (it.id == id) it.copy(progress = value, detail = detail) else it
                        },
                )
            }
        }
    }

class JobQueue(
    private val store: LauncherStore,
    private val scope: CoroutineScope,
) {
    private val gate = TaskResourceGate()
    private val workers = ConcurrentHashMap<String, Job>()
    val busy: Boolean
        get() =
            store.state.value.jobs.any {
                it.status == JobStatus.Queued || it.status == JobStatus.Running
            }

    val globallyBusy: Boolean
        get() = conflicts(setOf(GLOBAL_TASK_RESOURCE), globalOnly = true)

    fun conflicts(
        resources: Set<String>,
        globalOnly: Boolean = false,
    ): Boolean =
        store.state.value.jobs.any {
            it.status in setOf(JobStatus.Queued, JobStatus.Running) &&
                (
                    if (globalOnly) {
                        GLOBAL_TASK_RESOURCE in it.resources
                    } else {
                        taskResourcesConflict(resources, it.resources)
                    }
                )
        }

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    fun enqueue(
        title: String,
        resources: Set<String> = setOf(GLOBAL_TASK_RESOURCE),
        prepare: (JobContext) -> Unit = {},
        action: suspend JobContext.() -> Unit,
    ): String {
        val id = UUID.randomUUID().toString()
        val reserved = resources.toSet()
        gate.register(id, reserved)
        store.updateState {
            it.copy(jobs = listOf(DownloadJob(id, title, resources = reserved)) + it.jobs)
        }
        val context =
            JobContext(store, id) { resources ->
                gate.rebind(id, resources)
                store.updateState {
                    it.copy(
                        jobs =
                            it.jobs.map { job ->
                                if (job.id == id) job.copy(resources = resources) else job
                            },
                    )
                }
            }
        try {
            prepare(context)
        } catch (error: Exception) {
            gate.release(id)
            store.updateState { it.copy(jobs = it.jobs.filterNot { job -> job.id == id }) }
            throw error
        }
        val worker =
            // Enter cancellation cleanup even when cancelled before the dispatcher starts the body.
            scope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
                var cancelled = false
                try {
                    gate.acquire(id)
                    coroutineContext.ensureActive()
                    update(id, JobStatus.Running, "准备中…")
                    store.log("任务开始：$title", level = LogLevel.Debug)
                    withContext(TaskDownloadContext(context) + TaskSourceSelection()) {
                        context.action()
                    }
                    coroutineContext.ensureActive()
                    update(id, JobStatus.Completed, "已完成")
                    store.log("任务完成：$title", level = LogLevel.Debug)
                } catch (_: CancellationException) {
                    cancelled = true
                    val cleaned =
                        withContext(NonCancellable + Dispatchers.IO) { context.finish(true) }
                    update(
                        id,
                        JobStatus.Cancelled,
                        if (cleaned) "已取消，本次下载文件已清理" else "已取消；部分文件无法清理，请查看日志",
                    )
                    store.log("任务已取消：$title")
                    if (!cleaned) store.notify("任务已取消，部分文件未能清理，请查看日志")
                } catch (error: Exception) {
                    val message = error.message ?: error.javaClass.simpleName
                    update(id, JobStatus.Failed, message, message)
                    store.log("$title：$message", true)
                    store.log(error.stackTraceToString(), true)
                    store.notify(message)
                } finally {
                    if (!cancelled) context.finish(false)
                }
            }
        workers[id] = worker
        worker.invokeOnCompletion {
            gate.release(id)
            workers.remove(id, worker)
            if (
                worker.isCancelled &&
                store.state.value.jobs.any {
                    it.id == id && it.status in setOf(JobStatus.Queued, JobStatus.Running)
                }
            ) {
                update(id, JobStatus.Cancelled, "已取消")
            }
        }
        worker.start()
        return id
    }

    private fun update(
        id: String,
        status: JobStatus,
        detail: String,
        error: String? = null,
    ) {
        store.updateState { state ->
            state.copy(
                jobs =
                    state.jobs.map {
                        if (it.id == id) {
                            it.copy(
                                status = status,
                                detail = detail,
                                error = error,
                                progress = if (status == JobStatus.Completed) 1f else it.progress,
                                bytesPerSecond = 0,
                            )
                        } else {
                            it
                        }
                    },
            )
        }
    }

    fun cancel(id: String) {
        workers[id]?.cancel()
    }

    fun clearFinished(ids: Set<String>? = null) {
        store.updateState { state ->
            state.copy(
                jobs =
                    state.jobs.filter {
                        it.status == JobStatus.Queued ||
                            it.status == JobStatus.Running ||
                            (ids != null && it.id !in ids)
                    },
            )
        }
    }

    suspend fun awaitIdle() {
        // 完成回调会并发移除任务；逐项遍历不依赖集合瞬间的 size。
        while (workers.isNotEmpty()) workers.values.map { it }.joinAll()
    }

    fun cancelAll() {
        workers.values.forEach { it.cancel() }
    }
}
