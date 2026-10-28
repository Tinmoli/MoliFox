// 负责整合包文件下载与实例写入。

package dev.molifox.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import kotlin.io.path.*
import kotlinx.coroutines.CancellationException

/** All downloads and override layers stay in staging until the whole pack is ready. */
internal suspend fun stagePackFiles(
    manifest: PackManifest,
    zip: ZipFile,
    transaction: PackFileTransaction,
    game: Path,
    optionalFiles: Set<String>?,
    repairing: Boolean,
    context: JobContext,
    download: suspend (DownloadArtifact) -> Unit,
) {
    val files =
        manifest.index
            ?.array("files")
            ?.map { it.asJsonObject }
            ?.filter {
                it.obj("env").string("client") != "unsupported" &&
                    (it.obj("env").string("client") != "optional" ||
                        optionalFiles == null ||
                        it.string("path") in optionalFiles)
            }
            .orEmpty()
    for ((position, file) in files.withIndex()) {
        context.checkCancelled()
        context.progress(
            position.toFloat() / maxOf(files.size, 1),
            "整合包文件 ${position + 1}/${files.size} · ${file.string("path")}",
        )
        val target = packPath(transaction.content, file.string("path"))
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
                        digest(target, "SHA-1").equals(file.obj("hashes").string("sha1"), true)
                ) {
                    "整合包文件大小或双哈希校验失败：${file.string("path")}"
                }
                failure = null
                break
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = error
            }
        }
        failure?.let { throw it }
    }
    val prefixes = manifest.overridePrefixes
    for (file in manifest.checkedFiles.filter { it.url.isNotBlank() }) {
        context.checkCancelled()
        val target = packPath(transaction.content, file.path)
        val actual = packPath(game, file.path)
        if (repairing && actual.exists()) continue
        download(DownloadArtifact(file.url, target, file.hash))
        require(target.isRegularFile() && digest(target).equals(file.hash, true)) {
            "整合包文件校验失败：${file.path}"
        }
    }
    val checks = manifest.checkedFiles.associateBy { it.path }
    for (prefix in prefixes) for ((entry, target) in
        validateArchive(zip, prefix, transaction.content)) {
        context.checkCancelled()
        val relative = transaction.content.relativize(target).joinToString("/")
        if (relative.substringBefore('/').lowercase() in manifest.excludedRoots) continue
        val actual = packPath(game, relative)
        if (repairing && actual.exists()) continue
        target.parent.createDirectories()
        zip.getInputStream(entry).use {
            Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING)
        }
        checks[relative]?.let { file ->
            require(digest(target).equals(file.hash, true)) { "整合包文件校验失败：$relative" }
        }
    }
}
