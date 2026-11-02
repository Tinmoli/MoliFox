// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责整合包扫描、归档写入与内容哈希。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.io.InputStream
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
private const val EXPORT_SCAN_PROGRESS_INTERVAL_NANOS = 150_000_000L

/** 一次导出扫描的共享状态：目录队列、已见名称与收集结果。 */
internal class ExportScanState(
    val store: LauncherStore,
    val instance: GameInstance,
    val root: Path,
    val realRoot: Path,
    val includeLogs: Boolean,
    val hashContents: Boolean = true,
    val hashResources: Boolean = false,
) {
    val warnings: MutableList<String> = mutableListOf()
    val files: MutableList<MrpackExportFile> = mutableListOf()
    val fileStamps: MutableMap<String, ExportFileStamp> = mutableMapOf()
    var selectedEntries: Set<String>? = null
    var excludedPaths: Set<String> = emptySet()
    var context: JobContext? = null
    private var lastProgress = 0L

    fun select(preparation: ExportPreparation?) {
        selectedEntries =
            preparation
                ?.request
                ?.selectedEntries
                ?.map { it.lowercase(Locale.ROOT) }
                ?.toSet()
        excludedPaths = preparation?.request?.excludedPaths.orEmpty()
        context = preparation?.context
    }

    fun reportFile(path: String) {
        val now = System.nanoTime()
        if (now - lastProgress < EXPORT_SCAN_PROGRESS_INTERVAL_NANOS) return
        lastProgress = now
        context?.progress(0f, I18n.t("packsA2.packExport.readingSelected", path))
    }

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
    val top = path.substringBefore('/').lowercase(Locale.ROOT)
    if (
        state.selectedEntries?.let { top !in it } == true ||
        exportPathExcluded(path, state.excludedPaths) || excludedExportPath(path, state.includeLogs)
    ) {
        return
    }
    state.reportFile(path)
    if (instanceOwnExportFile(path, state.instance.versionId)) return
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

/** 选择预览仅记录文件信息；远程匹配只读取三类资源，完整预览仍保留原摘要语义。 */
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
    val stamp = if (state.hashContents) null else ExportFileStamp.read(checked)
    val hashes =
        if (state.hashContents || (state.hashResources && remoteExportResource(path))) {
            hashExportFile(checked)
        } else {
            ExportHashes(requireNotNull(stamp).size, "", "")
        }
    if (stamp != null) state.fileStamps[path] = stamp
    state.files += buildExportFile(path, top, hashes)
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

/** 本地文件只在写入时读一次；需要摘要的清单在内容写完后生成。 */
internal suspend fun writePackEntries(
    zip: ZipOutputStream,
    layout: PackExportLayout,
    plan: MrpackExportPlan,
    context: JobContext,
    format: PackExportFormat,
    options: PackExportOptions,
) {
    val deferred =
        format in setOf(PackExportFormat.Mcbbs, PackExportFormat.Backup) &&
            layout.payload.any { it.second.sha1.isEmpty() }
    val writer = PackArchiveWriter(zip, layout, context)
    writer.writeDirectories()
    if (!deferred) writer.writeDocuments(layout.documents)
    writer.writeIcon()
    val completed =
        writer.writePayload { archivePath, file -> writePayloadEntry(zip, archivePath, file, plan, writer::copied) }
    if (deferred) {
        writer.writeDocuments(packExportContent(format, plan, options, completed, layout.icon != null).documents)
    }
}

/** 复制一个文件进归档，同时校验路径仍在实例目录内、内容与预览一致。 */
private suspend fun writePayloadEntry(
    zip: ZipOutputStream,
    archivePath: String,
    file: MrpackExportFile,
    plan: MrpackExportPlan,
    copied: (Int) -> Unit,
): MrpackExportFile {
    val source = safePath(plan.root, file.path)
    require(
        Files.isRegularFile(source, NOFOLLOW_LINKS) &&
            source.toRealPath().startsWith(plan.realRoot),
    ) {
        I18n.t("packsA2.packExport.fileMovedPreviewAgain", file.path)
    }
    plan.requireUnchangedExportFile(file, source)
    val deferred = file.sha1.isEmpty() && file.sha512.isEmpty() && file.path in plan.fileStamps
    zip.putNextEntry(ZipEntry(archivePath))
    val hashes =
        hashExportFile(source) { bytes, count ->
            zip.write(bytes, 0, count)
            copied(count)
        }
    require(hashes.size == file.size && (deferred || hashes == ExportHashes(file.size, file.sha1, file.sha512))) {
        I18n.t("packsA2.packExport.fileChangedPreviewAgain", file.path)
    }
    plan.requireUnchangedExportFile(file, source)
    zip.closeEntry()
    return file.copy(sha1 = hashes.sha1, sha512 = hashes.sha512)
}

private data class ExportHashes(
    val size: Long,
    val sha1: String,
    val sha512: String,
)

/** 远程来源匹配仅针对模组、资源包与光影，不读取配置/存档/截图内容。 */
internal fun remoteExportResource(path: String): Boolean =
    path.substringBefore('/').lowercase(Locale.ROOT) in setOf("mods", "resourcepacks", "shaderpacks")

private suspend fun hashExportFile(
    path: Path,
    consume: (ByteArray, Int) -> Unit = { _, _ -> },
): ExportHashes {
    val sha1 = MessageDigest.getInstance("SHA-1")
    val sha512 = MessageDigest.getInstance("SHA-512")
    val size =
        try {
            Files.newInputStream(path).use { input -> readExportBytes(input, sha1, sha512, consume) }
        } catch (failure: IOException) {
            coroutineContext.ensureActive()
            throw IOException(
                I18n.t("packsA2.packExport.fileIoFailed", path.fileName, failure.message.orEmpty()),
                failure,
            )
        }
    return ExportHashes(
        size,
        sha1.digest().joinToString("") { "%02x".format(it) },
        sha512.digest().joinToString("") { "%02x".format(it) },
    )
}

private suspend fun readExportBytes(
    input: InputStream,
    sha1: MessageDigest,
    sha512: MessageDigest,
    consume: (ByteArray, Int) -> Unit,
): Long {
    var size = 0L
    val buffer = ByteArray(65536)
    while (true) {
        coroutineContext.ensureActive()
        val count = input.read(buffer)
        if (count < 0) return size
        sha1.update(buffer, 0, count)
        sha512.update(buffer, 0, count)
        size += count
        consume(buffer, count)
    }
}
