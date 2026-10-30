// 校验陶瓦控制接口：解析 /state 的各类负载，并按约定拼接查询参数。
package dev.molifox.multiplayer

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.molifox.multiplayer.platform.TerracottaApi
import java.net.InetSocketAddress
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerracottaApiTest {
    @Test
    fun `parses host room code and players`() {
        withServer(
            """{"index":3,"state":"host-ok","room":"U/ABCD-EFGH-IJKL-MNOP",
            "profiles":[{"machine_id":"m1","name":"Tinmoli","vendor":"Minecraft","kind":"HOST"},
            {"machine_id":"m2","name":"Friend","vendor":"Minecraft","kind":"GUEST"}]}""",
        ) { api ->
            val snapshot = api.snapshot()
            assertEquals(3, snapshot.index)
            assertEquals("host-ok", snapshot.state)
            assertEquals("U/ABCD-EFGH-IJKL-MNOP", snapshot.room)
            assertEquals(2, snapshot.profiles.size)
            assertEquals("m1", snapshot.profiles[0].machineId)
            assertEquals("HOST", snapshot.profiles[0].kind)
        }
    }

    @Test
    fun `parses guest address difficulty and exception type`() {
        withServer(
            "{\"index\":4,\"state\":\"guest-starting\",\"difficulty\":\"MEDIUM\"," +
                "\"url\":\"127.0.0.1:25565\"}",
        ) { api ->
            val snapshot = api.snapshot()
            assertEquals("127.0.0.1:25565", snapshot.url)
            assertEquals("MEDIUM", snapshot.difficulty)
            assertNull(snapshot.room)
        }
        withServer("""{"index":5,"state":"exception","type":4}""") { api ->
            assertEquals(4, api.snapshot().exceptionType)
        }
    }

    @Test
    fun `sends player and public nodes when scanning and joining`() {
        val requests = mutableListOf<String>()
        withServer(
            """{"index":1,"state":"host-scanning"}""",
            onRequest = { exchange ->
                requests += "${exchange.requestURI.path}?${exchange.requestURI.rawQuery.orEmpty()}"
            },
        ) { api ->
            api.startScanning("Nick", listOf("https://node1"))
            api.startGuesting("U/CODE", "Nick", listOf("https://node2"))
            api.idle()
        }
        assertEquals(3, requests.size)
        val scanning = decode(requests[0])
        assertTrue(scanning.startsWith("/state/scanning?"))
        assertTrue(scanning.contains("player=Nick"))
        assertTrue(scanning.contains("public_nodes=https://node1"))
        val guesting = decode(requests[1])
        assertTrue(guesting.startsWith("/state/guesting?"))
        assertTrue(guesting.contains("room=U/CODE"))
        assertTrue(guesting.contains("public_nodes=https://node2"))
        assertTrue(decode(requests[2]).startsWith("/state/ide"))
    }

    private fun decode(raw: String): String = URLDecoder.decode(raw, Charsets.UTF_8)

    private fun withServer(
        body: String,
        onRequest: (HttpExchange) -> Unit = {},
        block: (TerracottaApi) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            onRequest(exchange)
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block(TerracottaApi(server.address.port))
        } finally {
            server.stop(0)
        }
    }
}
