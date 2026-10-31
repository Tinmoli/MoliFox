// 中继发现：官方 JSON 接口、社区文本列表、失败兜底与缓存。
package dev.molifox.multiplayer.easytier

import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EasyTierRelayDirectoryTest {
    @Test
    fun `parser keeps only active relay-capable addresses`() {
        val json =
            """
            {"success":true,"data":{"items":[
              {"address":"tcp://active.example:11010","allow_relay":true,"is_active":true},
              {"address":"tcp://no-relay.example:11010","allow_relay":false,"is_active":true},
              {"address":"tcp://inactive.example:11010","allow_relay":true,"is_active":false},
              {"address":"not-a-url","allow_relay":true,"is_active":true}
            ]}}
            """.trimIndent()

        assertEquals(listOf("tcp://active.example:11010"), parseActiveRelays(json).getOrThrow())
    }

    @Test
    fun `parser deduplicates and rejects malformed payloads`() {
        val duplicated =
            nodesJson(
                "tcp://same.example:11010",
                "tcp://same.example:11010",
                "udp://other.example:11010",
            )
        assertEquals(
            listOf("tcp://same.example:11010", "udp://other.example:11010"),
            parseActiveRelays(duplicated).getOrThrow(),
        )
        assertTrue(parseActiveRelays("not json").isFailure)
        assertTrue(parseActiveRelays("""{"data":{}}""").isFailure)
    }

    @Test
    fun `community text list keeps only peer urls`() {
        val text =
            """
            # node list
            tcp://community.example:39409

            <html>error</html>
            https://etnode.zkitefly.eu.org/node1
            tcp://community.example:39409
            """.trimIndent()

        assertEquals(listOf("tcp://community.example:39409"), parseRelayUrls(text))
    }

    @Test
    fun `custom relays come before live nodes and duplicates collapse`() =
        runBlocking {
            val fetcher =
                FakeRelayFetcher(
                    json = Result.success(nodesJson(LIVE_NODE, "udp://second.example:11010")),
                )
            val directory = EasyTierRelayDirectory(fetcher = fetcher, logger = { _, _ -> })

            val relays = directory.resolve(listOf(CUSTOM_NODE, LIVE_NODE))

            assertEquals(listOf(CUSTOM_NODE, LIVE_NODE, "udp://second.example:11010"), relays)
            assertEquals(FETCH_CALLS, fetcher.calls)
        }

    @Test
    fun `community list is used when official api is gone`() =
        runBlocking {
            val fetcher =
                FakeRelayFetcher(
                    json = Result.failure(IOException("UnknownHostException: uptime.easytier.cn")),
                    community = mapOf(COMMUNITY_LIST to Result.success("$COMMUNITY_NODE\n")),
                )
            val directory = EasyTierRelayDirectory(fetcher = fetcher, logger = { _, _ -> })

            assertEquals(listOf(COMMUNITY_NODE), directory.resolve(emptyList()))
            assertEquals(FETCH_CALLS, fetcher.calls)
        }

    @Test
    fun `total failure falls back to list urls and caches them`() =
        runBlocking {
            val fetcher = FakeRelayFetcher(json = Result.failure(IOException("dns unavailable")))
            val directory = EasyTierRelayDirectory(fetcher = fetcher, logger = { _, _ -> })

            val relays = directory.resolve(listOf(CUSTOM_NODE))
            assertEquals(CUSTOM_NODE, relays.first())
            assertTrue(relays.containsAll(EasyTierRelays.HTTP_NODE_LISTS))
            assertEquals(FETCH_CALLS, fetcher.calls)

            directory.resolve(emptyList())
            assertEquals(FETCH_CALLS, fetcher.calls)
        }

    @Test
    fun `successful result is cached and reused by prefetch`() =
        runBlocking {
            val fetcher =
                FakeRelayFetcher(
                    json = Result.success(nodesJson(LIVE_NODE)),
                    community = mapOf(COMMUNITY_LIST to Result.success("$COMMUNITY_NODE\n")),
                )
            val directory = EasyTierRelayDirectory(fetcher = fetcher, logger = { _, _ -> })

            directory.prefetch()
            assertEquals(listOf(LIVE_NODE, COMMUNITY_NODE), directory.resolve(emptyList()))
            assertEquals(
                listOf(CUSTOM_NODE, LIVE_NODE, COMMUNITY_NODE),
                directory.resolve(listOf(CUSTOM_NODE, LIVE_NODE)),
            )
            assertEquals(FETCH_CALLS, fetcher.calls)
        }

    @Test
    fun `dynamic relay count is capped`() =
        runBlocking {
            val many = (1..20).map { index -> "tcp://relay$index.example:11010" }
            val fetcher = FakeRelayFetcher(json = Result.success(nodesJson(*many.toTypedArray())))
            val directory = EasyTierRelayDirectory(fetcher = fetcher, logger = { _, _ -> })

            assertEquals(MAX_DYNAMIC_RELAYS, directory.resolve(many).size)
            assertEquals(FETCH_CALLS, fetcher.calls)
        }

    @Test
    fun `official fallback is empty after upstream shutdown`() {
        assertTrue(EasyTierRelays.DEFAULT.isEmpty())
        assertTrue(EasyTierRelays.HTTP_NODE_LISTS.contains("https://etnode.zkitefly.eu.org/node1"))
        assertTrue(EasyTierRelays.NODE_LIST_URL.startsWith("https://"))
    }

    /** 按 URL 返回脚本化响应；未配置的社区列表返回空文本（等价于“没有节点”）。 */
    private class FakeRelayFetcher(
        private val json: Result<String> = Result.failure(IOException("no json")),
        private val community: Map<String, Result<String>> = emptyMap(),
    ) : RelayListFetcher {
        var calls = 0
            private set

        override suspend fun fetch(url: String): Result<String> {
            calls++
            return if (url == EasyTierRelays.NODE_LIST_URL) {
                json
            } else {
                community[url] ?: Result.success("")
            }
        }
    }

    private companion object {
        const val CUSTOM_NODE = "tcp://custom.example:11010"
        const val LIVE_NODE = "tcp://live.example:11010"
        const val COMMUNITY_NODE = "tcp://community.example:39409"
        const val COMMUNITY_LIST = "https://etnode.zkitefly.eu.org/node1"
        const val MAX_DYNAMIC_RELAYS = 8
        const val FETCH_CALLS = 5

        fun nodesJson(vararg addresses: String): String =
            addresses.joinToString(
                prefix = """{"success":true,"data":{"items":[""",
                postfix = "]}}",
                separator = ",",
            ) { address ->
                """{"address":"$address","allow_relay":true,"is_active":true}"""
            }
    }
}
