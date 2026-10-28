// 负责验证网络请求、下载校验、重试和断点续传的行为与边界。

package dev.molifox.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

internal fun sha1(bytes: ByteArray) =
    MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

/** 可复现的测试内容：同一 [size] 每次得到同样的字节。 */
private fun payload(size: Int, seed: Int = 7) =
    ByteArray(size) { index -> ((index * 31 + seed) % 251).toByte() }

/** 测试数据放在项目内 `.work/tests/` 下，结束后（含失败）删除。 */
private fun workDirectory(name: String): Path {
    var root = Path.of(System.getProperty("user.dir")).toAbsolutePath()
    while (root.parent != null && !root.resolve("settings.gradle.kts").isRegularFile()) root =
        root.parent
    val directory = root.resolve(".work/tests/launcher-core-download/$name")
    removeTree(directory)
    directory.createDirectories()
    return directory
}

private fun removeTree(root: Path) {
    if (!root.exists()) return
    Files.walk(root).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
    }
}

private suspend fun <T> withWorkDirectory(name: String, block: suspend (Path) -> T): T {
    val directory = workDirectory(name)
    try {
        return block(directory)
    } finally {
        removeTree(directory)
        // 本次运行的数据全部删掉，父目录空了也一并清掉（测试按顺序执行）。
        runCatching {
            directory.parent
                ?.takeIf { it.isDirectory() && it.listDirectoryEntries().isEmpty() }
                ?.deleteIfExists()
        }
            .getOrNull()
    }
}

private fun artifact(url: String, target: Path, content: ByteArray) =
    DownloadArtifact(url, target, sha1(content), content.size.toLong())

/**
 * 支持 `Range: bytes=N-` 的本地服务：无 Range 回 200 全量，有 Range 回 206 剩余部分， 起点越界回 416。记录每个请求的 Range
 * 头，用于证明续传确实发生。
 */
private class RangedFiles(private val content: ByteArray) : Dispatcher() {
    val requests = AtomicInteger()
    val ranges = mutableListOf<String?>()

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests.incrementAndGet()
        val range = request.getHeader("Range")
        ranges += range
        if (range == null) return full()
        val size = content.size.toLong()
        val start = range.removePrefix("bytes=").removeSuffix("-").toLongOrNull() ?: return full()
        if (start >= size) return MockResponse().setResponseCode(416)
        return MockResponse()
            .setResponseCode(206)
            .setHeader("Content-Range", "bytes $start-${size - 1}/$size")
            .setBody(Buffer().write(content.copyOfRange(start.toInt(), content.size)))
    }

    fun full() =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Accept-Ranges", "bytes")
            .setBody(Buffer().write(content))
}

class NetworkClientTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `authentication denial exposes status without disclosing response credentials`(): Unit =
        runBlocking {
            MockWebServer().use { server ->
                val client = NetworkClient()
                try {
                    server.enqueue(
                        MockResponse()
                            .setResponseCode(403)
                            .setBody("{\"access_token\":\"sensitive-response\"}")
                    )
                    val failure =
                        assertFailsWith<ServiceHttpException> {
                            client.post(
                                server.loopbackUrl("/login").toString(),
                                com.google.gson.JsonObject(),
                            )
                        }
                    assertEquals(403, failure.statusCode)
                    assertFalse(failure.message.orEmpty().contains("sensitive-response"))
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun `corrupted downloads retry and only verified content replaces destination`() = runBlocking {
        MockWebServer().use { server ->
            val client = NetworkClient()
            try {
                val target = directory.resolve("file.jar")
                target.writeText("existing")
                server.enqueue(MockResponse().setBody("corrupt"))
                server.enqueue(MockResponse().setBody("verified"))
                client.download(
                    DownloadArtifact(
                        server.loopbackUrl("/file").toString(),
                        target,
                        sha1("verified".toByteArray()),
                    )
                )
                assertEquals("verified", target.readText())
                assertEquals(2, server.requestCount)
                assertTrue(directory.listDirectoryEntries().none { it.name.endsWith(".part") })
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `failure preserves existing data and removes partial artifacts`() = runBlocking {
        MockWebServer().use { server ->
            val client = NetworkClient()
            try {
                val target = directory.resolve("file.jar")
                target.writeText("existing")
                repeat(3) { server.enqueue(MockResponse().setBody("corrupt")) }
                assertFails {
                    client.download(
                        DownloadArtifact(
                            server.loopbackUrl("/file").toString(),
                            target,
                            sha1("verified".toByteArray()),
                        )
                    )
                }
                assertEquals("existing", target.readText())
                assertTrue(directory.listDirectoryEntries().none { it.name.endsWith(".part") })
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `cancelled download does not leave an installable partial file`() = runBlocking {
        MockWebServer().use { server ->
            val client = NetworkClient()
            try {
                server.enqueue(
                    MockResponse()
                        .setBody("x".repeat(100000))
                        .throttleBody(1024, 100, TimeUnit.MILLISECONDS)
                )
                val target = directory.resolve("file.jar")
                val download =
                    launch(Dispatchers.IO) {
                        client.download(
                            DownloadArtifact(server.loopbackUrl("/slow").toString(), target)
                        )
                    }
                // 起取消要保留 `<目标>.part` 才能续传；断点不含最终文件名，目标仍不可安装。
                val partial = client.partialFile(target)
                withTimeout(20_000) {
                    while (!partial.isRegularFile() || partial.fileSize() == 0L) delay(20)
                }
                download.cancelAndJoin()
                assertFalse(target.exists())
                assertTrue(partial.isRegularFile(), "取消后必须保留断点文件")
                assertTrue(partial.fileSize() > 0L, "断点文件应包含已下载的字节")
            } finally {
                client.close()
            }
        }
    }

    // ---- ：断点续传、校验、重试与取消 ----

    @Test
    fun `verified download leaves no partial file behind`() = runBlocking {
        val content = payload(120_000)
        withWorkDirectory("fresh") { work ->
            MockWebServer().use { server ->
                val files = RangedFiles(content)
                server.dispatcher = files
                val client = NetworkClient()
                try {
                    val target = work.resolve("mods/core.jar")
                    val progress = mutableListOf<Pair<Long, Long>>()
                    val download =
                        artifact(server.loopbackUrl("/core.jar").toString(), target, content)
                    client.download(download) { done, total -> progress += done to total }
                    assertContentEquals(content, target.readBytes())
                    assertFalse(client.partialFile(target).exists())
                    assertTrue(work.listDirectoryEntries().none { it.name.endsWith(".part") })
                    assertEquals(1, files.requests.get())
                    assertEquals(content.size.toLong() to content.size.toLong(), progress.last())
                } finally {
                    client.close()
                }
            }
        }
    }

    @Test
    fun `interrupted transfer keeps partial bytes for resume`() = runBlocking {
        val content = payload(200_000)
        withWorkDirectory("interrupted") { work ->
            MockWebServer().use { server ->
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest) =
                            MockResponse()
                                .setResponseCode(200)
                                .setHeader("Accept-Ranges", "bytes")
                                .setBody(Buffer().write(content))
                                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                                .throttleBody(200_000, 20, TimeUnit.MILLISECONDS)
                    }
                val client = NetworkClient()
                try {
                    val target = work.resolve("core.jar")
                    assertFailsWith<IOException> {
                        client.download(
                            artifact(server.loopbackUrl("/core.jar").toString(), target, content),
                            attempts = 1,
                        )
                    }
                    val partial = client.partialFile(target)
                    assertTrue(partial.isRegularFile(), "服务端断流后必须保留断点")
                    assertTrue(
                        partial.fileSize() in 1 until content.size.toLong(),
                        "断点应只有前半段：${partial.fileSize()}",
                    )
                    assertFalse(target.exists())
                } finally {
                    client.close()
                }
            }
        }
    }

    @Test
    fun `resumed download sends Range and matches the original hash`() = runBlocking {
        val content = payload(200_000)
        withWorkDirectory("resume") { work ->
            MockWebServer().use { server ->
                val target = work.resolve("core.jar")
                val client = NetworkClient()
                try {
                    server.dispatcher =
                        object : Dispatcher() {
                            override fun dispatch(request: RecordedRequest) =
                                MockResponse()
                                    .setResponseCode(200)
                                    .setHeader("Accept-Ranges", "bytes")
                                    .setBody(Buffer().write(content))
                                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                                    .throttleBody(200_000, 20, TimeUnit.MILLISECONDS)
                        }
                    assertFailsWith<IOException> {
                        client.download(
                            artifact(server.loopbackUrl("/core.jar").toString(), target, content),
                            attempts = 1,
                        )
                    }
                    val partial = client.partialFile(target)
                    val received = partial.fileSize()
                    assertTrue(received > 0L)

                    val files = RangedFiles(content)
                    server.dispatcher = files
                    val resumed =
                        artifact(server.loopbackUrl("/core.jar").toString(), target, content)
                    client.download(resumed)
                    assertContentEquals(content, target.readBytes())
                    assertEquals(sha1(content), sha1(target.readBytes()))
                    assertEquals("bytes=$received-", files.ranges.first())
                    assertTrue(files.ranges.all { it == "bytes=$received-" }, "续传请求都应带上 Range")
                    assertTrue(files.requests.get() >= 1)
                    assertFalse(partial.exists())
                } finally {
                    client.close()
                }
            }
        }
    }

    @Test
    fun `server ignoring Range restarts from scratch instead of appending`() = runBlocking {
        val content = payload(90_000, seed = 3)
        withWorkDirectory("no-range") { work ->
            MockWebServer().use { server ->
                val ranges = mutableListOf<String?>()
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            ranges += request.getHeader("Range")
                            // 故意忽略 Range：总是回 200 全量。
                            return MockResponse()
                                .setResponseCode(200)
                                .setBody(Buffer().write(content))
                        }
                    }
                val client = NetworkClient()
                try {
                    val target = work.resolve("libraries/a.jar")
                    target.parent.createDirectories()
                    // 预先留下一段错误字节，模拟上次被截断的断点。
                    client.partialFile(target).writeBytes(ByteArray(5_000) { 0x7F.toByte() })
                    client.download(
                        artifact(server.loopbackUrl("/a.jar").toString(), target, content),
                        attempts = 1,
                    )
                    assertContentEquals(content, target.readBytes())
                    assertEquals("bytes=5000-", ranges.single())
                    assertFalse(client.partialFile(target).exists())
                } finally {
                    client.close()
                }
            }
        }
    }

    @Test
    fun `stale partial rejected with 416 restarts download`() = runBlocking {
        val content = payload(100, seed = 11)
        withWorkDirectory("range-416") { work ->
            MockWebServer().use { server ->
                val files = RangedFiles(content)
                server.dispatcher = files
                val client = NetworkClient()
                try {
                    val target = work.resolve("assets/obj")
                    target.parent.createDirectories()
                    client.partialFile(target).writeBytes(ByteArray(900) { 1.toByte() })
                    // 没有声明长度与哈希，416 之后必须自己重新开始，而不是拼接。
                    client.download(
                        DownloadArtifact(server.loopbackUrl("/obj").toString(), target),
                        attempts = 1,
                    )
                    assertContentEquals(content, target.readBytes())
                    assertEquals(listOf("bytes=900-", null), files.ranges)
                    assertEquals(2, files.requests.get())
                    assertFalse(client.partialFile(target).exists())
                } finally {
                    client.close()
                }
            }
        }
    }

    @Test
    fun `size mismatch removes the partial and reports a chinese error`() = runBlocking {
        val content = payload(50)
        withWorkDirectory("size-mismatch") { work ->
            MockWebServer().use { server ->
                server.dispatcher = RangedFiles(content)
                val client = NetworkClient()
                try {
                    val target = work.resolve("core.jar")
                    val failure =
                        assertFailsWith<DownloadVerificationException> {
                            client.download(
                                DownloadArtifact(
                                    server.loopbackUrl("/core.jar").toString(),
                                    target,
                                    sha1(content),
                                    content.size + 50L,
                                ),
                                attempts = 1,
                            )
                        }
                    assertTrue(failure.message.orEmpty().contains("文件大小校验失败"))
                    assertTrue(failure.message.orEmpty().contains("core.jar"))
                    assertFalse(client.partialFile(target).exists())
                    assertFalse(target.exists())
                } finally {
                    client.close()
                }
            }
        }
    }

    @Test
    fun `hash mismatch removes the partial and reports a chinese error`() = runBlocking {
        val content = payload(4096, seed = 5)
        withWorkDirectory("hash-mismatch") { work ->
            MockWebServer().use { server ->
                server.dispatcher = RangedFiles(content)
                val client = NetworkClient()
                try {
                    val target = work.resolve("core.jar")
                    val failure =
                        assertFailsWith<DownloadVerificationException> {
                            client.download(
                                DownloadArtifact(
                                    server.loopbackUrl("/core.jar").toString(),
                                    target,
                                    sha1("something else".toByteArray()),
                                ),
                                attempts = 1,
                            )
                        }
                    assertTrue(failure.message.orEmpty().contains("文件校验失败"))
                    assertTrue(failure.message.orEmpty().contains("core.jar"))
                    assertFalse(client.partialFile(target).exists())
                    assertFalse(target.exists())
                } finally {
                    client.close()
                }
            }
        }
    }

    @Test
    fun `server errors retry with backoff and then succeed`() = runBlocking {
        val content = payload(2048, seed = 13)
        withWorkDirectory("retry") { work ->
            MockWebServer().use { server ->
                val count = AtomicInteger()
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            val attempt = count.incrementAndGet()
                            if (attempt <= 2) return MockResponse().setResponseCode(503)
                            return MockResponse()
                                .setResponseCode(200)
                                .setBody(Buffer().write(content))
                        }
                    }
                val client = NetworkClient()
                try {
                    val target = work.resolve("core.jar")
                    val started = System.nanoTime()
                    client.download(
                        artifact(server.loopbackUrl("/core.jar").toString(), target, content)
                    )
                    val elapsedMs = (System.nanoTime() - started) / 1_000_000
                    assertContentEquals(content, target.readBytes())
                    assertEquals(3, count.get())
                    assertTrue(elapsedMs >= 1_400, "应至少退避 500+1000 毫秒，实际 $elapsedMs")
                } finally {
                    client.close()
                }
            }
        }
    }

    @Test
    fun `retry exhaustion fails without leaving an unusable file`() = runBlocking {
        withWorkDirectory("exhausted") { work ->
            MockWebServer().use { server ->
                val count = AtomicInteger()
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            count.incrementAndGet()
                            return MockResponse().setResponseCode(503)
                        }
                    }
                val client = NetworkClient()
                try {
                    val target = work.resolve("core.jar")
                    val failure =
                        assertFailsWith<DownloadHttpException> {
                            client.download(
                                DownloadArtifact(server.loopbackUrl("/core.jar").toString(), target)
                            )
                        }
                    assertEquals(503, failure.code)
                    assertTrue(failure.message.orEmpty().contains("下载失败 (503)"))
                    assertEquals(NetworkClient.DEFAULT_DOWNLOAD_ATTEMPTS, count.get())
                    assertFalse(target.exists())
                    // 5xx 从未写入字节，因此不会凭空出现断点；已存在的断点只在网络失败时保留。
                    assertFalse(client.partialFile(target).exists())
                } finally {
                    client.close()
                }
            }
        }
    }

    @Test
    fun `cancellation keeps the partial and later failures keep it too`() = runBlocking {
        val content = payload(400_000, seed = 17)
        withWorkDirectory("cancel") { work ->
            MockWebServer().use { server ->
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest) =
                            MockResponse()
                                .setResponseCode(200)
                                .setBody(Buffer().write(content))
                                .throttleBody(4096, 40, TimeUnit.MILLISECONDS)
                    }
                val client = NetworkClient()
                try {
                    val target = work.resolve("core.jar")
                    val partial = client.partialFile(target)
                    val job =
                        launch(Dispatchers.IO) {
                            client.download(
                                artifact(
                                    server.loopbackUrl("/core.jar").toString(),
                                    target,
                                    content,
                                )
                            )
                        }
                    withTimeout(30_000) {
                        while (!partial.isRegularFile() || partial.fileSize() < 4096L) delay(20)
                    }
                    job.cancelAndJoin()
                    assertFalse(target.exists())
                    assertTrue(partial.isRegularFile(), "取消必须保留断点")
                    val preserved = partial.fileSize()
                    assertTrue(preserved >= 4096L)

                    // 之后的网络失败也不能删掉断点（只有校验失败和 discardPartial 才删）。
                    server.dispatcher =
                        object : Dispatcher() {
                            override fun dispatch(request: RecordedRequest) =
                                MockResponse().setResponseCode(503)
                        }
                    assertFailsWith<DownloadHttpException> {
                        client.download(
                            artifact(server.loopbackUrl("/core.jar").toString(), target, content),
                            attempts = 1,
                        )
                    }
                    assertTrue(partial.isRegularFile(), "网络失败不得删除断点")
                    assertEquals(preserved, partial.fileSize())
                    assertFalse(target.exists())
                } finally {
                    client.close()
                }
            }
        }
    }

    @Test
    fun `resumed progress starts at the existing offset and keeps the full total`() = runBlocking {
        val content = payload(150_000, seed = 19)
        withWorkDirectory("progress") { work ->
            MockWebServer().use { server ->
                val files = RangedFiles(content)
                server.dispatcher = files
                val client = NetworkClient()
                try {
                    val target = work.resolve("core.jar")
                    val prefix = 40_000
                    client.partialFile(target).writeBytes(content.copyOfRange(0, prefix))
                    val progress = mutableListOf<Pair<Long, Long>>()
                    val download =
                        artifact(server.loopbackUrl("/core.jar").toString(), target, content)
                    client.download(download) { done, total -> progress += done to total }
                    assertTrue(progress.isNotEmpty())
                    assertTrue(
                        progress.first().first > prefix,
                        "续传首帧应从已有偏移继续：${progress.first()}",
                    )
                    assertTrue(progress.all { it.second == content.size.toLong() }, "total 应是完整大小")
                    assertEquals(content.size.toLong() to content.size.toLong(), progress.last())
                    assertTrue(progress.map { it.first }.zipWithNext().all { (a, b) -> b >= a })
                    assertEquals("bytes=$prefix-", files.ranges.single())
                    assertContentEquals(content, target.readBytes())
                    assertFalse(client.partialFile(target).exists())
                } finally {
                    client.close()
                }
            }
        }
    }

    @Test
    fun `discardPartial removes only the partial artifact`() = runBlocking {
        withWorkDirectory("discard") { work ->
            val client = NetworkClient()
            try {
                val target = work.resolve("mods/core.jar")
                target.parent.createDirectories()
                target.writeText("installed")
                client.partialFile(target).writeText("half")
                client.discardPartial(target)
                assertFalse(client.partialFile(target).exists())
                assertEquals("installed", target.readText())
                // 幂等：没有断点时也不报错。
                client.discardPartial(target)
                assertFalse(client.partialFile(target).exists())
            } finally {
                client.close()
            }
        }
    }
}
