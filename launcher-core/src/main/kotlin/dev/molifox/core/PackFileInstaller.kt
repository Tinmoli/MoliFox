// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责整合包文件下载与实例写入。

package dev.molifox.core

import com.google.gson.JsonObject
import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile

/** All downloads and override layers stay in staging until the whole pack is ready. */
internal suspend fun stagePackFiles(
    manifest: PackManifest,
    zip: ZipFile,
    transaction: PackFileTransaction,
    game: Path,
    optionalFiles: Set<String>?,
    repairing: Boolean,
    context: JobContext,
    progressStart: Float = 0f,
    progressEnd: Float = 1f,
    download: suspend (DownloadArtifact) -> Unit,
) {
    val stager = PackFileStager(context, download, progressStart, progressEnd)
    stager.indexed(manifest, transaction, optionalFiles)
    val prefixes = manifest.overridePrefixes
    val target = PackStagingTarget(transaction, game, repairing)
    stager.checked(manifest, target)
    stager.overrides(manifest, zip, target, prefixes)
}

private data class PackStagingTarget(
    val transaction: PackFileTransaction,
    val game: Path,
    val repairing: Boolean,
)

private class PackFileStager(
    private val context: JobContext,
    private val download: suspend (DownloadArtifact) -> Unit,
    private val progressStart: Float,
    private val progressEnd: Float,
) {
    suspend fun indexed(
        manifest: PackManifest,
        transaction: PackFileTransaction,
        optionalFiles: Set<String>?,
    ) {
        val files =
            manifest.index
                ?.array("files")
                ?.map { it.asJsonObject }
                ?.filter {
                    it.obj("env").string("client") != "unsupported" &&
                        (
                            it.obj("env").string("client") != "optional" ||
                                optionalFiles == null ||
                                it.string("path") in optionalFiles
                        )
                }.orEmpty()
        // 整合包下载是独立阶段：重置任务中心的文件计数，避免沿用原版安装的 1 万多文件。
        context.beginTransfers(files.size)
        val span = progressEnd - progressStart
        for ((position, file) in files.withIndex()) {
            context.checkCancelled()
            val path = file.string("path")
            context.progress(
                progressStart + span * position.toFloat() / maxOf(files.size, 1),
                I18n.t("packsA2.packStaging.progressIndexedFile", position + 1, files.size, path),
            )
            val target = packPath(transaction.content, path)
            val size = downloadIndexFile(file, target)
            context.transfer(target.toString(), size, size, completed = true)
        }
    }

    private suspend fun downloadIndexFile(
        file: JsonObject,
        target: Path,
    ): Long {
        var failure: Exception? = null
        for (url in file.array("downloads")) {
            try {
                val artifact = packDownload(file, target, url.asString)
                download(artifact)
                check(
                    target.isRegularFile() &&
                        target.fileSize() == artifact.size &&
                        digest(target, "SHA-512")
                            .equals(file.obj("hashes").string("sha512"), true) &&
                        digest(target, "SHA-1").equals(file.obj("hashes").string("sha1"), true),
                ) {
                    I18n.t("packsA2.packStaging.sizeOrHashMismatch", file.string("path"))
                }
                failure = null
                return target.fileSize()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                failure = error
            } catch (error: IllegalStateException) {
                failure = error
            } catch (error: IllegalArgumentException) {
                failure = error
            }
        }
        failure?.let { throw it }
        error(I18n.t("packsA2.packStaging.downloadFailed", file.string("path")))
    }

    suspend fun checked(
        manifest: PackManifest,
        destination: PackStagingTarget,
    ) {
        for (file in manifest.checkedFiles.filter { it.url.isNotBlank() }) {
            context.checkCancelled()
            val target = packPath(destination.transaction.content, file.path)
            val actual = packPath(destination.game, file.path)
            if (destination.repairing && actual.exists()) continue
            download(DownloadArtifact(file.url, target, file.hash))
            require(target.isRegularFile() && digest(target).equals(file.hash, true)) {
                I18n.t("packsA2.packStaging.verificationFailed", file.path)
            }
        }
    }

    suspend fun overrides(
        manifest: PackManifest,
        zip: ZipFile,
        destination: PackStagingTarget,
        prefixes: List<String>,
    ) {
        val checks = manifest.checkedFiles.associateBy { it.path }
        for (prefix in prefixes) {
            for (
            (entry, target) in
            validateArchive(zip, prefix, destination.transaction.content)
            ) {
                context.checkCancelled()
                val relative =
                    destination.transaction.content
                        .relativize(target)
                        .joinToString("/")
                // 排除目录与已存在的文件都不覆盖；判断顺序与原先逐个 `continue` 一致。
                val skipped =
                    relative.substringBefore('/').lowercase() in manifest.excludedRoots ||
                        (destination.repairing && packPath(destination.game, relative).exists())
                if (skipped) continue
                target.parent.createDirectories()
                zip.getInputStream(entry).use {
                    Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING)
                }
                checks[relative]?.let { file ->
                    require(digest(target).equals(file.hash, true)) {
                        I18n.t("packsA2.packStaging.verificationFailed", relative)
                    }
                }
            }
        }
    }
}
