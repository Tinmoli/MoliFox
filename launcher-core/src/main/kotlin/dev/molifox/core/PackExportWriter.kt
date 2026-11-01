// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责整合包扫描、归档写入与内容哈希。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.ensureActive
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/** 单次导出最多收录的文件数，防止扫描结果无上限增长。 */
private const val MAX_EXPORT_FILE_COUNT = 20000

/** 一次导出扫描的共享状态：目录队列、已见名称与收集结果。 */
internal class ExportScanState(
    val store: LauncherStore,
    val instance: GameInstance,
    val root: Path,
    val realRoot: Path,
    val includeLogs: Boolean,
) {
    val warnings: MutableList<String> = mutableListOf()
    val files: MutableList<MrpackExportFile> = mutableListOf()

    /** Standard component metadata cannot preserve another launcher's custom patches. */
    val names: MutableSet<String> = mutableSetOf()
    val contentRoots: Set<String> =
        if (includeLogs) exportContentRoots + backupOnlyExportRoots else exportContentRoots
    val directories: ArrayDeque<Path> = ArrayDeque<Path>().apply { add(root) }
}

/** 从实例根开始逐层遍历目录，把可导出的文件交给 [collectExportFile]。 */
internal suspend fun scanInstanceFiles(state: ExportScanState) {
    while (state.directories.isNotEmpty()) {
        Files.newDirectoryStream(state.directories.removeFirst()).use { paths ->
            for (file in paths) {
                coroutineContext.ensureActive()
                collectExportFile(state, file)
            }
        }
    }
}

/** 处理扫描到的一个目录项：先按相对路径过滤，校验路径后再决定入队或加入清单。 */
private suspend fun collectExportFile(
    state: ExportScanState,
    file: Path,
) {
    if (Files.isSymbolicLink(file)) return
    val path = state.root.relativize(file).joinToString("/")
    if (excludedExportPath(path, state.includeLogs)) return
    if (instanceOwnExportFile(path, state.instance.versionId)) return
    val top = path.substringBefore('/').lowercase(Locale.ROOT)
    if (!state.store.isolated(state.instance) && top !in state.contentRoots) return
    val checked = safePath(state.root, path)
    require(checked.toRealPath().startsWith(state.realRoot)) {
        I18n.t("packsA2.packExport.fileOutsideInstance", path)
    }
    classifyCheckedEntry(state, path, top, checked)
}

/** 已确认位于实例目录内的条目：目录入队，普通文件按类型与凭据特征决定是否写入。 */
private suspend fun classifyCheckedEntry(
    state: ExportScanState,
    path: String,
    top: String,
    checked: Path,
) {
    when {
        Files.isDirectory(checked, NOFOLLOW_LINKS) -> state.directories.add(checked)
        !Files.isRegularFile(checked, NOFOLLOW_LINKS) -> return
        // 日志可能带登录凭据：即使已选择包含日志，命中凭据特征的单个文件也跳过。
        state.includeLogs && backupOnlyExportPath(path) && exportLogContainsSecret(checked) ->
            state.warnings += I18n.t("packsA2.packExport.credentialLogSkipped", path)
        else -> addExportFile(state, path, top, checked)
    }
}

/** 先校验同名冲突与数量上限，再计算哈希写入清单：顺序与原先的判定顺序一致。 */
private suspend fun addExportFile(
    state: ExportScanState,
    path: String,
    top: String,
    checked: Path,
) {
    require(state.names.add(path.lowercase(Locale.ROOT))) {
        I18n.t("packsA2.packExport.nameConflictOnWindows", path)
    }
    require(state.files.size < MAX_EXPORT_FILE_COUNT) {
        I18n.t("packsA2.packExport.tooManyInstanceFiles")
    }
    state.files += buildExportFile(path, top, hashExportFile(checked))
}

/** 由哈希与相对路径生成导出条目：模组/资源包/光影或可执行资源需要确认分发权限。 */
private fun buildExportFile(
    path: String,
    top: String,
    hashes: ExportHashes,
): MrpackExportFile {
    val resource =
        top in setOf("mods", "resourcepacks", "shaderpacks") ||
            path
                .substringAfterLast('.', "")
                .lowercase(Locale.ROOT) in
            setOf("jar", "zip", "mrpack", "exe", "dll", "so")
    return MrpackExportFile(
        path,
        hashes.size,
        hashes.sha1,
        hashes.sha512,
        null,
        top in exportDefaultRoots && !resource,
        resource,
    )
}

/** 依次写入目录条目、清单文档、图标与文件内容，每项都报告进度。 */
internal suspend fun writePackEntries(
    zip: ZipOutputStream,
    layout: PackExportLayout,
    plan: MrpackExportPlan,
    context: JobContext,
) {
    val total = layout.totalEntries
    var written = 0
    for (directory in layout.directories) {
        context.checkCancelled()
        zip.putNextEntry(ZipEntry(directory))
        zip.closeEntry()
        written++
    }
    for ((name, bytes) in layout.documents) {
        context.checkCancelled()
        context.progress(
            written.toFloat() / maxOf(1, total),
            I18n.t("packsA2.packExport.progressWritingDocument", name, written + 1, total),
        )
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
        written++
    }
    if (layout.icon != null) {
        context.checkCancelled()
        zip.putNextEntry(ZipEntry(layout.iconPath))
        zip.write(layout.icon)
        zip.closeEntry()
        written++
    }
    for ((archivePath, file) in layout.payload) {
        context.checkCancelled()
        context.progress(
            written.toFloat() / maxOf(1, total),
            I18n.t("packsA2.packExport.progressExportingFile", file.path, written + 1, total),
        )
        writePayloadEntry(zip, archivePath, file, plan)
        written++
    }
}

/** 复制一个文件进归档，同时校验路径仍在实例目录内、内容与预览一致。 */
private suspend fun writePayloadEntry(
    zip: ZipOutputStream,
    archivePath: String,
    file: MrpackExportFile,
    plan: MrpackExportPlan,
) {
    val source = safePath(plan.root, file.path)
    require(
        Files.isRegularFile(source, NOFOLLOW_LINKS) &&
            source.toRealPath().startsWith(plan.realRoot),
    ) {
        I18n.t("packsA2.packExport.fileMovedPreviewAgain", file.path)
    }
    zip.putNextEntry(ZipEntry(archivePath))
    val hashes = hashExportFile(source) { bytes, count -> zip.write(bytes, 0, count) }
    require(hashes == ExportHashes(file.size, file.sha1, file.sha512)) {
        I18n.t("packsA2.packExport.fileChangedPreviewAgain", file.path)
    }
    zip.closeEntry()
}

private data class ExportHashes(
    val size: Long,
    val sha1: String,
    val sha512: String,
)

private suspend fun hashExportFile(
    path: Path,
    consume: (ByteArray, Int) -> Unit = { _, _ -> },
): ExportHashes {
    val sha1 = MessageDigest.getInstance("SHA-1")
    val sha512 = MessageDigest.getInstance("SHA-512")
    var size = 0L
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(65536)
        while (true) {
            coroutineContext.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            sha1.update(buffer, 0, count)
            sha512.update(buffer, 0, count)
            size += count
            consume(buffer, count)
        }
    }
    return ExportHashes(
        size,
        sha1.digest().joinToString("") { "%02x".format(it) },
        sha512.digest().joinToString("") { "%02x".format(it) },
    )
}
