// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责实例目录的导出扫描：顶层条目、逐目录统计与节流快照。

package dev.molifox.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Locale
import kotlin.coroutines.coroutineContext

private const val SNAPSHOT_INTERVAL_NANOS = 60_000_000L
private const val FILES_PER_SNAPSHOT = 64

/** 单个顶层条目的扫描累计值；只在扫描协程内读写。 */
private class ExportEntryState(
    val name: String,
    val directory: Boolean,
    val backupOnly: Boolean,
    val unavailable: Boolean,
) {
    var fileCount = 0L
    var totalBytes = 0L
    var scanning = true
    val tree = ExportTreeNode(name, directory)

    /** 扫描过程中按相对路径补全实际子项树；界面只在展开时读取。 */
    fun addNode(
        relative: String,
        directory: Boolean,
    ) {
        val parts = relative.split('/')
        if (parts.size < 2) return
        tree.add(parts.drop(1), directory)
    }
}

/** 可变的内容树；对外快照转成不可变的 `PackExportNode`。 */
private class ExportTreeNode(
    private val path: String,
    private val directory: Boolean,
) {
    private val children = linkedMapOf<String, ExportTreeNode>()

    fun add(
        parts: List<String>,
        directory: Boolean,
    ) {
        if (parts.isEmpty()) return
        val childDirectory = directory || parts.size > 1
        val childPath = "$path/${parts.first()}"
        val child =
            children.getOrPut(parts.first()) { ExportTreeNode(childPath, childDirectory) }
        child.add(parts.drop(1), directory)
    }

    fun toPublic(): List<PackExportNode> = sortedChildren().map { it.toPublicNode() }

    private fun toPublicNode(): PackExportNode =
        PackExportNode(
            path,
            path.substringAfterLast('/'),
            directory,
            sortedChildren().map { it.toPublicNode() },
        )

    private fun sortedChildren(): List<ExportTreeNode> =
        children.values.sortedWith(
            compareByDescending<ExportTreeNode> { it.directory }
                .thenBy { it.path.lowercase(Locale.ROOT) },
        )
}

/** 实例目录的只读扫描：边统计边按节流发送快照。[excludedRoots] 里的顶层条目整体不出现（小写匹配）。 */
internal class PackExportScanner(
    private val root: Path,
    private val versionId: String,
    private val isolated: Boolean,
    private val excludedRoots: Set<String> = emptySet(),
) {
    fun scan(): Flow<PackExportScan> =
        flow {
            val tracks = mutableListOf<ExportEntryState>()
            val omitted = mutableListOf<String>()
            collectTopLevel(tracks, omitted)
            tracks.sortWith(
                compareByDescending<ExportEntryState> { it.directory }
                    .thenBy { it.name.lowercase(Locale.ROOT) },
            )
            omitted.sort()
            val snapshots = SnapshotEmitter(this, tracks, omitted)
            snapshots.emit(force = true)
            for (track in tracks) {
                if (track.unavailable) {
                    track.scanning = false
                    continue
                }
                if (track.directory) scanDirectory(track, snapshots) else scanFile(track)
                track.scanning = false
                snapshots.emit(force = true)
            }
            // 全部条目扫描结束（包括全部不可用的情形）后一定发一次终态。
            snapshots.emit(force = true)
        }

    private suspend fun collectTopLevel(
        tracks: MutableList<ExportEntryState>,
        omitted: MutableList<String>,
    ) {
        Files.newDirectoryStream(root).use { children ->
            for (child in children) {
                coroutineContext.ensureActive()
                collectTopLevelEntry(child, tracks, omitted)
            }
        }
    }

    /** 符号链接、显式排除、实例自有与启动器私有条目不计入导出，其余登记为顶层条目。 */
    private fun collectTopLevelEntry(
        child: Path,
        tracks: MutableList<ExportEntryState>,
        omitted: MutableList<String>,
    ) {
        if (Files.isSymbolicLink(child)) return
        val name = child.fileName.toString()
        val lower = name.lowercase(Locale.ROOT)
        if (lower in excludedRoots || instanceOwnExportFile(name, versionId) || launcherPrivateExportEntry(name)) {
            omitted += name
            return
        }
        tracks += topLevelState(child, name)
    }

    private fun topLevelState(
        child: Path,
        name: String,
    ): ExportEntryState {
        val directory = Files.isDirectory(child, NOFOLLOW_LINKS)
        val key = name.lowercase(Locale.ROOT)
        val backupOnly =
            key in backupOnlyExportRoots ||
                (!directory && (key.endsWith(".log") || key.endsWith(".log.gz")))
        return ExportEntryState(
            name,
            directory,
            backupOnly,
            unavailable = !isolated && key !in exportContentRoots,
        )
    }

    private fun scanFile(track: ExportEntryState) {
        track.fileCount = 1
        track.totalBytes = runCatching { Files.size(root.resolve(track.name)) }.getOrDefault(0L)
    }

    private suspend fun scanDirectory(
        track: ExportEntryState,
        snapshots: SnapshotEmitter,
    ) {
        val pending = ArrayDeque<Path>().apply { add(root.resolve(track.name)) }
        while (pending.isNotEmpty()) {
            coroutineContext.ensureActive()
            collectDirectory(pending.removeFirst(), track, pending, snapshots)
        }
    }

    private suspend fun collectDirectory(
        directory: Path,
        track: ExportEntryState,
        pending: ArrayDeque<Path>,
        snapshots: SnapshotEmitter,
    ) {
        try {
            listDirectory(directory) { file -> visitEntry(file, track, pending, snapshots) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 单个目录不可读时跳过，其余条目照常统计。
        }
    }

    private suspend fun listDirectory(
        directory: Path,
        visit: suspend (Path) -> Unit,
    ) {
        Files.newDirectoryStream(directory).use { paths ->
            for (file in paths) visit(file)
        }
    }

    private suspend fun visitEntry(
        file: Path,
        track: ExportEntryState,
        pending: ArrayDeque<Path>,
        snapshots: SnapshotEmitter,
    ) {
        coroutineContext.ensureActive()
        if (Files.isSymbolicLink(file)) return
        val relative = root.relativize(file).joinToString("/")
        // 日志目录按“显式勾选才写入”统计；普通目录不计嵌套日志。
        if (excludedExportPath(relative, allowLogs = track.backupOnly)) return
        if (Files.isDirectory(file, NOFOLLOW_LINKS)) {
            track.addNode(relative, directory = true)
            pending.add(file)
        } else if (Files.isRegularFile(file, NOFOLLOW_LINKS)) {
            track.addNode(relative, directory = false)
            track.fileCount++
            track.totalBytes += runCatching { Files.size(file) }.getOrDefault(0L)
            snapshots.onFile()
        }
    }
}

/** 60ms / 64 文件节流地把当前累计值发成快照。 */
private class SnapshotEmitter(
    private val collector: FlowCollector<PackExportScan>,
    private val tracks: List<ExportEntryState>,
    private val omitted: List<String>,
) {
    private var lastEmitNanos = 0L
    private var filesSinceEmit = 0

    suspend fun onFile() {
        filesSinceEmit++
        if (filesSinceEmit >= FILES_PER_SNAPSHOT) emit(force = false)
    }

    suspend fun emit(force: Boolean) {
        val now = System.nanoTime()
        if (!force && now - lastEmitNanos < SNAPSHOT_INTERVAL_NANOS) return
        lastEmitNanos = now
        filesSinceEmit = 0
        collector.emit(
            PackExportScan(
                tracks.map {
                    PackExportEntry(
                        it.name,
                        it.directory,
                        it.fileCount,
                        it.totalBytes,
                        it.scanning,
                        it.backupOnly,
                        it.unavailable,
                        it.tree.toPublic(),
                    )
                },
                omitted.toList(),
            ),
        )
    }
}
