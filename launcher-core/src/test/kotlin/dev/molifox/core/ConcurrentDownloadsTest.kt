// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验证任务并行、同实例排队、取消文件清理和 HTTP 分段下载的真实行为。

package dev.molifox.core

import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ConcurrentDownloadsTest {
    @TempDir lateinit var directory: Path

    private suspend fun eventually(condition: () -> Boolean) =
        withTimeout(10_000) {
            while (!condition()) delay(10)
        }

    @Test
    fun `independent tasks start while a conflicting task waits and cancelled reservations release`() =
        runBlocking {
            val store = LauncherStore(DesktopPlatform(), directory)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val queue = JobQueue(store, scope)
                val hold = CompletableDeferred<Unit>()
                val started = CompletableDeferred<Unit>()
                val first =
                    queue.enqueue("A", setOf("instance:A")) {
                        started.complete(Unit)
                        hold.await()
                    }
                started.await()
                val second =
                    queue.enqueue("A cancelled", setOf("instance:A")) { error("must not run") }
                val third = queue.enqueue("B", setOf("instance:B")) {}
                eventually {
                    store.state.value.jobs.any {
                        it.id == third && it.status == JobStatus.Completed
                    }
                }
                assertFalse(queue.globallyBusy)
                assertTrue(queue.conflicts(setOf("instance:A")))
                queue.cancel(second)
                hold.complete(Unit)
                withTimeout(10_000) { queue.awaitIdle() }
                val fourth = queue.enqueue("A again", setOf("instance:A")) {}
                withTimeout(10_000) { queue.awaitIdle() }
                assertEquals(
                    JobStatus.Completed,
                    store.state.value.jobs.first { it.id == first }.status,
                )
                assertEquals(
                    JobStatus.Cancelled,
                    store.state.value.jobs.first { it.id == second }.status,
                )
                assertEquals(
                    JobStatus.Completed,
                    store.state.value.jobs.first { it.id == fourth }.status,
                )
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `cancellation removes new completed files and keeps existing or borrowed files`() =
        runBlocking {
            val store = LauncherStore(DesktopPlatform(), directory.resolve("profile"))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            MockWebServer().use { server ->
                NetworkClient().use { network ->
                    try {
                        repeat(3) { server.enqueue(MockResponse().setBody("abc")) }
                        val queue = JobQueue(store, scope)
                        val own = directory.resolve("own.bin")
                        val borrowed = directory.resolve("shared.bin")
                        val existing = directory.resolve("existing.bin").apply { writeText("abc") }
                        val ready = CompletableDeferred<Unit>()
                        val job =
                            queue.enqueue("Download", setOf("A")) {
                                for (target in listOf(own, borrowed, existing)) network.download(
                                    DownloadArtifact(
                                        server.loopbackUrl("/file").toString(),
                                        target,
                                        size = 3,
                                    )
                                )
                                ready.complete(Unit)
                                awaitCancellation()
                            }
                        ready.await()
                        val consumer =
                            queue.enqueue("Use shared", setOf("B")) {
                                network.download(
                                    DownloadArtifact(
                                        server.loopbackUrl("/file").toString(),
                                        borrowed,
                                        size = 3,
                                    )
                                )
                            }
                        eventually {
                            store.state.value.jobs.first { it.id == consumer }.status ==
                                JobStatus.Completed
                        }
                        queue.cancel(job)
                        queue.awaitIdle()
                        assertFalse(own.exists())
                        assertEquals("abc", existing.readText())
                        assertEquals("abc", borrowed.readText())
                    } finally {
                        scope.cancel()
                    }
                }
            }
        }

    private fun artifact(server: MockWebServer, target: Path, bytes: ByteArray): DownloadArtifact =
        DownloadArtifact(
            server.loopbackUrl("/large").toString(),
            target,
            MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) },
            bytes.size.toLong(),
        )

    private fun ranges(
        bytes: ByteArray,
        ignore: Boolean = false,
        slow: Boolean = false,
        observed: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf()),
    ) =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                if (range != null) observed.add(range)
                if (ignore || range == null) return MockResponse().setBody(Buffer().write(bytes))
                val match =
                    Regex("bytes=(\\d+)-(\\d*)").matchEntire(range)
                        ?: return MockResponse().setResponseCode(416)
                val start = match.groupValues[1].toInt()
                val end = match.groupValues[2].toIntOrNull() ?: bytes.lastIndex
                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-$end/${bytes.size}")
                    .setBody(Buffer().write(bytes, start, end - start + 1))
                    .apply {
                        if (slow && end > start) throttleBody(16384, 20, TimeUnit.MILLISECONDS)
                    }
            }
        }

    @Test
    fun `large verified artifact is downloaded in bounded ranges and merged exactly`() =
        runBlocking {
            val bytes = ByteArray(9 * 1024 * 1024) { (it * 31).toByte() }
            val observed = java.util.Collections.synchronizedList(mutableListOf<String>())
            MockWebServer().use { server ->
                NetworkClient().use { network ->
                    server.dispatcher = ranges(bytes, observed = observed)
                    val target = directory.resolve("large.bin")
                    network.download(artifact(server, target, bytes), attempts = 1)
                    assertContentEquals(bytes, target.readBytes())
                    assertEquals(3, observed.size)
                    assertEquals("bytes=0-0", observed.first())
                    assertFalse(SegmentedDownloader.directory(target).exists())
                }
            }
        }

    @Test
    fun `server ignoring Range falls back to verified full download`() = runBlocking {
        val bytes = ByteArray(8 * 1024 * 1024) { it.toByte() }
        MockWebServer().use { server ->
            NetworkClient().use { network ->
                server.dispatcher = ranges(bytes, ignore = true)
                val target = directory.resolve("fallback.bin")
                network.download(artifact(server, target, bytes), attempts = 1)
                assertContentEquals(bytes, target.readBytes())
                assertFalse(SegmentedDownloader.directory(target).exists())
            }
        }
    }

    @Test
    fun `explicit cancellation removes every partial segment`() = runBlocking {
        val bytes = ByteArray(8 * 1024 * 1024) { it.toByte() }
        MockWebServer().use { server ->
            NetworkClient().use { network ->
                server.dispatcher = ranges(bytes, slow = true)
                val target = directory.resolve("cancel.bin")
                val job =
                    launch(Dispatchers.IO) {
                        network.download(artifact(server, target, bytes), attempts = 1)
                    }
                eventually {
                    val parts = SegmentedDownloader.directory(target)
                    parts.exists() && parts.listDirectoryEntries("*.part").any { it.fileSize() > 0 }
                }
                withTimeout(5000) { job.cancelAndJoin() }
                assertFalse(target.exists())
                assertFalse(network.partialFile(target).exists())
                assertFalse(SegmentedDownloader.directory(target).exists())
            }
        }
    }
}
