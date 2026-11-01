// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val TEST_SEGMENT_BYTES = 4L * 1024 * 1024
private const val RESUMED_SEGMENT_BYTES = 64 * 1024
private const val TEST_PARTIAL_RESPONSE = 206
private const val SHA1_CHARACTERS = 40

class SegmentedResumeTest {
    @TempDir lateinit var temporary: Path
    private val zeroChunk = ByteArray(RESUMED_SEGMENT_BYTES)

    private fun zeroHash(size: Long): String {
        val hash = MessageDigest.getInstance("SHA-1")
        var remaining = size
        while (remaining > 0) {
            val length = minOf(remaining, zeroChunk.size.toLong()).toInt()
            hash.update(zeroChunk, 0, length)
            remaining -= length
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    private fun artifact(segments: Int): DownloadArtifact {
        val size = TEST_SEGMENT_BYTES * segments
        val target = temporary.resolve("large.bin")
        return DownloadArtifact("https://example.invalid/large", target, zeroHash(size), size)
    }

    /** 使用有界零字节源，验证 64 MiB / 16 段时不在内存保留整个测试文件。 */
    private fun zeroBody(size: Long): ResponseBody = object : ResponseBody() {
        private val stream = object : Source {
            private var remaining = size

            override fun read(sink: Buffer, byteCount: Long): Long {
                if (byteCount == 0L) return 0
                if (remaining == 0L) return -1
                val count = minOf(remaining, byteCount, zeroChunk.size.toLong()).toInt()
                sink.write(zeroChunk, 0, count)
                remaining -= count
                return count.toLong()
            }

            override fun timeout(): Timeout = Timeout.NONE

            override fun close() = Unit
        }.buffer()

        override fun contentType(): MediaType? = null

        override fun contentLength(): Long = size

        override fun source(): BufferedSource = stream
    }

    private fun response(range: String, total: Long): Response {
        val (start, end) = range.removePrefix("bytes=").split('-').map(String::toLong)
        return Response.Builder()
            .request(Request.Builder().url("https://example.invalid/large").build())
            .protocol(Protocol.HTTP_1_1)
            .code(TEST_PARTIAL_RESPONSE)
            .message("fixture")
            .header("Content-Range", "bytes $start-$end/$total")
            .body(zeroBody(end - start + 1))
            .build()
    }

    private fun seed(artifact: DownloadArtifact, count: Int): Path {
        val folder = SegmentedDownloader.directory(artifact.target).createDirectories()
        folder.resolve("manifest").writeText(
            "molifox-segments-v1\n${artifact.size}\n${artifact.hashAlgorithm}\n${artifact.sha1}\n$count\n",
        )
        return folder
    }

    @Test
    fun `sixteen configured segments cover the file once and merge with a valid hash`() = runBlocking {
        val artifact = artifact(MAX_DOWNLOAD_SEGMENTS)
        val ranges = CopyOnWriteArrayList<String>()
        val downloader = SegmentedDownloader { _, range ->
            ranges += range
            response(range, artifact.size)
        }
        val partial = artifact.target.resolveSibling("large.part")
        val completed = downloader.download(artifact, partial, artifact.url, MAX_DOWNLOAD_SEGMENTS) { _, _ -> }
        assertTrue(completed)
        assertEquals(MAX_DOWNLOAD_SEGMENTS + 1, ranges.size)
        assertEquals("bytes=0-0", ranges.first())
        assertEquals(MAX_DOWNLOAD_SEGMENTS, ranges.drop(1).distinct().size)
        assertEquals(artifact.size, artifact.target.fileSize())
        assertEquals(artifact.sha1, digest(artifact.target))
        assertFalse(SegmentedDownloader.directory(artifact.target).exists())
    }

    @Test
    fun `partially downloaded segments resume from their own offsets`() = runBlocking {
        val artifact = artifact(2)
        val folder = seed(artifact, 2)
        repeat(2) { folder.resolve("$it.part").writeBytes(zeroChunk) }
        val ranges = CopyOnWriteArrayList<String>()
        val downloader = SegmentedDownloader { _, range ->
            ranges += range
            response(range, artifact.size)
        }
        assertTrue(downloader.download(artifact, temporary.resolve("resume.part"), artifact.url, 2) { _, _ -> })
        assertTrue("bytes=$RESUMED_SEGMENT_BYTES-${TEST_SEGMENT_BYTES - 1}" in ranges)
        assertTrue("bytes=${TEST_SEGMENT_BYTES + RESUMED_SEGMENT_BYTES}-${artifact.size - 1}" in ranges)
        assertEquals(artifact.sha1, digest(artifact.target))
    }

    @Test
    fun `hash failure preserves the existing target and removes owned segments`() = runBlocking {
        val artifact = artifact(2).copy(sha1 = "a".repeat(SHA1_CHARACTERS))
        artifact.target.writeText("keep")
        val partial = temporary.resolve("verify.part")
        val downloader = SegmentedDownloader { _, range -> response(range, artifact.size) }
        assertFailsWith<DownloadVerificationException> {
            downloader.download(artifact, partial, artifact.url, 2) { _, _ -> }
        }
        assertEquals("keep", artifact.target.readText())
        assertFalse(partial.exists())
        assertFalse(SegmentedDownloader.directory(artifact.target).exists())
    }

    @Test
    fun `cleanup removes only indices owned by the recorded segment count`() {
        for (count in listOf(2, MAX_DOWNLOAD_SEGMENTS)) {
            val artifact = artifact(count).copy(target = temporary.resolve("cleanup-$count"))
            val folder = seed(artifact, count)
            repeat(count) { folder.resolve("$it.part").writeBytes(byteArrayOf(0)) }
            val unknown = folder.resolve("$count.part").apply { writeText("user fixture") }
            SegmentedDownloader.discard(artifact.target)
            repeat(count) { assertFalse(folder.resolve("$it.part").exists()) }
            assertEquals("user fixture", unknown.readText())
            assertTrue(folder.resolve("manifest").exists())
        }
    }
}
