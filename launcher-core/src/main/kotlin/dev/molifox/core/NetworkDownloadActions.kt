// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责文件下载：目标锁、断点、候选源传输与校验。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import dev.molifox.core.net.CandidateSource
import dev.molifox.core.net.DownloadConcurrency
import dev.molifox.core.net.DownloadSourceFailureException
import dev.molifox.core.net.SourceAttempt
import dev.molifox.core.net.SourceThrottle
import dev.molifox.core.net.TransferBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

/** 重试退避：500ms 起翻倍，最长 8 秒。 */
private const val HTTP_RANGE_NOT_SATISFIABLE = 416
private const val RETRY_BACKOFF_BASE_MILLIS = 500L
private const val MAX_RETRY_BACKOFF_MILLIS = 8_000L

/** 单个文件对同一源的限流重试次数；1s→2s→4s 之后仍被拒就换下一个源。 */
private const val MAX_RATE_LIMIT_RETRIES = 3

/** 403/429：限流而不是失败，退避后重试同一源，不计入禁用。 */
private val RATE_LIMIT_STATUSES = setOf(403, 429)

/** 断点文件与源健康度入口：取消下载后清理、换源前重置。 */
internal interface NetworkTransferApi {
    fun partialFile(target: Path): Path

    fun discardPartial(target: Path)

    fun resetSourceHealth()
}

/** 一次整文件下载在候选源之间共享的状态：目标、断点、传输闸门与失败记录。 */
private class CandidateFetchRun(
    val artifact: DownloadArtifact,
    val partial: Path,
    val perSource: Int,
    val multiSource: Boolean,
    val budget: TransferBudget,
    val progress: (Long, Long) -> Unit,
) {
    val failures = mutableListOf<SourceAttempt>()
}

/** 下载会话：断点文件、目标锁、任务日志与传输预算。 */
internal abstract class NetworkSessionActions(
    settings: NetworkClientSettings,
) : NetworkPayloadActions(settings),
    NetworkTransferApi {
    /** 按目标路径分片的下载锁，避免同一文件被并发写入同一份 `.part`。 */
    private val downloadLocks = List(16) { Mutex() }
    protected val downloadJournals = ConcurrentHashMap<String, TaskDownloadJournal>()

    /** 传输闸门：并发数按任务快照，设置改动只影响下一个任务。 */
    private val transferBudgetLock = Mutex()

    @Volatile private var transferBudget: TransferBudget? = null

    @Volatile private var transferBudgetKey: Pair<Int, String>? = null

    /** 断点缓存固定为 `<目标>.part`，与目标同目录，随实例目录一起迁移或清理。 */
    override fun partialFile(target: Path): Path = target.resolveSibling("${target.fileName}.part")

    /** 删除普通与分段缓存；显式取消不保留断点，网络中断则允许重试续传。 */
    override fun discardPartial(target: Path) {
        partialFile(target).deleteIfExists()
        SegmentedDownloader.discard(target)
    }

    /** 新一轮安装开始时调用：清空上一轮的源禁用与限流状态，并让预算重新记一次日志。 */
    override fun resetSourceHealth() {
        settings.health.reset()
        transferBudget = null
        transferBudgetKey = null
    }

    suspend fun transferBudget(): TransferBudget {
        val units = settings.maxConcurrent().coerceIn(1, MAX_DOWNLOAD_THREADS)
        val taskId = coroutineContext[TaskDownloadContext]?.task?.id ?: ""
        val key = units to taskId
        if (transferBudgetKey == key) transferBudget?.let { return it }
        return transferBudgetLock.withLock {
            if (transferBudgetKey == key) transferBudget?.let { return@withLock it }
            TransferBudget(units, settings.downloadBudgetBytes).also {
                transferBudget = it
                transferBudgetKey = key
            }
        }
    }

    fun logClampedConcurrency(value: DownloadConcurrency) {
        value.note?.let { settings.logger(it, false) }
    }

    /** 同一目标的并发下载共用一把锁：`.part` 只有一份，不能让两个协程同时写。 */
    suspend fun <T> withTargetLock(
        target: Path,
        block: suspend () -> T,
    ): T {
        val key = taskPathKey(target)
        val index = Math.floorMod(key.hashCode(), downloadLocks.size)
        return downloadLocks[index].withLock { block() }
    }

    /** 登记本轮任务的下载日志：取消时清理断点，完成后摘除记录。 */
    suspend fun registerDownloadJournal(): TaskDownloadJournal? {
        val task = coroutineContext[TaskDownloadContext]?.task ?: return null
        return downloadJournals
            .computeIfAbsent(task.id) { _ -> TaskDownloadJournal(task) }
            .also { journal ->
                task.onFinished(
                    this,
                    cancel = {
                        journal.clean { file, action ->
                            withTargetLock(file) { action() }
                        }
                    },
                    complete = { downloadJournals.remove(task.id) },
                )
            }
    }
}

/** 断点续传：请求一次（416 时再请求一次），写入 `.part` 并校验后改名。 */
internal abstract class NetworkResumeActions(
    settings: NetworkClientSettings,
) : NetworkSessionActions(settings) {
    /** 单次尝试：请求一次（416 时再请求一次），成功即完成改名，失败抛异常交给重试。 */
    protected suspend fun fetch(
        artifact: DownloadArtifact,
        partial: Path,
        url: String,
        progress: (Long, Long) -> Unit,
    ) {
        repeat(2) {
            val offset = resumeOffset(artifact, partial)
            val request = builder(url)
            if (offset > 0) request.header("Range", "bytes=$offset-")
            var restart = true
            execute(request.build(), attempts = 1).readCancellable { response ->
                restart = receive(artifact, partial, offset, response, progress)
            }
            if (!restart) return
        }
        // 第二次仍未成功：断点或服务器状态异常，交给重试策略。
        throw DownloadHttpException(HTTP_RANGE_NOT_SATISFIABLE, java.net.URI(url).host)
    }

    /** 处理一次响应：写完并校验后改名成功返回 `false`；需要清空 `.part` 重新请求时返回 `true`。 */
    private suspend fun receive(
        artifact: DownloadArtifact,
        partial: Path,
        offset: Long,
        response: Response,
        progress: (Long, Long) -> Unit,
    ): Boolean {
        val start = resumableStart(partial, response, offset) ?: return true
        val body = checkedResponseBody(response)
        val total = totalBytes(artifact, response, start)
        val digest = MessageDigest.getInstance(artifact.hashAlgorithm)
        if (start > 0) updateDigest(partial, digest, start)
        writeResponseBody(body, partial, start, total, digest, progress)
        commitVerified(artifact, partial, digest, total, progress)
        return false
    }

    /** 把响应体追加到 `.part` 并同步累加摘要。 */
    private suspend fun writeResponseBody(
        body: ResponseBody,
        partial: Path,
        start: Long,
        total: Long,
        digest: MessageDigest,
        progress: (Long, Long) -> Unit,
    ) {
        body.byteStream().use { input ->
            Files
                .newOutputStream(
                    partial,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND,
                ).use { output ->
                    copyBody(input, output, digest, start, total, progress)
                }
        }
    }

    /** 逐块搬运响应体、累加摘要并上报进度；取消时立即中断。 */
    private suspend fun copyBody(
        input: InputStream,
        output: OutputStream,
        digest: MessageDigest,
        start: Long,
        total: Long,
        progress: (Long, Long) -> Unit,
    ) {
        val buffer = ByteArray(65536)
        var received = 0L
        while (true) {
            coroutineContext.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            digest.update(buffer, 0, count)
            received += count
            progress(start + received, total)
        }
    }
}

/** 候选源传输：换源、限流退避与失败汇总。 */
internal abstract class NetworkCandidateActions(
    settings: NetworkClientSettings,
) : NetworkResumeActions(settings) {
    /**
     * 依次尝试候选源：一个源失败立刻换下一个；403/429 不是失败，按 1→2→4s 退避后重试
     * 同一个源，且不计入禁用。所有源都失败时抛出带「每个源尝试的 URL 与状态码」的异常。
     */
    protected suspend fun fetchFromCandidates(
        artifact: DownloadArtifact,
        partial: Path,
        candidates: List<CandidateSource>,
        attempts: Int,
        progress: (Long, Long) -> Unit,
    ) {
        if (candidates.isEmpty()) {
            throw DownloadSourceFailureException(artifact.target.toString(), mutableListOf())
        }
        val run =
            CandidateFetchRun(
                artifact = artifact,
                partial = partial,
                perSource = if (candidates.size > 1) 1 else attempts,
                multiSource = candidates.size > 1,
                budget = transferBudget(),
                progress = progress,
            )
        var lastError: Exception? = null
        for (candidate in candidates) {
            lastError = fetchFromSource(run, candidate) ?: return
        }
        // 只有一个候选源时保留原来的异常类型与中文消息（调用方按状态码分类）；
        // 多个源都失败时汇总，逐个列出尝试过的 URL 与状态码。
        allSourcesFailed(artifact, candidates, run.failures, lastError)
    }

    /** 在同一个源上尝试若干次；成功返回 null，失败返回最后一次异常并登记失败记录。 */
    private suspend fun fetchFromSource(
        run: CandidateFetchRun,
        candidate: CandidateSource,
    ): Exception? {
        var attempt = 0
        var rateLimitRetries = 0
        while (true) {
            coroutineContext.ensureActive()
            attempt++
            val failure = transferOnce(run, candidate) ?: return null
            coroutineContext.ensureActive()
            val status = (failure as? DownloadHttpException)?.code
            val rateLimited = status != null && status in RATE_LIMIT_STATUSES
            if (rateLimited && rateLimitRetries < MAX_RATE_LIMIT_RETRIES) {
                rateLimitRetries++
                delay(reportRateLimited(candidate, status))
                attempt--
                continue
            }
            recordAttemptFailure(run, candidate, status, rateLimited, failure)
            val recoverable = isRecoverableDownloadFailure(failure, run.multiSource)
            if (attempt >= run.perSource || !recoverable) {
                return failure
            }
            delay(minOf(RETRY_BACKOFF_BASE_MILLIS shl (attempt - 1), MAX_RETRY_BACKOFF_MILLIS))
        }
    }

    /**
     * 一次源传输：传输闸门在外、源闸门在内，覆盖整次文件传输。成功返回 null；
     * 取消照旧抛出，其余失败交给调用方决定换源还是重试。
     */
    private suspend fun transferOnce(
        run: CandidateFetchRun,
        candidate: CandidateSource,
    ): Exception? =
        try {
            transferOnSource(run, candidate)
            settings.health.recordSuccess(candidate.source.key)
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: java.io.IOException) {
            error
        } catch (error: IllegalStateException) {
            error
        } catch (error: IllegalArgumentException) {
            error
        }

    /** 传输闸门（内存预算）与源闸门（镜像并发与间隔）都覆盖整次文件传输。 */
    private suspend fun transferOnSource(
        run: CandidateFetchRun,
        candidate: CandidateSource,
    ) {
        run.budget.run(run.artifact.size, ::logClampedConcurrency) {
            SourceThrottle.run(candidate.source, settings.mirrorConcurrency()) {
                fetchSegmentedOrPlain(run, candidate)
            }
        }
    }

    /** 大文件先走分段下载；不支持分段时退化为普通续传请求。 */
    private suspend fun fetchSegmentedOrPlain(
        run: CandidateFetchRun,
        candidate: CandidateSource,
    ) {
        val segmented =
            SegmentedDownloader { url, range ->
                execute(builder(url).header("Range", range).build(), attempts = 1)
            }
        if (
            !segmented.download(
                run.artifact,
                run.partial,
                candidate.url,
                settings.downloadSegments(),
                run.progress,
            )
        ) {
            fetch(run.artifact, run.partial, candidate.url, run.progress)
        }
    }

    /** 汇总所有候选源的失败：单源保留原异常，多源构造带每个源尝试信息的异常。 */
    private fun allSourcesFailed(
        artifact: DownloadArtifact,
        candidates: List<CandidateSource>,
        failures: MutableList<SourceAttempt>,
        lastError: Exception?,
    ): Nothing {
        if (candidates.size == 1) {
            throw lastError
                ?: DownloadSourceFailureException(artifact.target.toString(), failures)
        }
        throw DownloadSourceFailureException(artifact.target.toString(), failures)
    }

    /** 限流不是失败：记一次健康度事件并返回同一源要等待的毫秒数。 */
    private fun reportRateLimited(
        candidate: CandidateSource,
        status: Int?,
    ): Long {
        val wait = settings.health.recordRateLimited(candidate.source.key)
        settings.logger(
            I18n.t("networkA.source.rateLimited", candidate.source.title, status, wait),
            false,
        )
        return wait
    }

    /** 记下这轮尝试的 URL 与状态码；连续失败达到阈值时同时把源标记为禁用。 */
    private fun recordAttemptFailure(
        run: CandidateFetchRun,
        candidate: CandidateSource,
        status: Int?,
        rateLimited: Boolean,
        failure: Exception,
    ) {
        run.failures +=
            SourceAttempt(
                candidate.source.key,
                candidate.source.title,
                candidate.url,
                status,
                failure.message ?: failure.javaClass.simpleName,
            )
        if (!rateLimited && settings.health.recordFailure(candidate.source.key)) {
            val count = settings.health.state(candidate.source.key).consecutiveFailures
            settings.logger(
                I18n.t("networkA.source.disabled", candidate.source.title, count),
                true,
            )
        }
    }
}

/** 整文件下载入口：目标锁、目录准备与候选源传输。 */
internal class NetworkActions(
    settings: NetworkClientSettings,
) : NetworkCandidateActions(settings) {
    /** 下载到临时文件，校验整体大小与摘要后替换目标；网络失败可续传，显式取消则清理。 */
    suspend fun download(
        artifact: DownloadArtifact,
        attempts: Int = transport.attemptLimit(),
        progress: (Long, Long) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        val target = artifact.target
        withTargetLock(target) {
            prepareTargetDirectory(artifact)
            val partial = partialFile(target)
            val path = target.toAbsolutePath().normalize()
            val journal = registerDownloadJournal()
            downloadJournals.values.filter { it !== journal }.forEach { it.borrowed(path) }
            val existed = target.exists()
            if (artifactProblem(artifact) == null) {
                // 目标已经完整，残留的断点只会占空间。
                discardPartial(target)
                return@withTargetLock
            }
            transferFromCandidates(artifact, partial, existed, journal, attempts, progress)
        }
    }

    /** 可选文件失败只记一行警告并跳过，返回 `false` 让整批继续。 */
    suspend fun downloadOptional(
        artifact: DownloadArtifact,
        attempts: Int = transport.attemptLimit(),
        progress: (Long, Long) -> Unit = { _, _ -> },
    ): Boolean =
        try {
            download(artifact, attempts, progress)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: java.io.IOException) {
            skipOptionalDownload(settings.logger, artifact, error)
        } catch (error: IllegalStateException) {
            skipOptionalDownload(settings.logger, artifact, error)
        } catch (error: IllegalArgumentException) {
            skipOptionalDownload(settings.logger, artifact, error)
        }

    /** 传输前的目录准备：先确认任务仍有效，再交给任务钩子检查目录创建。 */
    private suspend fun prepareTargetDirectory(artifact: DownloadArtifact) {
        val parent = artifact.target.parent
        coroutineContext.ensureActive()
        coroutineContext[TaskDownloadContext]?.task?.beforeDirectoryCreation(parent)
        parent.createDirectories()
    }

    /** 在候选源之间传输文件并落盘；取消与中断时的清理保持原样。 */
    private suspend fun transferFromCandidates(
        artifact: DownloadArtifact,
        partial: Path,
        existed: Boolean,
        journal: TaskDownloadJournal?,
        attempts: Int,
        progress: (Long, Long) -> Unit,
    ) {
        val path = artifact.target.toAbsolutePath().normalize()
        try {
            fetchFromCandidates(
                artifact,
                partial,
                orderedSources(artifact.url),
                attempts.coerceIn(1, MAX_DOWNLOAD_ATTEMPT_LIMIT),
                progress,
            )
            if (!existed) journal?.completed(path)
        } catch (cancelled: CancellationException) {
            discardPartial(artifact.target)
            throw cancelled
        } finally {
            if (!existed && artifact.target.isRegularFile()) journal?.completed(path)
            if (!coroutineContext.isActive) discardPartial(artifact.target)
        }
    }
}
