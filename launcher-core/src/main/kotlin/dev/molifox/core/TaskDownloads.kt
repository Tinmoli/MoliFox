// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 记录任务新下载的文件，取消时清理，并保护已存在或被其他实例使用的内容。

package dev.molifox.core

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal class TaskDownloadContext(
    val task: JobContext,
) : AbstractCoroutineContextElement(Key) {
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
                    if (
                        current != null &&
                        current.isRegularFile &&
                        path.toRealPath() == path.toAbsolutePath().normalize() &&
                        current.size() == owned.size &&
                        current.lastModifiedTime() == owned.modified &&
                        current.fileKey() == owned.key
                    ) {
                        Files.deleteIfExists(path)
                    }
                }
            }
        }
    }
}
