// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责启动器自有日志、更新包和临时目录的有界清理；不接触游戏与用户配置。

package dev.molifox.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
import java.util.UUID
import java.util.zip.GZIPOutputStream
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

private const val RETAINED_LOG_ARCHIVES = 5

/** 启动早期先把上一会话日志移出活动文件，压缩工作稍后在 IO 协程完成。 */
internal fun prepareLogSession(
    directories: LauncherDirectories,
    timestamp: Long = System.currentTimeMillis(),
) {
    listOf(directories.previousLog, directories.launcherLog).forEachIndexed { index, source ->
        runCatching {
            if (!source.isRegularFile(NOFOLLOW_LINKS)) return@runCatching
            if (source.fileSize() == 0L) {
                Files.deleteIfExists(source)
                return@runCatching
            }
            val target = directories.archivedLogs.resolve("launcher-$timestamp-$index.log")
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

/** 只维护启动器明确拥有的缓存：失败保留到下次启动重试，不影响启动。 */
internal suspend fun maintainLauncherStorage(store: LauncherStore) =
    withContext(Dispatchers.IO) {
        runCatching { compressLogArchives(store.directories.archivedLogs) }
        runCatching { clearDirectory(store.downloadDirectory.resolve("updates")) }
        runCatching { clearTemporaryDirectory(store.temporaryDirectory) }
        runCatching { clearEasyTierWorkDirectories(store.directories.runtimes) }
    }

private fun compressLogArchives(directory: Path) {
    if (!directory.isDirectory(NOFOLLOW_LINKS)) return
    directory.listDirectoryEntries("*.log").forEach(::compressLog)
    directory
        .listDirectoryEntries("*.log.gz")
        .sortedByDescending { it.getLastModifiedTime(NOFOLLOW_LINKS).toMillis() }
        .drop(RETAINED_LOG_ARCHIVES)
        .forEach { Files.deleteIfExists(it) }
}

private fun compressLog(source: Path) {
    if (!source.isRegularFile(NOFOLLOW_LINKS)) return
    val target = source.resolveSibling("${source.fileName}.gz")
    val temporary = target.resolveSibling(".${target.fileName}.${UUID.randomUUID()}.part")
    try {
        Files.newInputStream(source).use { input ->
            GZIPOutputStream(Files.newOutputStream(temporary)).use(input::copyTo)
        }
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        Files.deleteIfExists(source)
    } finally {
        Files.deleteIfExists(temporary)
    }
}

private fun clearTemporaryDirectory(root: Path) {
    if (!root.isDirectory(NOFOLLOW_LINKS)) return
    val current =
        System
            .getProperty("molifox.temp.dir")
            ?.let {
                runCatching { Path.of(it).toAbsolutePath().normalize() }
                    .getOrNull()
            }
    root.listDirectoryEntries().forEach { child ->
        val normalized = child.toAbsolutePath().normalize()
        val keep = child.fileName.toString() == "games" || current?.startsWith(normalized) == true
        if (!keep) deleteTree(child, root)
    }
}

private fun clearEasyTierWorkDirectories(runtimes: Path) {
    if (!runtimes.isDirectory(NOFOLLOW_LINKS)) return
    runtimes.listDirectoryEntries().forEach { child ->
        val name = child.fileName.toString()
        if (name.startsWith("easytier-install-") || name == ".easytier-backup") {
            deleteTree(child, runtimes)
        }
    }
}

private fun clearDirectory(directory: Path) {
    if (!Files.exists(directory, NOFOLLOW_LINKS)) return
    deleteTree(directory, directory.parent)
}

private fun deleteTree(
    target: Path,
    owner: Path,
) {
    val root = owner.toAbsolutePath().normalize()
    val normalized = target.toAbsolutePath().normalize()
    require(normalized.parent != null && normalized.startsWith(root) && normalized != root)
    Files.walk(normalized).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
}
