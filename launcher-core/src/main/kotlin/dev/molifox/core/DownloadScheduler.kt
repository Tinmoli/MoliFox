// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责按文件有界并发下载、汇总失败，并在取消时清理断点缓存。

package dev.molifox.core

import java.io.IOException
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlin.io.path.fileSize
import kotlinx.coroutines.*

/** 默认同时下载的文件数；官方源用满，镜像源另受 [DownloadThrottle] 限制。 */
internal const val DEFAULT_FILE_CONCURRENCY = 8

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
                add(if (host.isBlank()) "来源未知" else "来源 $host")
                add(status?.let { "HTTP $it" } ?: reason)
            }
            .filter { it.isNotBlank() }
            .joinToString(" · ")
}

/** 一批文件下载结束后一次性报告的失败列表，不因第一个 404 丢掉其余结果。 */
internal class DownloadBatchException(val failures: List<FileFailure>) :
    IOException(
        buildString {
            append("以下 ${failures.size} 个文件下载失败：")
            failures.take(12).forEach { append('\n').append(it.describe()) }
            if (failures.size > 12) append("\n…另有 ${failures.size - 12} 个文件失败")
        }
    )

/**
 * 有界并发的文件下载：固定数量的工作协程从队列里取文件，每个文件内部仍然走
 * [NetworkClient.download] 的分段路径。取消时不再派发新文件，等在传文件中止后
 * 清掉本批可能残留的 `.part` 与分段缓存。
 *
 * 逐文件失败只收集不抛出，由调用方按「必需 / 可选」决定是否中断；这样一批 5000
 * 个资源里少数 404 不会让其余文件白等。
 */
internal class DownloadScheduler(private val network: NetworkClient) {
    suspend fun run(
        artifacts: List<DownloadArtifact>,
        context: JobContext,
        concurrency: Int = DEFAULT_FILE_CONCURRENCY,
        sink: DownloadProgressSink = DownloadProgressSink.None,
    ): List<FileFailure> = coroutineScope {
        val unique = artifacts.distinctBy { it.target }
        if (unique.isEmpty()) return@coroutineScope emptyList()
        sink.planned(unique.size, unique.sumOf { if (it.size > 0) it.size else 0L })
        val limit = concurrency.coerceIn(1, MAX_FILE_CONCURRENCY)
        val next = AtomicInteger()
        val failures = ConcurrentLinkedQueue<FileFailure>()
        val workers =
            (0 until minOf(limit, unique.size)).map {
                launch(Dispatchers.IO) {
                    while (coroutineContext.isActive) {
                        context.checkCancelled()
                        val index = next.getAndIncrement()
                        if (index >= unique.size) break
                        val artifact = unique[index]
                        val key = artifact.target.toString()
                        sink.started(key, artifact)
                        try {
                            network.download(artifact) { done, total ->
                                sink.bytes(key, done, total)
                            }
                            sink.finished(key, artifact)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            failures += failureOf(artifact, error)
                            sink.failed(key, artifact)
                        }
                    }
                }
            }
        try {
            workers.joinAll()
        } finally {
            if (!coroutineContext.isActive) {
                // 取消后不再派发；本批可能还有半截缓存，逐个清掉，别把 .part 留给用户。
                unique.forEach { artifact ->
                    runCatching { network.discardPartial(artifact.target) }
                }
            }
        }
        failures.toList()
    }

    private fun failureOf(artifact: DownloadArtifact, error: Throwable): FileFailure {
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
        /** 单批允许的最大文件并发；再高对镜像和磁盘都没有好处。 */
        const val MAX_FILE_CONCURRENCY = 16
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

    override fun planned(files: Int, bytes: Long) {
        total = maxOf(files, 1)
        context.beginTransfers(files)
    }

    override fun bytes(key: String, done: Long, total: Long) {
        context.transfer(key, done, total)
    }

    override fun finished(key: String, artifact: DownloadArtifact) {
        val size =
            if (artifact.size > 0) artifact.size
            else runCatching { artifact.target.fileSize() }.getOrDefault(0L)
        context.transfer(key, size, size, completed = true)
        val count = finished.incrementAndGet()
        context.progress(from + (to - from) * count / total, "$label $count/$total")
    }
}