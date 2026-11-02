// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责后台任务调度、进度和取消。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
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
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/** 进度节流间隔（毫秒）：约 10 次/秒。 */
private const val PROGRESS_THROTTLE_MS = 100

class JobContext
    internal constructor(
        internal val store: LauncherStore,
        val id: String,
        internal val bindResources: ((Set<String>) -> Unit)? = null,
    ) {
        private val cancellationActions = ConcurrentHashMap<Any, suspend () -> Unit>()
        private val completionActions = ConcurrentHashMap<Any, () -> Unit>()
        internal val outputs = TaskOutputCleanup(this)
        internal var references: Pair<List<GameInstance>, TaskFileReferences>? = null

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
                if (cancelled) cleaned = cleanupCancelledTask(store, cancellationActions, outputs)
            } finally {
                completionActions.values.forEach { it() }
            }
            return cleaned
        }

        private var lastUpdate = 0L
        private val transfers = mutableMapOf<String, Triple<Long, Long, Boolean>>()
        private val observedTransfers = mutableSetOf<String>()
        private val transferRate = TransferRate()
        private var hasNetworkMeasurement = false
        private var transferCount = 0
        private var sampleTime = System.nanoTime()
        private var transferSpeed = 0L

        @Synchronized
        fun beginTransfers(count: Int) {
            transfers.clear()
            observedTransfers.clear()
            transferRate.reset()
            hasNetworkMeasurement = false
            transferCount = count
            sampleTime = System.nanoTime()
            transferSpeed = 0
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
            val measuredBytes =
                if (!observedTransfers.add(key) && previous != null && !hasNetworkMeasurement) {
                    (bytes - previous.first).coerceAtLeast(0)
                } else {
                    0L
                }
            transferSpeed = transferRate.record(measuredBytes)
            transfers[key] = Triple(bytes.coerceAtLeast(0), total.coerceAtLeast(0), completed)
            val elapsed = System.nanoTime() - sampleTime
            if (elapsed >= TRANSFER_SAMPLE_INTERVAL_NANOS || completed) {
                sampleTime = System.nanoTime()
                publishTransfers()
            }
        }

        /** 计划大小用于展示；网络首回调仍需单独建立断点基线。 */
        @Synchronized
        internal fun planTransfer(
            key: String,
            total: Long,
        ) {
            transfers[key] = Triple(0L, total.coerceAtLeast(0), false)
            observedTransfers.remove(key)
        }

        /** 只接收本次从网络读取的字节，缓存、断点和校验读盘不计入。 */
        @Synchronized
        internal fun networkBytes(bytes: Long) {
            hasNetworkMeasurement = true
            transferSpeed = transferRate.record(bytes)
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
            if (now - lastUpdate < PROGRESS_THROTTLE_MS && value < 1f) return
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

/** 逐个执行取消清理：单个清理失败也继续，返回是否全部成功。 */
private suspend fun cleanupCancelledTask(
    store: LauncherStore,
    cancellationActions: Map<Any, suspend () -> Unit>,
    outputs: TaskOutputCleanup,
): Boolean {
    var cleaned = true
    for (cleanup in cancellationActions.values) {
        if (!runCancellationAction(store, cleanup)) cleaned = false
    }
    if (!cleanOutputs(store, outputs)) cleaned = false
    return cleaned
}

private suspend fun runCancellationAction(
    store: LauncherStore,
    cleanup: suspend () -> Unit,
): Boolean =
    runCatching {
        cleanup()
        true
    }.getOrElse { error ->
        // cleanup 由各任务自行注册，失败类型无法枚举；这里必须兜底并继续清理其余产物。
        store.log(I18n.t("miscA1.jobQueue.cancelCleanupFileFailed", error.message), true)
        false
    }

private suspend fun cleanOutputs(
    store: LauncherStore,
    outputs: TaskOutputCleanup,
): Boolean =
    try {
        outputs.cleanFiles()
        outputs.cleanEmptyDirectories()
        true
    } catch (error: IOException) {
        store.log(I18n.t("miscA1.jobQueue.cancelCleanupOutputsFailed", error.message), true)
        false
    } catch (error: IllegalStateException) {
        // 引用关系在清理时可能发现实例/游戏目录已被移除（error(...)），同样只记录并继续。
        store.log(I18n.t("miscA1.jobQueue.cancelCleanupOutputsFailed", error.message), true)
        false
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
        kind: LauncherTaskKind = LauncherTaskKind.Generic,
        prepare: (JobContext) -> Unit = {},
        action: suspend JobContext.() -> Unit,
    ): String {
        val id = UUID.randomUUID().toString()
        val reserved = resources.toSet()
        gate.register(id, reserved)
        store.updateState {
            it.copy(jobs = listOf(DownloadJob(id, title, kind = kind, resources = reserved)) + it.jobs)
        }
        val context =
            JobContext(store, id) { updated ->
                gate.rebind(id, updated)
                store.updateState {
                    it.copy(
                        jobs =
                            it.jobs.map { job ->
                                if (job.id == id) job.copy(resources = updated) else job
                            },
                    )
                }
            }
        prepareJob(id, context, prepare)
        val worker =
            // Enter cancellation cleanup even when cancelled before the dispatcher starts the body.
            scope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
                runWorker(id, title, context, action)
            }
        workers[id] = worker
        worker.invokeOnCompletion { whenWorkerFinished(id, worker) }
        worker.start()
        return id
    }

    /** prepare 由调用方提供；失败时按原顺序回滚已登记的任务与资源，再原样抛出。 */
    private fun prepareJob(
        id: String,
        context: JobContext,
        prepare: (JobContext) -> Unit,
    ) {
        runCatching {
            prepare(context)
        }.onFailure { error ->
            // 初始化逻辑来自调用方，异常类型不可枚举；这里只负责回滚与继续抛出。
            gate.release(id)
            store.updateState { it.copy(jobs = it.jobs.filterNot { job -> job.id == id }) }
            throw error
        }
    }

    private suspend fun runWorker(
        id: String,
        title: String,
        context: JobContext,
        action: suspend JobContext.() -> Unit,
    ) {
        var cancelled = false
        try {
            runCatching {
                gate.acquire(id)
                coroutineContext.ensureActive()
                update(id, JobStatus.Running, I18n.t("miscA1.jobQueue.preparing"))
                store.log(I18n.t("miscA1.jobQueue.started", title), level = LogLevel.Debug)
                withContext(TaskDownloadContext(context) + TaskSourceSelection()) {
                    context.action()
                }
                coroutineContext.ensureActive()
                update(id, JobStatus.Completed, I18n.t("pages.tasks.status.completed"))
                store.log(I18n.t("miscA1.jobQueue.completed", title), level = LogLevel.Debug)
            }.onFailure { error ->
                // 任务体来自调用方，失败类型不可枚举；取消先清理产物，其它失败必须兜底为 Failed，
                // 不能让任务停在 Running。
                cancelled = error is CancellationException
                handleWorkerFailure(id, title, context, error)
            }
        } finally {
            if (!cancelled) context.finish(false)
        }
    }

    /** 取消先清理产物；其它失败兜底为 Failed，不能让任务停在 Running。 */
    private suspend fun handleWorkerFailure(
        id: String,
        title: String,
        context: JobContext,
        error: Throwable,
    ) {
        if (error !is CancellationException) {
            val message = error.message ?: error.javaClass.simpleName
            update(id, JobStatus.Failed, message, message)
            store.log("$title：$message", true)
            store.log(error.stackTraceToString(), true)
            store.notify(message)
            return
        }
        val cleaned =
            withContext(NonCancellable + Dispatchers.IO) { context.finish(true) }
        update(
            id,
            JobStatus.Cancelled,
            if (cleaned) {
                I18n.t("miscA1.jobQueue.cancelledCleaned")
            } else {
                I18n.t("miscA1.jobQueue.cancelledPartial")
            },
        )
        store.log(I18n.t("miscA1.jobQueue.cancelled", title))
        if (!cleaned) store.notify(I18n.t("miscA1.jobQueue.cancelledNotice"))
    }

    private fun whenWorkerFinished(
        id: String,
        worker: Job,
    ) {
        gate.release(id)
        workers.remove(id, worker)
        if (
            worker.isCancelled &&
            store.state.value.jobs.any {
                it.id == id && it.status in setOf(JobStatus.Queued, JobStatus.Running)
            }
        ) {
            update(id, JobStatus.Cancelled, I18n.t("pages.tasks.status.cancelled"))
        }
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
