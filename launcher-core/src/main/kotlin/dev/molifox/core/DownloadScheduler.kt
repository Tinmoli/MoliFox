// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责按文件有界并发下载、汇总失败，并在取消时清理断点缓存。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlin.io.path.fileSize

/** 默认同时下载的文件数：与设置项「下载线程数」的默认值一致（HMCL 同为 64）。 */
internal const val DEFAULT_DOWNLOAD_THREADS = 64

/** 默认同时下载的文件数；调度器不传参时使用设置默认值。 */
internal const val DEFAULT_FILE_CONCURRENCY = DEFAULT_DOWNLOAD_THREADS

/** 设置项允许的下载线程数上限。 */
internal const val MAX_DOWNLOAD_THREADS = 128

/** 失败汇总里最多逐个列出的文件数，其余折叠成一行数量提示，避免消息无限膨胀。 */
private const val MAX_REPORTED_FAILURES = 12

/** 单个文件的最终失败：文件、实际来源主机与 HTTP 状态码（没有状态码时给原因）。 */
internal data class FileFailure(
    val target: Path,
    val host: String,
    val status: Int?,
    val reason: String,
) {
    fun describe(): String =
        buildList {
            add(target.toString())
            add(
                if (host.isBlank()) {
                    I18n.t("networkA.scheduler.sourceUnknown")
                } else {
                    I18n.t("networkA.scheduler.sourceHost", host)
                },
            )
            add(status?.let { "HTTP $it" } ?: reason)
        }.filter { it.isNotBlank() }
            .joinToString(" · ")
}

/** 一批文件下载结束后一次性报告的失败列表，不因第一个 404 丢掉其余结果。 */
internal class DownloadBatchException(
    val failures: List<FileFailure>,
) : IOException(
        buildString {
            append(I18n.t("networkA.scheduler.batchFailed", failures.size))
            failures.take(MAX_REPORTED_FAILURES).forEach { append('\n').append(it.describe()) }
            if (failures.size > MAX_REPORTED_FAILURES) {
                append(I18n.t("networkA.scheduler.moreFailed", failures.size - MAX_REPORTED_FAILURES))
            }
        },
    )

/**
 * 一批下载的共享状态：去重后的文件列表、下一个要取的下标、失败收集队列。
 * 工作协程都指向同一份状态，因此不会重复下载同一个目标。
 */
private class BatchState(
    val unique: List<DownloadArtifact>,
) {
    val next = AtomicInteger()
    val failures = ConcurrentLinkedQueue<FileFailure>()
}

/**
 * 有界并发的文件下载：固定数量的工作协程从队列里取文件，每个文件内部仍然走
 * [NetworkClient.download] 的分段路径。取消时不再派发新文件，等在传文件中止后
 * 清掉本批可能残留的 `.part` 与分段缓存。
 *
 * 逐文件失败只收集不抛出，由调用方按「必需 / 可选」决定是否中断；这样一批 5000
 * 个资源里少数 404 不会让其余文件白等。
 */
internal class DownloadScheduler(
    private val network: NetworkClient,
) {
    suspend fun run(
        artifacts: List<DownloadArtifact>,
        context: JobContext,
        concurrency: Int = DEFAULT_FILE_CONCURRENCY,
        sink: DownloadProgressSink = DownloadProgressSink.None,
    ): List<FileFailure> =
        coroutineScope {
            val unique = artifacts.distinctBy { it.target }
            if (unique.isEmpty()) return@coroutineScope emptyList()
            sink.planned(unique.size, unique.sumOf { if (it.size > 0) it.size else 0L })
            val batch = BatchState(unique)
            val workers = startWorkers(this, batch, concurrency, context, sink)
            try {
                workers.joinAll()
            } finally {
                if (!coroutineContext.isActive) {
                    // 取消后不再派发；本批可能还有半截缓存，逐个清掉，别把 .part 留给用户。
                    discardPartials(unique)
                }
            }
            batch.failures.toList()
        }

    /** 启动固定数量的工作协程，从共享队列里取文件；单个文件失败只登记，不结束整批。 */
    private fun startWorkers(
        scope: CoroutineScope,
        batch: BatchState,
        concurrency: Int,
        context: JobContext,
        sink: DownloadProgressSink,
    ) = (0 until minOf(concurrency.coerceIn(1, MAX_FILE_CONCURRENCY), batch.unique.size)).map {
        scope.launch(Dispatchers.IO + TransferTrafficContext(context)) {
            while (coroutineContext.isActive) {
                context.checkCancelled()
                val index = batch.next.getAndIncrement()
                if (index >= batch.unique.size) break
                downloadOne(batch.unique[index], sink, batch.failures)
            }
        }
    }

    /** 下载单个文件：失败折成 [FileFailure] 记进批次，让其余文件继续。 */
    private suspend fun downloadOne(
        artifact: DownloadArtifact,
        sink: DownloadProgressSink,
        failures: ConcurrentLinkedQueue<FileFailure>,
    ) {
        val key = artifact.target.toString()
        sink.started(key, artifact)
        try {
            network.download(artifact) { done, total ->
                sink.bytes(key, done, total)
            }
            sink.finished(key, artifact)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            recordFailure(artifact, key, error, sink, failures)
        } catch (error: IllegalArgumentException) {
            recordFailure(artifact, key, error, sink, failures)
        } catch (error: IllegalStateException) {
            recordFailure(artifact, key, error, sink, failures)
        } catch (error: URISyntaxException) {
            recordFailure(artifact, key, error, sink, failures)
        }
    }

    /** 记录一次文件级失败；[error] 是这次尝试实际抛出的异常，消息原样保留。 */
    private fun recordFailure(
        artifact: DownloadArtifact,
        key: String,
        error: Throwable,
        sink: DownloadProgressSink,
        failures: ConcurrentLinkedQueue<FileFailure>,
    ) {
        failures += failureOf(artifact, error)
        sink.failed(key, artifact)
    }

    /** 取消后清掉本批可能残留的 `.part` 与分段缓存，不把半截文件留给用户。 */
    private fun discardPartials(unique: List<DownloadArtifact>) {
        unique.forEach { artifact ->
            runCatching { network.discardPartial(artifact.target) }
        }
    }

    private fun failureOf(
        artifact: DownloadArtifact,
        error: Throwable,
    ): FileFailure {
        val http =
            generateSequence<Throwable>(error) { it.cause }
                .take(16)
                .filterIsInstance<DownloadHttpException>()
                .firstOrNull()
        val host =
            http?.host
                ?: runCatching { URI(artifact.url).host.orEmpty() }.getOrNull().orEmpty()
        return FileFailure(
            target = artifact.target,
            host = host,
            status = http?.code,
            reason = error.message ?: error.javaClass.simpleName,
        )
    }

    companion object {
        /** 单批允许的最大文件并发，与 HMCL 的上限一致。 */
        const val MAX_FILE_CONCURRENCY = MAX_DOWNLOAD_THREADS
    }
}

/** 复用旧 `downloadAll` 语义的简单进度：按完成文件数在 [from]..[to] 区间内推进。 */
internal class BatchRangeProgress(
    private val context: JobContext,
    private val label: String,
    private val from: Float,
    private val to: Float,
) : DownloadProgressSink {
    private val finished = AtomicInteger()

    @Volatile private var total = 1

    override fun planned(
        files: Int,
        bytes: Long,
    ) {
        total = maxOf(files, 1)
        context.beginTransfers(files)
    }

    override fun bytes(
        key: String,
        done: Long,
        total: Long,
    ) {
        context.transfer(key, done, total)
    }

    override fun finished(
        key: String,
        artifact: DownloadArtifact,
    ) {
        val size =
            if (artifact.size > 0) {
                artifact.size
            } else {
                runCatching { artifact.target.fileSize() }.getOrDefault(0L)
            }
        context.transfer(key, size, size, completed = true)
        val count = finished.incrementAndGet()
        context.progress(from + (to - from) * count / total, "$label $count/$total")
    }
}
