// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 记录任务新下载的文件，取消时清理，并保护已存在或被其他实例使用的内容。

package dev.molifox.core

import dev.molifox.core.net.TransferBudget
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

internal class TransferTrafficContext(
    val task: JobContext,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TransferTrafficContext>
}

internal suspend fun recordNetworkBytes(bytes: Int) {
    val task = coroutineContext[TransferTrafficContext]?.task ?: coroutineContext[TaskDownloadContext]?.task
    task?.networkBytes(bytes.toLong())
    coroutineContext[SourceTransferSample]?.bytes?.addAndGet(bytes.toLong())
}

internal class SourceTransferSample : AbstractCoroutineContextElement(Key) {
    val bytes = AtomicLong()

    companion object Key : CoroutineContext.Key<SourceTransferSample>
}

/**
 * 从文件请求发起到响应关闭计一次，取消与异常也会释放计数。
 * API 请求不计入；没有任务上下文时不计数。
 */
internal suspend fun <T> withTransferConnection(block: suspend () -> T): T {
    val task = coroutineContext[TransferTrafficContext]?.task ?: coroutineContext[TaskDownloadContext]?.task
    task?.connectionOpened()
    try {
        return block()
    } finally {
        task?.connectionClosed()
    }
}

internal class TaskDownloadContext(
    val task: JobContext,
) : AbstractCoroutineContextElement(Key) {
    // 随本任务上下文一起释放；不同网络会话各自冻结首次下载时的并发设置。
    val budgets = ConcurrentHashMap<Any, TransferBudget>()

    companion object Key : CoroutineContext.Key<TaskDownloadContext>
}

internal class TaskDownloadJournal(
    val task: JobContext,
) {
    private data class OwnedFile(
        val size: Long,
        val modified: java.nio.file.attribute.FileTime,
        val key: Any?,
    )

    private val files = ConcurrentHashMap<Path, OwnedFile>()
    private val borrowed = ConcurrentHashMap.newKeySet<Path>()

    fun borrowed(path: Path) {
        borrowed.add(path)
    }

    fun completed(path: Path) {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (attributes.isRegularFile) {
            files[path] =
                OwnedFile(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey())
        }
    }

    suspend fun clean(withLock: suspend (Path, suspend () -> Unit) -> Unit) {
        for ((path, owned) in files) {
            withLock(path) {
                if (path !in borrowed && task.canRemoveDownload(path)) {
                    val current =
                        runCatching {
                            Files.readAttributes(
                                path,
                                BasicFileAttributes::class.java,
                                NOFOLLOW_LINKS,
                            )
                        }.getOrNull()
                    val unchanged =
                        current != null &&
                            current.isRegularFile &&
                            current.size() == owned.size
                    val sameEntry =
                        current != null &&
                            current.lastModifiedTime() == owned.modified &&
                            current.fileKey() == owned.key
                    val samePath =
                        current != null && path.toRealPath() == path.toAbsolutePath().normalize()
                    if (unchanged && sameEntry && samePath) {
                        Files.deleteIfExists(path)
                    }
                }
            }
        }
    }
}
