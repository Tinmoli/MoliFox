// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责遍历世界目录、收集可复制的普通文件并响应取消。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** 每遍历这么多个文件检查一次取消，避免每个文件都读协程上下文。 */
private const val CANCELLATION_CHECK_INTERVAL = 256

/**
 * 收集世界目录内的普通文件（相对路径统一正斜杠）。
 *
 * 用 [Files.walkFileTree] 而不是 `Files.walk`：Windows 目录联接（junction）的 `isSymbolicLink` 为
 * false，`Files.walk` 会递归进去，必须在 `preVisitDirectory` 里跳过整棵子树。 链接、越出世界目录的路径与读不出的文件只跳过；取消时抛
 * [CancellationException]。
 */
internal suspend fun collectSaveFiles(source: Path): List<Pair<String, Path>> {
    val root = source.toAbsolutePath().normalize()
    val realRoot = root.toRealPath()
    return SaveFileCollector(root, realRoot, currentCoroutineContext()[Job]).collect()
}

/** 单次目录遍历：跳过链接与越出根目录的路径，按相对路径排序后返回。 */
private class SaveFileCollector(
    private val root: Path,
    private val realRoot: Path,
    private val job: Job?,
) : SimpleFileVisitor<Path>() {
    private val files = mutableListOf<Pair<String, Path>>()
    private var visited = 0

    fun collect(): List<Pair<String, Path>> {
        Files.walkFileTree(root, this)
        return files.sortedBy { it.first }
    }

    private fun checkCancelled() {
        if (job != null && !job.isActive) throw CancellationException(I18n.t("saveA2.collector.cancelled"))
    }

    override fun preVisitDirectory(
        dir: Path,
        attrs: BasicFileAttributes,
    ): FileVisitResult {
        checkCancelled()
        if (dir != root && (attrs.isOther || attrs.isSymbolicLink)) {
            return FileVisitResult.SKIP_SUBTREE
        }
        return FileVisitResult.CONTINUE
    }

    override fun visitFile(
        file: Path,
        attrs: BasicFileAttributes,
    ): FileVisitResult {
        if (++visited % CANCELLATION_CHECK_INTERVAL == 0) checkCancelled()
        if (attrs.isRegularFile && !attrs.isOther) {
            val real = runCatching { file.toRealPath() }.getOrNull()
            if (real != null && real.startsWith(realRoot)) {
                files.add(root.relativize(file).joinToString("/") to file)
            }
        }
        return FileVisitResult.CONTINUE
    }

    override fun visitFileFailed(
        file: Path,
        error: IOException,
    ): FileVisitResult = FileVisitResult.CONTINUE
}
