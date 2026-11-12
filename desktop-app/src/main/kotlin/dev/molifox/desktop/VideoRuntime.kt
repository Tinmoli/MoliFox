// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 按需下载并解压 mediamp 的 MPV 运行库；运行库不进安装包，路径固定后不能再换。
package dev.molifox.desktop

import dev.molifox.core.DownloadArtifact
import dev.molifox.core.LauncherController
import dev.molifox.core.i18n.I18n
import dev.molifox.core.rankDownloadSources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.openani.mediamp.mpv.MpvMediampPlayer
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.concurrent.Volatile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

private const val RUNTIME_MARKER = ".molifox-mpv-ready"

internal object VideoRuntime {
    // 本进程已成功加载运行库的目录；再次安装绝不能对它逐文件删除，
    // Windows 下删除已加载的 DLL 会抛 AccessDeniedException 并留下残缺目录。
    @Volatile
    private var loadedDirectory: Path? = null

    fun isInstalled(dataDirectory: Path): Boolean {
        val marker = videoRuntimeDirectory(dataDirectory).resolve(RUNTIME_MARKER)
        if (!marker.isRegularFile()) return false
        return runCatching {
            Files
                .readString(marker)
                .lineSequence()
                .firstOrNull()
                ?.trim() == MPV_RUNTIME_VERSION
        }.getOrDefault(false)
    }

    fun prepareInstalled(dataDirectory: Path): Boolean {
        if (!isInstalled(dataDirectory)) return false
        val prepared =
            runCatching {
                MpvMediampPlayer.prepareLibraries(videoRuntimeDirectory(dataDirectory).toString(), false)
                true
            }.getOrDefault(false)
        if (prepared) loadedDirectory = videoRuntimeDirectory(dataDirectory)
        return prepared
    }

    suspend fun ensure(
        controller: LauncherController,
        progress: (Float) -> Unit = {},
    ): Path =
        withContext(Dispatchers.IO) {
            val directory = videoRuntimeDirectory(controller.dataDirectory)
            // isInstalled 会自己拼出运行库目录，这里必须传数据目录而不是已解析的目录；
            // 否则标记永远查不到，「已安装」快速路径失效，每次确认都重新下载整个运行库。
            if (isInstalled(controller.dataDirectory)) {
                MpvMediampPlayer.prepareLibraries(directory.toString(), false)
                loadedDirectory = directory
                return@withContext directory
            }
            val loaded = loadedDirectory
            if (loaded == directory && directory.exists()) {
                // 标记丢失但本会话正用着这个目录：不重下也不删除，补回标记即可；版本升级留到下次启动重装。
                repairMissingMarker(directory)
                return@withContext directory
            }
            downloadAndInstall(controller, directory, progress)
        }

    /** 标记文件被误删时的最小修复：只补标记，不动本会话正在使用的运行库文件。 */
    private fun repairMissingMarker(directory: Path) {
        if (!directory.resolve(RUNTIME_MARKER).isRegularFile()) {
            runCatching { Files.writeString(directory.resolve(RUNTIME_MARKER), "$MPV_RUNTIME_VERSION\n") }
        }
    }

    /** 全新安装：按设置的下载源排序取包，解压到暂存目录后整体让位替换；调用方已在 IO 线程。 */
    private suspend fun downloadAndInstall(
        controller: LauncherController,
        directory: Path,
        progress: (Float) -> Unit,
    ): Path {
        val asset =
            mpvRuntimeAsset()
                ?: error(I18n.t("settings.appearance.background.videoUnsupported"))
        val cache = controller.dataDirectory.resolve("cache/video").createDirectories()
        val archive = cache.resolve(asset.fileName)
        val urls = controller.network.rankDownloadSources(asset.urls())
        controller.network.downloadFrom(
            DownloadArtifact(
                url = urls.firstOrNull() ?: error(I18n.t("settings.appearance.background.videoDownloadFailed")),
                target = archive,
                sha1 = asset.sha256,
                size = asset.size,
                hashAlgorithm = "SHA-256",
            ),
            urls,
            progress = { done, total ->
                val expected = if (total > 0) total else asset.size
                progress((done.toFloat() / expected.toFloat()).coerceIn(0f, 1f))
            },
        )
        val staging = directory.parent.resolve(".${directory.fileName}-${UUID.randomUUID()}")
        try {
            extractRuntime(archive, staging)
            Files.writeString(staging.resolve(RUNTIME_MARKER), "$MPV_RUNTIME_VERSION\n${asset.platformId}\n")
            replaceDirectory(staging, directory)
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
        MpvMediampPlayer.prepareLibraries(directory.toString(), false)
        loadedDirectory = directory
        progress(1f)
        return directory
    }
}

private fun extractRuntime(
    archive: Path,
    staging: Path,
) {
    staging.createDirectories()
    ZipInputStream(Files.newInputStream(archive)).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            copyRuntimeEntry(entry, zip, staging)
            entry = zip.nextEntry
        }
    }
    require(Files.list(staging).use { it.findAny().isPresent }) {
        I18n.t("settings.appearance.background.videoDownloadFailed")
    }
}

/** 解压单个运行库条目；目录、元数据和越界路径直接跳过，不保留半截文件。 */
private fun copyRuntimeEntry(
    entry: ZipEntry,
    zip: ZipInputStream,
    staging: Path,
) {
    if (!entry.isDirectory) {
        val name = entry.name
        val safeName = !name.startsWith("META-INF/") && !name.contains("..") && !name.startsWith('/')
        if (safeName) {
            val target = staging.resolve(name).normalize()
            if (target.startsWith(staging)) {
                target.parent?.createDirectories()
                Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}

private fun replaceDirectory(
    staging: Path,
    directory: Path,
) {
    directory.parent?.createDirectories()
    if (directory.exists()) {
        // 不逐文件删除旧目录：其中的 DLL 可能仍被本进程占用，逐个删除会在 Windows 上留下残缺目录。
        // 先整体改名让位，改名失败就中止安装并保留旧目录；清理失败只残留旧副本，不影响使用。
        val retired = directory.resolveSibling(".${directory.fileName}-old-${UUID.randomUUID()}")
        Files.move(directory, retired)
        runCatching { retired.deleteRecursively() }
    }
    try {
        Files.move(staging, directory, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: IOException) {
        Files.move(staging, directory)
    }
}

private fun Path.deleteRecursively() {
    if (!Files.exists(this)) return
    Files.walk(this).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
}
