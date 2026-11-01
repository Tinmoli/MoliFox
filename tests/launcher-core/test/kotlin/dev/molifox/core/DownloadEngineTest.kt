// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验证文件级并发、镜像主机节流、取消清理与逐文件失败汇总。

package dev.molifox.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DownloadEngineTest {
    @TempDir lateinit var directory: Path

    private suspend fun eventually(condition: () -> Boolean) =
        withTimeout(10_000) {
            while (!condition()) delay(10)
        }

    @Test
    fun `files are fetched concurrently and never above the limit`() =
        runBlocking {
            val inFlight = AtomicInteger()
            val peak = AtomicInteger()
            MockWebServer().use { server ->
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            val current = inFlight.incrementAndGet()
                            peak.accumulateAndGet(current) { left, right -> maxOf(left, right) }
                            try {
                                // 让请求真正重叠：太快返回的话并发度看不出来。
                                Thread.sleep(60)
                                val name = request.path.orEmpty().trim('/')
                                return MockResponse().setBody(Buffer().write(name.toByteArray()))
                            } finally {
                                inFlight.decrementAndGet()
                            }
                        }
                    }
                NetworkClient(source = { "official" }).use { network ->
                    val store = LauncherStore(DesktopPlatform(), directory.resolve("profile"))
                    val context = JobContext(store, "test")
                    val artifacts =
                        (1..24).map { index ->
                            DownloadArtifact(
                                server.loopbackUrl("/file-$index").toString(),
                                directory.resolve("out/file-$index.bin"),
                            )
                        }
                    val failures =
                        DownloadScheduler(
                            network,
                        ).run(artifacts, context, concurrency = 8)
                    assertTrue(failures.isEmpty(), "不应有失败：${failures.map { it.describe() }}")
                    assertTrue(peak.get() > 1, "文件应并发下载，实测最高并发 ${peak.get()}")
                    assertTrue(
                        peak.get() <= DEFAULT_FILE_CONCURRENCY,
                        "并发度 ${peak.get()} 超过上限 $DEFAULT_FILE_CONCURRENCY",
                    )
                    artifacts.forEach { artifact ->
                        assertEquals(
                            artifact.target.nameWithoutExtension,
                            artifact.target.readText(),
                        )
                    }
                }
            }
        }

    @Test
    fun `per file failures are collected and reported once at the end`() =
        runBlocking {
            MockWebServer().use { server ->
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            val path = request.path.orEmpty()
                            return if ("bad" in path) {
                                MockResponse().setResponseCode(404)
                            } else {
                                MockResponse().setBody(Buffer().write("ok".toByteArray()))
                            }
                        }
                    }
                NetworkClient(source = { "official" }).use { network ->
                    val store = LauncherStore(DesktopPlatform(), directory.resolve("profile"))
                    val context = JobContext(store, "test")
                    val good = artifacts(server, listOf("good-1", "good-2"))
                    val bad = artifacts(server, listOf("bad-1", "bad-2"))
                    val scheduler = DownloadScheduler(network)
                    val failures = scheduler.run(good + bad, context, concurrency = 4)
                    assertEquals(2, failures.size)
                    assertTrue(failures.all { it.status == 404 }, "失败信息要带状态码：$failures")
                    assertTrue(failures.all { it.host == "127.0.0.1" }, "失败信息要带来源：$failures")
                    // 一个 404 不能中断整批：成功文件必须真的落盘。
                    assertTrue(good.all { it.target.exists() }, "其余文件应继续下载完成")
                    assertBatchFailureReported(network, good + bad, context)
                }
            }
        }

    /** 按名称生成指向 mock 服务器的下载工件：路径决定成功或 404。 */
    private fun artifacts(
        server: MockWebServer,
        names: List<String>,
    ): List<DownloadArtifact> =
        names.map { name ->
            DownloadArtifact(
                server.loopbackUrl("/$name").toString(),
                directory.resolve("out/$name.bin"),
            )
        }

    /** 整批失败只上报一次，消息里需要列出每个失败文件、状态码与来源。 */
    private suspend fun assertBatchFailureReported(
        network: NetworkClient,
        artifacts: List<DownloadArtifact>,
        context: JobContext,
    ) {
        val error =
            assertFailsWith<DownloadBatchException> {
                network.downloadAll(artifacts, DEFAULT_FILE_CONCURRENCY, context, "下载资源")
            }
        val message = error.message.orEmpty()
        assertTrue("bad-1" in message && "bad-2" in message, "需要列出每个失败文件：$message")
        assertTrue("HTTP 404" in message, "需要列出状态码：$message")
        assertEquals(2, error.failures.size)
    }

    @Test
    fun `cancelling a batch stops scheduling and removes every partial`() =
        runBlocking {
            MockWebServer().use { server ->
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            MockResponse()
                                .setBody(Buffer().write(ByteArray(128 * 1024)))
                                .throttleBody(4096, 25, TimeUnit.MILLISECONDS)
                    }
                NetworkClient(source = { "official" }).use { network ->
                    val store = LauncherStore(DesktopPlatform(), directory.resolve("profile"))
                    val context = JobContext(store, "test")
                    val output = directory.resolve("out")
                    val artifacts =
                        (1..8).map { index ->
                            DownloadArtifact(
                                server.loopbackUrl("/slow-$index").toString(),
                                output.resolve("slow-$index.bin"),
                            )
                        }
                    val job =
                        launch(Dispatchers.IO) {
                            DownloadScheduler(network).run(artifacts, context, concurrency = 4)
                        }
                    eventually {
                        val partials = output.takeIf { it.exists() }?.listDirectoryEntries("*.part")
                        partials != null && partials.any { it.fileSize() > 0 }
                    }
                    withTimeout(10_000) { job.cancelAndJoin() }
                    val leftovers =
                        output.listDirectoryEntries().filter {
                            it.name.endsWith(".part") || it.name.endsWith(".parts")
                        }
                    assertTrue(leftovers.isEmpty(), "取消后不应残留断点文件：$leftovers")
                }
            }
        }
}
