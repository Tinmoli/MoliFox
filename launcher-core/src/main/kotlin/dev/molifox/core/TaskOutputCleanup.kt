// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 记录任务生成的文件和新建目录，在取消且写入停止后回收本次产物。

package dev.molifox.core

import java.nio.file.DirectoryNotEmptyException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.walk

internal class TaskOutputCleanup(
    private val task: JobContext,
) {
    private val journal = TaskDownloadJournal(task)
    private val directories = ConcurrentHashMap.newKeySet<Path>()

    /**
     * 本次任务新建的 `versions/<名字>` 版本目录。
     *
     * 安装加载器时中间会临时出现基础版本目录；整个目录都是任务产物，取消时按引用判断后整棵回收，
     * 而不是只删空目录（否则残留的配置会让下次安装把它当成「已有基础版本」保留下来）。
     */
    private val versionDirectories = ConcurrentHashMap.newKeySet<Path>()

    fun beforeDirectoryCreation(path: Path) {
        var current: Path? = path.toAbsolutePath().normalize()
        while (current != null && !current.exists(NOFOLLOW_LINKS)) {
            directories.add(current)
            if (current.parent?.fileName?.toString() == "versions") versionDirectories.add(current)
            current = current.parent
        }
    }

    fun track(path: Path): () -> Unit {
        val normalized = path.toAbsolutePath().normalize()
        val existed = normalized.exists(NOFOLLOW_LINKS)
        beforeDirectoryCreation(normalized.parent)
        return {
            if (!existed && normalized.isRegularFile(NOFOLLOW_LINKS)) journal.completed(normalized)
        }
    }

    fun write(
        path: Path,
        action: () -> Unit,
    ) {
        val completed = track(path)
        try {
            action()
        } finally {
            completed()
        }
    }

    suspend fun cleanFiles() = journal.clean { _, action -> action() }

    fun cleanEmptyDirectories() {
        for (path in directories.sortedByDescending { it.nameCount }) {
            if (!path.isDirectory(NOFOLLOW_LINKS) || path.toRealPath() != path) continue
            if (path in versionDirectories) {
                cleanVersionDirectory(path)
                continue
            }
            try {
                Files.deleteIfExists(path)
            } catch (_: DirectoryNotEmptyException) {
                // Existing content or another task's files keep their parent directory alive.
            }
        }
    }

    /**
     * 回收本任务新建的版本目录：目录里只可能有本任务的下载与生成的配置。
     *
     * 其它实例仍按实际目录身份、`inheritsFrom` 或 `jar` 引用它时保留；目录变成链接时不删。
     */
    private fun cleanVersionDirectory(path: Path) {
        if (!task.canRemoveDownload(path)) return
        runCatching {
            val paths = Files.walk(path).use { it.toList() }
            require(paths.all { !Files.isSymbolicLink(it) && it.toRealPath() == it }) {
                "新建版本目录包含链接，未删除"
            }
            paths.sortedByDescending { it.nameCount }.forEach(Files::deleteIfExists)
        }
    }
}
