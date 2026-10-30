// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验证源健康度、403/429 豁免、必需/可选文件与两个下载源设置的迁移。

package dev.molifox.core

import com.google.gson.JsonObject
import dev.molifox.core.net.DownloadSource
import dev.molifox.core.net.DownloadSourceCatalog
import dev.molifox.core.net.DownloadSourceFailureException
import dev.molifox.core.net.SourceHealth
import dev.molifox.core.net.SourceRoute
import dev.molifox.core.net.SourceThrottle
import dev.molifox.core.net.downloadConcurrency
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceHealthTest {
    @TempDir lateinit var directory: Path

    private fun artifact(
        url: String,
        name: String,
        content: ByteArray = "ok".toByteArray(),
    ) = DownloadArtifact(
        url,
        directory.resolve("out/$name"),
        MessageDigest
            .getInstance(
                "SHA-1",
            ).digest(content)
            .joinToString("") { "%02x".format(it) },
        content.size.toLong(),
    )

    /** 两个本地服务：主源（官方）与替身镜像，后者按前缀映射接管前者的地址。 */
    private fun catalogFor(
        primary: MockWebServer,
        mirror: MockWebServer,
    ): DownloadSourceCatalog {
        val from = primary.loopbackUrl("/").toString()
        val to = mirror.loopbackUrl("/").toString()
        return DownloadSourceCatalog.DEFAULT.plus(
            DownloadSource(
                "fixture",
                "测试镜像",
                mirror = true,
                routes = listOf(SourceRoute(from, to)),
            ),
        )
    }

    @Test
    fun `source failing three times is disabled for the rest of the run`() =
        runBlocking {
            val logs = Collections.synchronizedList(mutableListOf<String>())
            MockWebServer().use { primary ->
                primary.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest) =
                            MockResponse().setResponseCode(500)
                    }
                MockWebServer().use { mirror ->
                    mirror.dispatcher =
                        object : Dispatcher() {
                            override fun dispatch(request: RecordedRequest) =
                                MockResponse().setBody(Buffer().write("ok".toByteArray()))
                        }
                    NetworkClient(
                        source = { "auto" },
                        catalog = catalogFor(primary, mirror),
                        logger = { message, _ -> logs += message },
                    ).use { network ->
                        repeat(5) { index ->
                            network.download(
                                artifact(primary.loopbackUrl("/file-$index").toString(), "f$index"),
                            )
                        }
                        // 前 3 个文件各让官方失败一次，之后官方被禁用，只剩镜像。
                        assertEquals(3, primary.requestCount)
                        assertEquals(5, mirror.requestCount)
                        val state = network.health.state("official")
                        assertEquals(3, state.consecutiveFailures)
                        assertEquals(3, state.failures)
                        assertTrue(state.disabled(System.currentTimeMillis()))
                        assertTrue(
                            logs.any { it == "源 官方 已禁用：连续 3 次失败" },
                            "应记录禁用日志，实际：$logs",
                        )
                    }
                }
            }
        }

    @Test
    fun `forbidden and throttled responses retry the same source without disabling it`() =
        runBlocking {
            val logs = Collections.synchronizedList(mutableListOf<String>())
            MockWebServer().use { server ->
                val calls = AtomicInteger()
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            val index = calls.incrementAndGet()
                            return when (index) {
                                // 每个文件先被拒一次，再成功：403 与 429 都不得算失败。
                                1 -> MockResponse().setResponseCode(403)
                                3 -> MockResponse().setResponseCode(429)
                                else -> MockResponse().setBody(Buffer().write("ok".toByteArray()))
                            }
                        }
                    }
                NetworkClient(
                    source = { "official" },
                    health = SourceHealth(rateLimitBaseMillis = 5, rateLimitCapMillis = 20),
                    logger = { message, _ -> logs += message },
                ).use { network ->
                    network.download(artifact(server.loopbackUrl("/a").toString(), "a"))
                    network.download(artifact(server.loopbackUrl("/b").toString(), "b"))
                    assertEquals(4, server.requestCount, "两次拒绝都应重试同一个源")
                    val state = network.health.state("official")
                    assertEquals(0, state.failures)
                    assertEquals(0, state.consecutiveFailures)
                    assertEquals(2, state.rateLimitHits)
                    assertEquals(0L, state.disabledUntil)
                    assertTrue(logs.any { "返回 403" in it })
                    assertTrue(logs.any { "返回 429" in it })
                }
            }
        }

    @Test
    fun `rate limit backoff doubles up to thirty seconds and resets after success`() {
        var now = 1_000L
        val health = SourceHealth(clock = { now })
        val waits = (1..7).map { health.recordRateLimited("bmclapi") }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), waits)
        health.recordSuccess("bmclapi")
        now += 100_000
        assertEquals(1_000L, health.recordRateLimited("bmclapi"))
        assertEquals(0, health.state("bmclapi").failures)
    }

    @Test
    fun `optional file is skipped while a required file lists every failed source`() =
        runBlocking {
            val warnings = Collections.synchronizedList(mutableListOf<String>())
            MockWebServer().use { primary ->
                primary.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest) =
                            MockResponse().setResponseCode(500)
                    }
                MockWebServer().use { mirror ->
                    mirror.dispatcher =
                        object : Dispatcher() {
                            override fun dispatch(request: RecordedRequest) =
                                MockResponse().setResponseCode(404)
                        }
                    NetworkClient(source = { "auto" }, catalog = catalogFor(primary, mirror))
                        .use { network ->
                            val required =
                                artifact(primary.loopbackUrl("/required").toString(), "required")
                            val optional =
                                artifact(primary.loopbackUrl("/optional").toString(), "optional")
                            val failure =
                                assertFailsWith<DownloadSourceFailureException> {
                                    network.download(
                                        required,
                                    )
                                }
                            assertEquals(listOf<Int?>(500, 404), failure.attempts.map { it.status })
                            assertTrue("每个源尝试的 URL 与状态码" in failure.message.orEmpty())
                            assertTrue("HTTP 500" in failure.message.orEmpty())
                            assertTrue("HTTP 404" in failure.message.orEmpty())
                            assertFalse(network.downloadOptional(optional))
                            assertFalse(optional.target.exists())

                            val store =
                                LauncherStore(DesktopPlatform(), directory.resolve("verify"))
                            val verifier = GameFileVerifier(network, concurrency = 2)
                            val error =
                                assertFailsWith<IOException> {
                                    verifier.ensure(
                                        listOf(required),
                                        JobContext(store, "test"),
                                        "下载资源",
                                        optional = listOf(optional),
                                        warn = { warnings += it },
                                    )
                                }
                            assertTrue(
                                "每个源尝试的 URL 与状态码" in error.message.orEmpty(),
                                "必需文件的失败信息要列出每个源：${error.message}",
                            )
                            assertTrue(
                                warnings.any { it.startsWith("可选文件未补全，已跳过") },
                                "可选文件只警告：$warnings",
                            )
                        }
                }
            }
        }

    @Test
    fun `mirror files offer several candidates while unknown selections fall back to automatic`() {
        val library =
            "https://libraries.minecraft.net/commons-io/commons-io/2.20.0/commons-io-2.20.0.jar"
        val candidates = DownloadSourceCatalog.DEFAULT.candidates(library, "auto")
        assertEquals(
            listOf("official", "bmclapi", "aliyun", "tencent", "huawei", "central"),
            candidates.map { it.source.key },
        )
        assertEquals(
            "https://bmclapi2.bangbang93.com/maven/commons-io/commons-io/2.20.0/commons-io-2.20.0.jar",
            candidates.first { it.source.key == "bmclapi" }.url,
        )
        val assets =
            DownloadSourceCatalog.DEFAULT.candidates(
                "https://resources.download.minecraft.net/ab/abcdef",
                "auto",
            )
        assertEquals(2, assets.size)
        assertEquals(
            "https://bmclapi2.bangbang93.com/assets/ab/abcdef",
            assets.first { it.source.key == "bmclapi" }.url,
        )
        // 旧配置里的未知取值按自动处理，绝不因为旧值少试源。
        assertEquals(2, DownloadSourceCatalog.DEFAULT.candidates(assets.first().url, "mirror").size)
        assertEquals(
            listOf("official"),
            DownloadSourceCatalog.DEFAULT
                .candidates(
                    "https://api.modrinth.com/v2/project/x",
                    "auto",
                ).map { it.source.key },
        )
    }

    @Test
    fun `legacy single download source migrates into both sources independently`() {
        val migrated = loadSettings("downloadSource" to "bmclapi")
        assertEquals("bmclapi", migrated.downloadSource)
        assertEquals("bmclapi", migrated.versionListSource, "旧配置只有一份值，两边都要沿用")

        val split = loadSettings("downloadSource" to "official", "versionListSource" to "bmclapi")
        assertEquals("official", split.downloadSource)
        assertEquals("bmclapi", split.versionListSource)

        val unknown = loadSettings("downloadSource" to "mirror")
        assertEquals("auto", unknown.downloadSource)
        assertEquals("auto", unknown.versionListSource)
    }

    @Test
    fun `download concurrency follows the memory budget and mirror cap applies`() =
        runBlocking {
            val plain = downloadConcurrency(8, 1L * 1024 * 1024)
            assertEquals(8, plain.files)
            assertNull(plain.note)
            val clamped = downloadConcurrency(8, 64L * 1024 * 1024)
            assertEquals(4, clamped.files)
            assertTrue("并发下载数 8 → 4" in clamped.note.orEmpty())

            val logs = Collections.synchronizedList(mutableListOf<String>())
            val inFlight = AtomicInteger()
            val peak = AtomicInteger()
            val content = ByteArray(256 * 1024) { (it % 251).toByte() }
            MockWebServer().use { server ->
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            val current = inFlight.incrementAndGet()
                            peak.accumulateAndGet(current) { left, right -> maxOf(left, right) }
                            Thread.sleep(60)
                            inFlight.decrementAndGet()
                            return MockResponse().setBody(Buffer().write(content))
                        }
                    }
                NetworkClient(
                    source = { "official" },
                    downloadBudgetBytes = 1024L * 1024,
                    logger = { message, _ -> logs += message },
                ).use { network ->
                    val store = LauncherStore(DesktopPlatform(), directory.resolve("budget"))
                    val artifacts =
                        (1..4).map { index ->
                            artifact(
                                server.loopbackUrl("/big-$index").toString(),
                                "big-$index",
                                content,
                            )
                        }
                    val failures =
                        DownloadScheduler(
                            network,
                        ).run(artifacts, JobContext(store, "test"), concurrency = 8)
                    assertTrue(failures.isEmpty(), "不应有失败：${failures.map { it.describe() }}")
                    assertTrue(peak.get() in 2..4, "按预算应压到 4 路，实测 ${peak.get()}")
                    assertEquals(1, logs.count { "并发下载数" in it }, "预算只记一行日志：$logs")
                }
            }

            SourceThrottle.resetForTests()
            val mirror = checkNotNull(DownloadSourceCatalog.DEFAULT.source("bmclapi"))
            val mirrorInFlight = AtomicInteger()
            val mirrorPeak = AtomicInteger()
            coroutineScope {
                repeat(6) {
                    launch(Dispatchers.IO) {
                        SourceThrottle.run(mirror, mirrorConcurrency = 2) {
                            val current = mirrorInFlight.incrementAndGet()
                            mirrorPeak.accumulateAndGet(
                                current,
                            ) { left, right -> maxOf(left, right) }
                            delay(150)
                            mirrorInFlight.decrementAndGet()
                        }
                    }
                }
            }
            assertTrue(mirrorPeak.get() <= 2, "镜像并发设置应生效，实测 ${mirrorPeak.get()}")
        }

    private fun loadSettings(vararg fields: Pair<String, String>): LauncherSettings {
        val root = directory.resolve("profile-${fields.joinToString("-") { it.second }}")
        val settings =
            JsonObject().apply {
                addProperty("gameRoot", directory.resolve("game").toString())
                fields.forEach { (key, value) -> addProperty(key, value) }
            }
        val data =
            JsonObject().apply {
                addProperty("schemaVersion", 2)
                add("settings", settings)
                add("accounts", com.google.gson.JsonArray())
                add("instances", com.google.gson.JsonArray())
            }
        val config = root.resolve("config")
        config.createDirectories()
        config.resolve("launcher.json").writeText(data.toString())
        return LauncherStore(DesktopPlatform(), root).data.settings
    }
}
