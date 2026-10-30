// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 对已知大小和摘要的大文件进行有界分段传输、断点恢复与合并校验。

package dev.molifox.core

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Response
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import kotlin.coroutines.coroutineContext
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

private class RangeUnsupported : IOException("下载服务器不支持有效的分段响应")

internal class ResponseCancellation(
    val cancel: () -> Unit,
)

internal suspend fun <T> Response.readCancellable(block: suspend (Response) -> T): T =
    coroutineScope {
        val response = this@readCancellable
        val finished =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        val closer =
            launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    if (!finished.get()) {
                        response.request
                            .tag(ResponseCancellation::class.java)
                            ?.cancel
                            ?.invoke()
                    }
                }
            }
        try {
            block(response)
        } catch (failure: Exception) {
            coroutineContext.ensureActive()
            throw failure
        } finally {
            finished.set(true)
            closer.cancel()
            response.close()
        }
    }

internal class SegmentedDownloader(
    private val request: suspend (String, String) -> Response,
) {
    companion object {
        const val MINIMUM_FILE_SIZE = 8L * 1024 * 1024
        private const val MINIMUM_SEGMENT_SIZE = 4L * 1024 * 1024
        private const val MARKER = "molifox-segments-v1"

        fun directory(target: Path): Path = target.resolveSibling("${target.fileName}.parts")

        fun discard(target: Path) {
            val folder = directory(target)
            if (!folder.isDirectory(NOFOLLOW_LINKS) || folder.isSymbolicLink()) return
            val marker = folder.resolve("manifest")
            if (
                !marker.isRegularFile(NOFOLLOW_LINKS) ||
                marker.isSymbolicLink() ||
                marker.fileSize() > 2048
            ) {
                return
            }
            if (!marker.readText().startsWith("$MARKER\n")) return
            folder
                .listDirectoryEntries()
                .filter {
                    it.fileName.toString().matches(Regex("[0-7]\\.part")) &&
                        !it.isSymbolicLink() &&
                        it.isRegularFile(NOFOLLOW_LINKS)
                }.forEach { it.deleteIfExists() }
            // Unknown files belong to someone else; leave the directory intact.
            if (folder.listDirectoryEntries().all { it == marker }) {
                marker.deleteIfExists()
                folder.deleteIfExists()
            }
        }
    }

    suspend fun download(
        artifact: DownloadArtifact,
        partial: Path,
        url: String,
        segments: Int,
        progress: (Long, Long) -> Unit,
    ): Boolean {
        if (segments < 2 || artifact.size < MINIMUM_FILE_SIZE || artifact.sha1.isBlank()) {
            return false
        }
        val count = minOf(segments.coerceAtMost(8), (artifact.size / MINIMUM_SEGMENT_SIZE).toInt())
        try {
            request(url, "bytes=0-0").use { probe ->
                if (!matches(probe, 0, 0, artifact.size)) return false
            }
            val folder = prepare(artifact, count)
            val done = LongArray(count)
            for (index in 0 until count) {
                val piece = folder.resolve("$index.part")
                require(
                    !piece.isSymbolicLink() &&
                        (!piece.exists(NOFOLLOW_LINKS) || piece.isRegularFile(NOFOLLOW_LINKS)),
                ) {
                    "分段缓存路径无效"
                }
                val length = artifact.size * (index + 1) / count - artifact.size * index / count
                if (piece.exists() && piece.fileSize() > length) piece.deleteExisting()
                done[index] = if (piece.exists()) piece.fileSize() else 0L
            }
            progress(done.sum(), artifact.size)
            val monitor = Any()
            coroutineScope {
                (0 until count)
                    .map { index ->
                        async(Dispatchers.IO) {
                            val start = artifact.size * index / count
                            val end = artifact.size * (index + 1) / count - 1
                            val piece = folder.resolve("$index.part")
                            require(
                                !piece.isSymbolicLink() &&
                                    (
                                        !piece.exists(NOFOLLOW_LINKS) ||
                                            piece.isRegularFile(NOFOLLOW_LINKS)
                                    ),
                            ) {
                                "分段缓存路径无效"
                            }
                            if (piece.exists() && piece.fileSize() > end - start + 1) {
                                piece.deleteExisting()
                            }
                            var offset = done[index]

                            fun report() =
                                synchronized(monitor) {
                                    done[index] = offset
                                    progress(done.sum(), artifact.size)
                                }
                            report()
                            if (offset == end - start + 1) return@async
                            request(url, "bytes=${start + offset}-$end")
                                .readCancellable { response ->
                                    if (!matches(response, start + offset, end, artifact.size)) {
                                        throw RangeUnsupported()
                                    }
                                    val body = response.body ?: throw IOException("分段响应为空")
                                    val expected = end - start + 1 - offset
                                    if (body.contentLength() >= 0 &&
                                        body.contentLength() != expected
                                    ) {
                                        throw RangeUnsupported()
                                    }
                                    body.byteStream().use { input ->
                                        Files.newOutputStream(piece, CREATE, APPEND).use { output ->
                                            val buffer = ByteArray(65536)
                                            while (true) {
                                                coroutineContext.ensureActive()
                                                val length = input.read(buffer)
                                                if (length < 0) break
                                                if (offset + length > end - start + 1) {
                                                    throw RangeUnsupported()
                                                }
                                                output.write(buffer, 0, length)
                                                offset += length
                                                report()
                                            }
                                        }
                                    }
                                    if (offset != end - start + 1) throw IOException("分段传输中断")
                                }
                        }
                    }.awaitAll()
            }
            require(!partial.isSymbolicLink()) { "下载缓存不能是链接" }
            Files.newOutputStream(partial).use { output ->
                for (index in 0 until count) {
                    coroutineContext.ensureActive()
                    Files.newInputStream(folder.resolve("$index.part")).use { it.copyTo(output) }
                }
            }
            val problem = artifactProblem(artifact.copy(target = partial))
            if (problem != null) {
                partial.deleteIfExists()
                discard(artifact.target)
                throw DownloadVerificationException(
                    "分段文件校验失败：${artifact.target.fileName} · $problem",
                )
            }
            coroutineContext.ensureActive()
            Files.move(partial, artifact.target, REPLACE_EXISTING)
            discard(artifact.target)
            progress(artifact.size, artifact.size)
            return true
        } catch (_: RangeUnsupported) {
            discard(artifact.target)
            return false
        }
    }

    private fun prepare(
        artifact: DownloadArtifact,
        count: Int,
    ): Path {
        val folder = directory(artifact.target)
        val identity =
            "$MARKER\n${artifact.size}\n${artifact.hashAlgorithm}\n${artifact.sha1.lowercase()}\n$count\n"
        require(!folder.isSymbolicLink()) { "分段缓存不能是链接" }
        if (folder.exists(NOFOLLOW_LINKS)) {
            val marker = folder.resolve("manifest")
            require(
                folder.isDirectory(NOFOLLOW_LINKS) &&
                    marker.isRegularFile(NOFOLLOW_LINKS) &&
                    !marker.isSymbolicLink() &&
                    marker.fileSize() <= 2048 &&
                    marker.readText().startsWith("$MARKER\n"),
            ) {
                "分段缓存目录已被其他文件占用"
            }
            if (marker.readText() != identity) discard(artifact.target)
        }
        folder.createDirectories()
        val marker = folder.resolve("manifest")
        if (!marker.exists(NOFOLLOW_LINKS)) {
            marker.writeText(identity, Charsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW)
        }
        require(marker.readText() == identity) { "分段缓存与目标文件不匹配" }
        return folder
    }

    private fun matches(
        response: Response,
        start: Long,
        end: Long,
        total: Long,
    ): Boolean =
        response.code == 206 && response.header("Content-Range") == "bytes $start-$end/$total"
}
