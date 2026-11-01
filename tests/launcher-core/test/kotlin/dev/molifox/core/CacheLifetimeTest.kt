// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 用假传输验证分类即时刷新及测速顺序只在单次任务中共用。

package dev.molifox.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

class CacheLifetimeTest {
    @TempDir lateinit var temporary: Path
    private val probeUrls = listOf("https://first.invalid/probe", "https://second.invalid/probe")

    private fun transport(body: (okhttp3.Request) -> String) =
        Interceptor { chain ->
            Response
                .Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(java.net.HttpURLConnection.HTTP_OK)
                .message("Fixture")
                .body(body(chain.request()).toResponseBody("application/json".toMediaType()))
                .build()
        }

    @Test
    fun `requests outside tasks measure again without retaining old source order`(): Unit =
        runBlocking {
            val requests = AtomicInteger()
            NetworkClient(
                interceptors =
                    listOf(
                        transport {
                            requests.incrementAndGet()
                            "{}"
                        },
                    ),
            ).use {
                it.fastestFirst(probeUrls)
                it.fastestFirst(probeUrls)
            }
            assertEquals(probeUrls.size * 2, requests.get())
        }

    @Test
    fun `parallel task children share initialization but the next task measures again`(): Unit =
        runBlocking {
            val requests = AtomicInteger()
            NetworkClient(
                interceptors =
                    listOf(
                        transport {
                            requests.incrementAndGet()
                            "{}"
                        },
                    ),
            ).use { network ->
                repeat(2) {
                    withContext(TaskSourceSelection()) {
                        coroutineScope {
                            List(8) { async { network.fastestFirst(probeUrls) } }.awaitAll()
                        }
                    }
                }
            }
            assertEquals(probeUrls.size * 2, requests.get())
        }

    @Test
    fun `each queued task shares probes within itself and the next task probes again`(): Unit =
        runBlocking {
            val requests = AtomicInteger()
            val store = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                NetworkClient(
                    interceptors =
                        listOf(
                            transport {
                                requests.incrementAndGet()
                                "{}"
                            },
                        ),
                ).use { network ->
                    val queue = JobQueue(store, scope)
                    repeat(2) { index ->
                        queue.enqueue("probe $index", setOf("probe:$index")) {
                            coroutineScope {
                                List(3) { async { network.fastestFirst(probeUrls) } }.awaitAll()
                            }
                        }
                        withTimeout(10_000) { queue.awaitIdle() }
                    }
                }
            } finally {
                scope.cancel()
            }
            // 每个任务内 3 个子下载共用一次探测，任务结束后释放；下一个任务重新探测两份地址。
            assertEquals(probeUrls.size * 2, requests.get())
        }

    @Test
    fun `cancelled initialization is not retained`(): Unit =
        runBlocking {
            val selection = TaskSourceSelection()
            val owner = Any()
            val started = CompletableDeferred<Unit>()
            val never = CompletableDeferred<List<String>>()
            val first =
                launch {
                    selection.order(owner, "auto", probeUrls) {
                        started.complete(Unit)
                        never.await()
                    }
                }
            started.await()
            first.cancelAndJoin()
            assertEquals(probeUrls, selection.order(owner, "auto", probeUrls) { probeUrls })
        }

    @Test
    fun `each category request receives fresh source data`(): Unit =
        runBlocking {
            val categories = AtomicInteger()
            val fake =
                transport { request ->
                    if (request.url.queryParameter("classesOnly") == "true") {
                        """{"data":[{"id":6,"name":"Mods","slug":"mc-mods"}]}"""
                    } else {
                        """{"data":[{"id":101,"name":"Category ${categories.incrementAndGet()}",
                          "slug": "fixture"}]}"""
                    }
                }
            NetworkClient(interceptors = listOf(fake)).use { network ->
                val backend =
                    CurseForgeBackend(LauncherStore(DesktopPlatform(), temporary), network)
                assertEquals("Category 1", backend.categories("mod").single().name)
                assertEquals("Category 2", backend.categories("mod").single().name)
            }
            assertEquals(2, categories.get())
        }
}
