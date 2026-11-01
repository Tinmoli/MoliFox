// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val HIGH_FILE_CONCURRENCY = 12
private const val COLLIDING_FILE_CONCURRENCY = 3
private const val LEGACY_TARGET_LOCK_BUCKETS = 16
private const val PEAK_WAIT_SECONDS = 3L
private const val FULL_FILE_CONCURRENCY = 64
private const val MINIMUM_FULL_FILE_CONCURRENCY = 40
private const val LARGE_STREAM_BYTES = 8L * 1024 * 1024
private const val DOWNLOAD_CANCEL_TIMEOUT_MILLIS = 5000L

private fun recordPeak(concurrency: Int, minimum: Int, peak: Int) {
    println("MEASURED_DOWNLOAD_CONCURRENCY configured=$concurrency minimum=$minimum peak=$peak")
    assertTrue(peak in minimum..concurrency, "Observed peak $peak")
}

class DownloadSettingsConcurrencyTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `controller honors file concurrency above eight`() = measure(HIGH_FILE_CONCURRENCY, collisions = false)

    @Test
    fun `different targets with colliding hashes download concurrently`() =
        measure(COLLIDING_FILE_CONCURRENCY, collisions = true)

    @Test
    fun `low configured concurrency still bounds active transfers`() = measure(2, collisions = false, files = 4)

    @Test
    fun `sixty four configured files can fill the worker pool`() =
        measure(FULL_FILE_CONCURRENCY, collisions = false, minimum = MINIMUM_FULL_FILE_CONCURRENCY)

    @Test
    fun `large streamed files are not throttled by their total disk size`() =
        measure(FULL_FILE_CONCURRENCY, collisions = false, minimum = MINIMUM_FULL_FILE_CONCURRENCY, large = true)

    private fun targets(count: Int, collisions: Boolean): List<Path> {
        val candidates = generateSequence(0) { it + 1 }.map { temporary.resolve("files/file-$it.bin") }
        fun bucket(path: Path) = Math.floorMod(taskPathKey(path).hashCode(), LEGACY_TARGET_LOCK_BUCKETS)
        val selected = when {
            collisions -> candidates.filter { bucket(it) == 0 }
            count <= LEGACY_TARGET_LOCK_BUCKETS -> candidates.distinctBy(::bucket)
            else -> candidates
        }
        return selected
            .take(count).toList()
    }

    private fun configuredController(concurrency: Int): LauncherController {
        val profile = temporary.resolve("profile")
        val platform = DesktopPlatform()
        LauncherStore(platform, profile).mutate {
            it.copy(
                settings = it.settings.copy(
                    autoCheckUpdates = false,
                    downloadSource = "official",
                    downloadThreads = concurrency,
                    downloadSegments = 1,
                ),
            )
        }
        return LauncherController(platform, profile)
    }

    private fun dispatcher(
        active: AtomicInteger,
        peak: AtomicInteger,
        arrivals: CountDownLatch,
        large: Boolean,
    ): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val current = active.incrementAndGet()
            peak.accumulateAndGet(current, ::maxOf)
            arrivals.countDown()
            try {
                arrivals.await(PEAK_WAIT_SECONDS, TimeUnit.SECONDS)
                return MockResponse().setBody("x").apply {
                    if (large) setHeader("Content-Length", LARGE_STREAM_BYTES)
                }
            } finally {
                active.decrementAndGet()
            }
        }
    }

    private fun measure(
        concurrency: Int,
        collisions: Boolean,
        files: Int = concurrency,
        minimum: Int = concurrency,
        large: Boolean = false,
    ) = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val arrivals = CountDownLatch(concurrency)
        MockWebServer().use { server ->
            server.dispatcher = dispatcher(active, peak, arrivals, large)
            configuredController(concurrency).use { controller ->
                val artifacts = targets(files, collisions).mapIndexed { index, target ->
                    DownloadArtifact(
                        server.loopbackUrl("/file-$index").toString(),
                        target,
                        size = if (large) LARGE_STREAM_BYTES else 1,
                    )
                }
                val download: suspend () -> Unit = {
                    val failures = DownloadScheduler(controller.network).run(
                        artifacts,
                        JobContext(controller.store, "measured"),
                        concurrency = controller.store.data.settings.downloadThreads,
                    )
                    assertTrue(failures.isEmpty(), failures.toString())
                }
                if (large) {
                    val worker = launch(Dispatchers.IO) { download() }
                    try {
                        arrivals.await(PEAK_WAIT_SECONDS, TimeUnit.SECONDS)
                        assertTrue(peak.get() in minimum..concurrency, "Observed peak ${peak.get()}")
                    } finally {
                        withTimeout(DOWNLOAD_CANCEL_TIMEOUT_MILLIS) { worker.cancelAndJoin() }
                    }
                } else {
                    download()
                    assertEquals(files, server.requestCount)
                }
                recordPeak(concurrency, minimum, peak.get())
            }
        }
    }
}
