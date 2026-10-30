// 使用内存 HTTP 拦截器验证测速排序、镜像回退、更新清单与 Java 提供商边界，不访问外网。
package dev.molifox.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val SLOW_SOURCE_DELAY_MS = 200L
private const val PROBE_RESPONSE_BYTES = 64 * 1024
private const val HTTP_SUCCESS = 200
private const val HTTP_UNAVAILABLE = 504
private const val PROBE_SOURCE_LIMIT = 4
private const val TEST_JAVA_MAJOR = 21
private const val CANCEL_TEST_TIMEOUT_MS = 2_000L

class DownloadSourceSelectionTest {
    @Test fun speedSamplingRanksFastSourceAndLimitsProbeCount() =
        runBlocking {
            val seen = ConcurrentLinkedQueue<String>()
            val active = AtomicInteger()
            val peak = AtomicInteger()
            fixtureNetwork { chain ->
                seen += chain.request().header("Range").orEmpty()
                val concurrent = active.incrementAndGet()
                peak.accumulateAndGet(concurrent, ::maxOf)
                try {
                    if (chain.request().url.host ==
                        "slow.invalid"
                    ) {
                        Thread.sleep(SLOW_SOURCE_DELAY_MS)
                    }
                    response(chain, HTTP_SUCCESS, "x".repeat(PROBE_RESPONSE_BYTES))
                } finally {
                    active.decrementAndGet()
                }
            }.use { network ->
                val urls =
                    listOf("slow", "fast", "third", "fourth", "unprobed").map {
                        "https://$it.invalid/core.zip"
                    }
                val ranked = network.rankDownloadSources(urls)
                assertTrue(ranked.indexOf(urls[1]) < ranked.indexOf(urls[0]))
                assertEquals(urls.last(), ranked.last())
                assertEquals(PROBE_SOURCE_LIMIT, seen.size)
                assertTrue(seen.all { it == "bytes=0-${PROBE_RESPONSE_BYTES - 1}" })
                assertTrue(peak.get() <= PROBE_SOURCE_LIMIT)
            }
        }

    @Test fun cancellingSourceProbeDoesNotWaitForBlockedResponse() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val gate = CountDownLatch(1)
            fixtureNetwork { chain ->
                entered.complete(Unit)
                gate.await()
                response(chain, HTTP_SUCCESS, "fixture")
            }.use { network ->
                try {
                    val job =
                        launch {
                            network.rankDownloadSources(
                                listOf("https://blocked.invalid/core.zip"),
                            )
                        }
                    withTimeout(CANCEL_TEST_TIMEOUT_MS) {
                        entered.await()
                        job.cancelAndJoin()
                    }
                    assertTrue(job.isCancelled)
                } finally {
                    gate.countDown()
                }
            }
        }

    @Test fun githubCandidatesPreserveOriginalAndAppendCustomMirror() {
        val official = "https://github.com/Tinmoli/MoliFox/releases/download/v1.0.0/MoliFox.exe"
        val sources = gitHubDownloadCandidates(official, "https://custom.invalid/")
        assertEquals(official, sources.first())
        assertEquals("https://custom.invalid/$official", sources.last())
        assertEquals(
            listOf("http://127.0.0.1/file"),
            gitHubDownloadCandidates("http://127.0.0.1/file"),
        )
    }

    @Test fun releaseListFallsBackToAcceleratorAndPreservesNotes() =
        runBlocking {
            val hosts = ConcurrentLinkedQueue<String>()
            fixtureNetwork { chain ->
                hosts += chain.request().url.host
                if (chain.request().url.host == "api.github.com") {
                    response(chain, HTTP_UNAVAILABLE, "")
                } else {
                    response(chain, HTTP_SUCCESS, "[{\"tag_name\":\"v1.0.0\",\"body\":\"更新内容\"}]")
                }
            }.use { network ->
                val releases =
                    network.gitHubReleaseList(
                        "https://api.github.com/repos/Tinmoli/MoliFox/releases",
                    )
                assertEquals(
                    "更新内容",
                    releases
                        .single()
                        .asJsonObject
                        .get("body")
                        .asString,
                )
                assertTrue("api.github.com" in hosts)
                assertTrue(hosts.any { it != "api.github.com" })
            }
        }

    @Test fun chosenJavaProviderDoesNotQueryOtherVendors() =
        runBlocking {
            for ((source, expectedHost) in listOf(
                "azul" to "api.azul.com",
                "adoptium" to "api.adoptium.net",
            )) {
                val hosts = ConcurrentLinkedQueue<String>()
                fixtureNetwork { chain ->
                    hosts += chain.request().url.host
                    response(chain, HTTP_SUCCESS, "[]")
                }.use { network ->
                    assertFailsWith<IllegalStateException> {
                        JavaRuntimePackages(
                            network,
                        ).find(TEST_JAVA_MAJOR, "windows", "x64", "jre", source)
                    }
                    assertTrue(hosts.isNotEmpty())
                    assertEquals(setOf(expectedHost), hosts.toSet())
                    assertEquals(source, normalizedJavaDownloadSource(source))
                }
            }
        }

    private fun fixtureNetwork(reply: (Interceptor.Chain) -> Response) =
        NetworkClient(
            downloadAttempts = { 1 },
            interceptors = listOf(Interceptor(reply)),
        )

    private fun response(
        chain: Interceptor.Chain,
        status: Int,
        body: String,
    ): Response =
        Response
            .Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("fixture")
            .body(body.toResponseBody())
            .build()
}
