// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 集中资源包与光影的替换及失败复位，确保查询逻辑不直接管理安装事务。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

internal typealias PackUpdateDownloader =
    suspend (candidate: PackUpdateCandidate, target: Path, context: JobContext?) -> Unit

/** `.zip` 含点号的长度；避免在文件名校验里出现裸数字。 */
private const val ZIP_EXTENSION_LENGTH = 4

/** 默认下载器：直接使用候选里的地址与 SHA-512/SHA-1，下载过程中向任务中心汇报进度。 */
internal fun defaultPackUpdateDownloader(network: NetworkClient): PackUpdateDownloader =
    { candidate, target, context ->
        val hasSha512 = candidate.sha512.isNotBlank()
        network.download(
            DownloadArtifact(
                candidate.downloadUrl,
                target,
                sha1 = if (hasSha512) candidate.sha512 else candidate.sha1,
                size = candidate.size,
                hashAlgorithm = if (hasSha512) "SHA-512" else "SHA-1",
            ),
        ) { bytes, total ->
            context?.progress(
                if (total > 0) bytes.toFloat() / total else 0f,
                I18n.t("packsB2.packUpdate.downloading", candidate.filename),
            )
        }
    }

/**
 * 资源包与光影的更新落地。
 *
 * 与模组更新一致：下载到目标目录内的临时 ZIP，校验大小、摘要与 ZIP 结构后才替换旧条目； 旧文件先移到同目录备份，新文件落位失败会原样恢复。文件夹形式的包同样支持，
 * 更新后统一变为 ZIP。
 */
internal class PackUpdateInstaller(
    private val store: LauncherStore,
    private val downloader: PackUpdateDownloader,
) {
    /** 应用一次更新；进度与取消都通过 [context] 汇报，失败时旧文件保持原样。 */
    suspend fun apply(
        instanceId: String,
        item: PackUpdateItem,
        context: JobContext? = null,
    ) = withContext(Dispatchers.IO) {
        val candidate = requireApplicable(item)
        requireSupported(store.instance(instanceId), item, candidate)
        val directory = requireFolderDirectory(instanceId, item.folder)
        val old = safePath(directory, safePackFilename(item.filename))
        requireUnchanged(old, item)
        val newName = requireDownloadName(candidate)
        val target = requireVacantTarget(directory, newName, old)
        val temporary = safePath(directory, ".molifox-update-${UUID.randomUUID()}.zip")
        context?.progress(0f, I18n.t("packsB2.packUpdate.downloading", newName))
        try {
            downloadAndVerify(candidate, temporary, context)
            context?.checkCancelled()
            store.replaceUpdatedFile(directory, old, target, temporary, "packsB2.packUpdate")
            store.log(
                I18n.t(
                    "packsB2.packUpdate.updated",
                    item.folder.title,
                    item.filename,
                    item.currentVersion,
                    candidate.versionNumber,
                ),
            )
            context?.progress(1f, I18n.t("packsB2.packUpdate.done"))
        } finally {
            temporary.deleteIfExists()
        }
    }

    private fun requireApplicable(item: PackUpdateItem): PackUpdateCandidate {
        require(
            item.status == PackUpdateStatus.Updatable && item.candidate != null &&
                item.applicable,
        ) {
            I18n.t("packsB2.packUpdate.noneApplicable")
        }
        return requireNotNull(item.candidate)
    }

    /** 下载前复核候选：资源包必须声明当前游戏版本，光影允许不限版本。 */
    private fun requireSupported(
        instance: GameInstance,
        item: PackUpdateItem,
        candidate: PackUpdateCandidate,
    ) {
        require(packVersionMatches(item.folder, candidate.gameVersions, instance.gameVersion)) {
            I18n.t("packsB2.packUpdate.unsupportedGameVersion")
        }
        require(candidate.downloadable) {
            I18n.t("packsB2.packUpdate.missingDownloadInfo")
        }
    }

    private fun requireFolderDirectory(
        instanceId: String,
        folder: PackFolder,
    ): Path {
        val directory = store.instancePath(instanceId).resolve(folder.directory)
        require(
            directory.isDirectory(LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(directory),
        ) {
            I18n.t("packsB2.packUpdate.folderMissing", folder.title)
        }
        return directory
    }

    /** 确认旧条目仍在且没有在检查后被改动，避免覆盖用户在别处替换过的文件。 */
    private fun requireUnchanged(
        old: Path,
        item: PackUpdateItem,
    ) {
        require(old.exists(LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(old)) {
            I18n.t("packsB2.packUpdate.fileMissing")
        }
        if (item.modified > 0) {
            require(old.getLastModifiedTime().toMillis() == item.modified) {
                I18n.t("packsB2.packUpdate.fileChanged")
            }
        }
        if (item.size > 0 && old.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
            require(old.fileSize() == item.size) { I18n.t("packsB2.packUpdate.fileChanged") }
        }
    }

    private fun requireDownloadName(candidate: PackUpdateCandidate): String {
        val base = safePackFilename(contentBaseFilename(candidate.filename.trim()))
        require(base.endsWith(".zip", true) && base.length > ZIP_EXTENSION_LENGTH) {
            I18n.t("packsB2.packUpdate.notZip")
        }
        return base
    }

    private fun requireVacantTarget(
        directory: Path,
        newName: String,
        old: Path,
    ): Path {
        val target = safePath(directory, newName)
        require(!target.exists(LinkOption.NOFOLLOW_LINKS) || target == old) {
            I18n.t("packsB2.packUpdate.targetExists", newName)
        }
        return target
    }

    private suspend fun downloadAndVerify(
        candidate: PackUpdateCandidate,
        temporary: Path,
        context: JobContext?,
    ) {
        downloader(candidate, temporary, context)
        context?.checkCancelled()
        require(temporary.isRegularFile() && temporary.fileSize() > 0) {
            I18n.t("packsB2.packUpdate.empty")
        }
        require(temporary.fileSize() == candidate.size) { I18n.t("packsB2.packUpdate.sizeMismatch") }
        requireUpdateDigest(temporary, candidate.sha512, candidate.sha1)
        ZipFile(temporary.toFile()).use { zip ->
            require(zip.entries().hasMoreElements()) { I18n.t("packsB2.packUpdate.invalidZip") }
        }
        context?.checkCancelled()
    }
}
