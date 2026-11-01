// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 对已知大小和摘要的大文件进行有界分段传输、断点恢复与合并校验。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
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
import okhttp3.ResponseBody
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
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

private const val MAX_SEGMENT_MARKER_BYTES = 2048
private const val HTTP_PARTIAL_CONTENT = 206

private class RangeUnsupported : IOException(I18n.t("networkA.segments.rangeUnsupported"))

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
            runCatching {
                block(response)
            }.getOrElse { failure ->
                // block 是调用方传入的传输 lambda，可能抛出任意运行时异常；这里只做取消优先判定后原样重抛。
                coroutineContext.ensureActive()
                throw failure
            }
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
                marker.fileSize() > MAX_SEGMENT_MARKER_BYTES
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
        return try {
            val supported =
                request(url, "bytes=0-0").use { probe -> matches(probe, 0, 0, artifact.size) }
            if (!supported) {
                false
            } else {
                val folder = prepare(artifact, count)
                val done = existingSegments(artifact, count, folder)
                progress(done.sum(), artifact.size)
                val monitor = Any()
                coroutineScope {
                    (0 until count)
                        .map { index ->
                            async(Dispatchers.IO) {
                                downloadSegment(
                                    artifact,
                                    url,
                                    folder,
                                    index,
                                    count,
                                    done,
                                    monitor,
                                    progress,
                                )
                            }
                        }.awaitAll()
                }
                mergeSegments(artifact, partial, folder, count, progress)
                true
            }
        } catch (_: RangeUnsupported) {
            discard(artifact.target)
            false
        }
    }

    private fun existingSegments(
        artifact: DownloadArtifact,
        count: Int,
        folder: Path,
    ): LongArray {
        val done = LongArray(count)
        for (index in 0 until count) {
            val piece = folder.resolve("$index.part")
            validatePiece(piece)
            val length = artifact.size * (index + 1) / count - artifact.size * index / count
            if (piece.exists() && piece.fileSize() > length) piece.deleteExisting()
            done[index] = if (piece.exists()) piece.fileSize() else 0L
        }
        return done
    }

    private suspend fun mergeSegments(
        artifact: DownloadArtifact,
        partial: Path,
        folder: Path,
        count: Int,
        progress: (Long, Long) -> Unit,
    ) {
        require(!partial.isSymbolicLink()) { I18n.t("networkA.segments.partialSymlink") }
        Files.newOutputStream(partial).use { output ->
            for (index in 0 until count) {
                coroutineContext.ensureActive()
                Files
                    .newInputStream(folder.resolve("$index.part"))
                    .use { it.copyTo(output) }
            }
        }
        val problem = artifactProblem(artifact.copy(target = partial))
        if (problem != null) {
            partial.deleteIfExists()
            discard(artifact.target)
            throw DownloadVerificationException(
                I18n.t(
                    "networkA.segments.verifyFailed",
                    artifact.target.fileName,
                    problem,
                ),
            )
        }
        coroutineContext.ensureActive()
        Files.move(partial, artifact.target, REPLACE_EXISTING)
        discard(artifact.target)
        progress(artifact.size, artifact.size)
    }

    private fun validatePiece(piece: Path) {
        require(
            !piece.isSymbolicLink() &&
                (!piece.exists(NOFOLLOW_LINKS) || piece.isRegularFile(NOFOLLOW_LINKS)),
        ) {
            I18n.t("networkA.segments.invalidPath")
        }
    }

    /** 单个分段的完整传输：请求、校验响应并按块写入缓存文件。 */
    private suspend fun downloadSegment(
        artifact: DownloadArtifact,
        url: String,
        folder: Path,
        index: Int,
        count: Int,
        done: LongArray,
        monitor: Any,
        progress: (Long, Long) -> Unit,
    ) {
        val start = artifact.size * index / count
        val end = artifact.size * (index + 1) / count - 1
        val piece = folder.resolve("$index.part")
        validatePiece(piece)
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
        if (offset == end - start + 1) return
        request(url, "bytes=${start + offset}-$end")
            .readCancellable { response ->
                val body = segmentBody(response, start, offset, end, artifact.size)
                val expected = end - start + 1 - offset
                ensureSegmentLength(body, expected)
                offset =
                    copySegment(body, piece, offset, expected) { value ->
                        offset = value
                        report()
                    }
                if (offset != expected) throw IOException(I18n.t("networkA.segments.transferInterrupted"))
            }
    }

    private fun segmentBody(
        response: Response,
        start: Long,
        offset: Long,
        end: Long,
        total: Long,
    ): ResponseBody {
        if (!matches(response, start + offset, end, total)) throw RangeUnsupported()
        return response.body ?: throw IOException(I18n.t("networkA.segments.emptyBody"))
    }

    private fun ensureSegmentLength(
        body: ResponseBody,
        expected: Long,
    ) {
        if (body.contentLength() >= 0 && body.contentLength() != expected) throw RangeUnsupported()
    }

    private suspend fun copySegment(
        body: ResponseBody,
        piece: Path,
        startOffset: Long,
        expected: Long,
        onProgress: (Long) -> Unit,
    ): Long =
        body.byteStream().use { input ->
            Files.newOutputStream(piece, CREATE, APPEND).use { output ->
                copySegmentBytes(input, output, startOffset, expected, onProgress)
            }
        }

    private fun prepare(
        artifact: DownloadArtifact,
        count: Int,
    ): Path {
        val folder = directory(artifact.target)
        val identity =
            "$MARKER\n${artifact.size}\n${artifact.hashAlgorithm}\n" +
                "${artifact.sha1.lowercase()}\n$count\n"
        require(!folder.isSymbolicLink()) { I18n.t("networkA.segments.folderSymlink") }
        if (folder.exists(NOFOLLOW_LINKS)) {
            val marker = folder.resolve("manifest")
            require(
                folder.isDirectory(NOFOLLOW_LINKS) &&
                    marker.isRegularFile(NOFOLLOW_LINKS) &&
                    !marker.isSymbolicLink() &&
                    marker.fileSize() <= MAX_SEGMENT_MARKER_BYTES &&
                    marker.readText().startsWith("$MARKER\n"),
            ) {
                I18n.t("networkA.segments.folderOccupied")
            }
            if (marker.readText() != identity) discard(artifact.target)
        }
        folder.createDirectories()
        val marker = folder.resolve("manifest")
        if (!marker.exists(NOFOLLOW_LINKS)) {
            marker.writeText(identity, Charsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW)
        }
        require(marker.readText() == identity) { I18n.t("networkA.segments.markerMismatch") }
        return folder
    }

    private fun matches(
        response: Response,
        start: Long,
        end: Long,
        total: Long,
    ): Boolean =
        response.code == HTTP_PARTIAL_CONTENT &&
            response.header("Content-Range") == "bytes $start-$end/$total"
}

private suspend fun copySegmentBytes(
    input: InputStream,
    output: OutputStream,
    startOffset: Long,
    expected: Long,
    onProgress: (Long) -> Unit,
): Long {
    var offset = startOffset
    val buffer = ByteArray(65536)
    while (true) {
        coroutineContext.ensureActive()
        val length = input.read(buffer)
        if (length < 0) break
        if (offset + length > expected) throw RangeUnsupported()
        output.write(buffer, 0, length)
        offset += length
        onProgress(offset)
    }
    return offset
}
