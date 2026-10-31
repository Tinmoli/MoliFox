// 中继发现：活动节点 JSON 解析、失败兜底、缓存与自定义优先级。
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
    fun `custom relays come before live nodes and duplicates collapse`() =
        runBlocking {
            val fetcher =
                FakeRelayFetcher(Result.success(nodesJson(LIVE_NODE, "udp://second.example:11010")))
            val directory = EasyTierRelayDirectory(fetcher = fetcher, logger = { _, _ -> })

            val relays = directory.resolve(listOf(CUSTOM_NODE, LIVE_NODE))

            assertEquals(listOf(CUSTOM_NODE, LIVE_NODE, "udp://second.example:11010"), relays)
            assertEquals(1, fetcher.calls)
        }

    @Test
    fun `fetch failure falls back to custom relays and retries later`() =
        runBlocking {
            val fetcher = FakeRelayFetcher(Result.failure(IOException("dns unavailable")))
            val directory = EasyTierRelayDirectory(fetcher = fetcher, logger = { _, _ -> })

            assertEquals(listOf(CUSTOM_NODE), directory.resolve(listOf(CUSTOM_NODE)))
            assertTrue(directory.resolve(emptyList()).isEmpty())
            assertEquals(2, fetcher.calls)
        }

    @Test
    fun `successful result is cached and reused by prefetch`() =
        runBlocking {
            val fetcher = FakeRelayFetcher(Result.success(nodesJson(LIVE_NODE)))
            val directory = EasyTierRelayDirectory(fetcher = fetcher, logger = { _, _ -> })

            directory.prefetch()
            assertEquals(listOf(LIVE_NODE), directory.resolve(emptyList()))
            assertEquals(
                listOf(CUSTOM_NODE, LIVE_NODE),
                directory.resolve(listOf(CUSTOM_NODE, LIVE_NODE)),
            )
            assertEquals(1, fetcher.calls)
        }

    @Test
    fun `dynamic relay count is capped`() =
        runBlocking {
            val many = (1..20).map { index -> "tcp://relay$index.example:11010" }
            val fetcher = FakeRelayFetcher(Result.success(nodesJson(*many.toTypedArray())))
            val directory = EasyTierRelayDirectory(fetcher = fetcher, logger = { _, _ -> })

            assertEquals(MAX_DYNAMIC_RELAYS, directory.resolve(many).size)
            assertEquals(1, fetcher.calls)
        }

    @Test
    fun `built-in fallback includes fixed nodes and http node lists`() {
        assertTrue(EasyTierRelays.DEFAULT.contains("tcp://public.easytier.top:11010"))
        assertTrue(EasyTierRelays.DEFAULT.contains("https://etnode.zkitefly.eu.org/node1"))
        assertTrue(EasyTierRelays.NODE_LIST_URL.startsWith("https://"))
    }

    private class FakeRelayFetcher(
        private val response: Result<String>,
    ) : RelayListFetcher {
        var calls = 0
            private set

        override suspend fun fetch(url: String): Result<String> {
            calls++
            assertTrue(url == EasyTierRelays.NODE_LIST_URL, "unexpected url: $url")
            return response
        }
    }

    private companion object {
        const val CUSTOM_NODE = "tcp://custom.example:11010"
        const val LIVE_NODE = "tcp://live.example:11010"
        const val MAX_DYNAMIC_RELAYS = 8

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
