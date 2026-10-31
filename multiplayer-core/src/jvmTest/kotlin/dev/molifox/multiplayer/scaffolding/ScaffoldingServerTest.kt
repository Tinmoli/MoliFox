// Scaffolding 服务端的回环集成测试：真实 TCP、可控超时，不使用长时间 sleep。
package dev.molifox.multiplayer.scaffolding

import dev.molifox.multiplayer.protocol.ScaffoldingPayloads
import dev.molifox.multiplayer.protocol.ScaffoldingProtocol
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun withServer(
    playerTimeoutMillis: Long = DEFAULT_PLAYER_TIMEOUT_MILLIS,
    cleanupIntervalMillis: Long = DEFAULT_CLEANUP_INTERVAL_MILLIS,
    hostProfile: ScaffoldingPlayer? = null,
    clock: () -> Long = { System.currentTimeMillis() },
    onError: (Throwable) -> Unit = {},
    block: (ScaffoldingServer, Int) -> Unit,
) {
    val (server, port) =
        startTestServer(
            playerTimeoutMillis = playerTimeoutMillis,
            cleanupIntervalMillis = cleanupIntervalMillis,
            hostProfile = hostProfile,
            clock = clock,
            onError = onError,
        )
    try {
        block(server, port)
    } finally {
        server.close()
    }
}

class ScaffoldingServerTest {
    @Test
    fun `echoes ping body and negotiates protocols`() =
        withServer { _, port ->
            RawScaffoldingConnection(port).use { connection ->
                val pong = connection.request(ScaffoldingProtocol.PING, "hello".encodeToByteArray())
                assertEquals(ScaffoldingProtocol.STATUS_OK, pong.status)
                assertContentEquals("hello".encodeToByteArray(), pong.body)

                val offered = listOf(ScaffoldingProtocol.PING, ScaffoldingProtocol.PROTOCOLS)
                val negotiated =
                    connection.request(
                        ScaffoldingProtocol.PROTOCOLS,
                        ScaffoldingPayloads.encodeProtocolNames(offered),
                    )
                assertEquals(ScaffoldingProtocol.STATUS_OK, negotiated.status)
                assertEquals(
                    ScaffoldingProtocol.STANDARD_PROTOCOLS,
                    ScaffoldingPayloads.decodeProtocolNames(negotiated.body),
                )
            }
        }

    @Test
    fun `reports server port only after minecraft port is registered`() =
        withServer { server, port ->
            RawScaffoldingConnection(port).use { connection ->
                assertEquals(
                    ScaffoldingProtocol.STATUS_SERVER_NOT_READY,
                    connection.request(ScaffoldingProtocol.SERVER_PORT).status,
                )
                server.setMinecraftPort(25565)
                val ready = connection.request(ScaffoldingProtocol.SERVER_PORT)
                assertEquals(ScaffoldingProtocol.STATUS_OK, ready.status)
                assertEquals(25565, ScaffoldingPayloads.decodeServerPort(ready.body))

                assertFailsWith<IllegalArgumentException> { server.setMinecraftPort(0) }
                server.setMinecraftPort(null)
                assertEquals(
                    ScaffoldingProtocol.STATUS_SERVER_NOT_READY,
                    connection.request(ScaffoldingProtocol.SERVER_PORT).status,
                )
            }
        }

    @Test
    fun `registers players and returns profiles json`() =
        withServer { server, port ->
            RawScaffoldingConnection(port).use { connection ->
                val alice = testPlayer("machine-a", "Alice")
                val registered =
                    connection.request(ScaffoldingProtocol.PLAYER_PING, encodePlayerPing(alice))
                assertEquals(ScaffoldingProtocol.STATUS_OK, registered.status)
                runBlocking {
                    awaitTrue { server.players.value.any { it.machineId == "machine-a" } }
                }

                val profiles = connection.request(ScaffoldingProtocol.PLAYER_PROFILES_LIST)
                assertEquals(ScaffoldingProtocol.STATUS_OK, profiles.status)
                val parsed = parsePlayerProfiles(profiles.body).getOrThrow()
                assertEquals(listOf(alice), parsed)
            }
        }

    @Test
    fun `publishes host profile first with host kind`() {
        val host = testPlayer("host-machine", "Host")
        withServer(hostProfile = host) { server, port ->
            RawScaffoldingConnection(port).use { connection ->
                val profiles = connection.request(ScaffoldingProtocol.PLAYER_PROFILES_LIST)
                val parsed = parsePlayerProfiles(profiles.body).getOrThrow()
                assertEquals(listOf(ScaffoldingPlayerKind.HOST), parsed.map { it.kind })
                assertEquals(
                    "host-machine",
                    server.players.value
                        .single()
                        .machineId,
                )
            }
        }
    }

    @Test
    fun `answers unknown protocol and malformed ping with error status`() =
        withServer { _, port ->
            RawScaffoldingConnection(port).use { connection ->
                val unknown = connection.request("c:not_implemented")
                assertEquals(ScaffoldingProtocol.STATUS_UNKNOWN_ERROR, unknown.status)
                assertTrue(unknown.body.decodeToString().contains("c:not_implemented"))

                val malformed =
                    connection.request(ScaffoldingProtocol.PLAYER_PING, "{oops".encodeToByteArray())
                assertEquals(ScaffoldingProtocol.STATUS_UNKNOWN_ERROR, malformed.status)
            }
        }

    @Test
    fun `removes player when its connection closes`() =
        withServer { server, port ->
            val connection = RawScaffoldingConnection(port)
            connection.request(
                ScaffoldingProtocol.PLAYER_PING,
                encodePlayerPing(testPlayer("machine-b", "Bob")),
            )
            runBlocking { awaitTrue { server.players.value.any { it.machineId == "machine-b" } } }
            connection.close()
            runBlocking { awaitTrue { server.players.value.isEmpty() } }
        }

    @Test
    fun `expires players after timeout using injected clock`() {
        val now = AtomicLong(1_000L)
        withServer(
            playerTimeoutMillis = 100,
            cleanupIntervalMillis = 10,
            clock = { now.get() },
        ) { server, port ->
            RawScaffoldingConnection(port).use { connection ->
                connection.request(
                    ScaffoldingProtocol.PLAYER_PING,
                    encodePlayerPing(testPlayer("machine-c", "Carol")),
                )
                runBlocking { awaitTrue { server.players.value.isNotEmpty() } }
                now.set(1_500L)
                runBlocking { awaitTrue { server.players.value.isEmpty() } }
            }
        }
    }

    @Test
    fun `closes connection on invalid frame and keeps serving`() {
        val errors = CopyOnWriteArrayList<Throwable>()
        withServer(onError = { failure -> errors += failure }) { _, port ->
            val socket = Socket()
            socket.connect(InetSocketAddress(TEST_HOST, port), TEST_TIMEOUT_MILLIS.toInt())
            socket.soTimeout = TEST_TIMEOUT_MILLIS.toInt()
            socket.use {
                it.getOutputStream().write(byteArrayOf(0))
                it.getOutputStream().flush()
                assertEquals(-1, it.getInputStream().read(ByteArray(8)))
            }
            runBlocking { awaitTrue { errors.isNotEmpty() } }

            RawScaffoldingConnection(port).use { connection ->
                assertEquals(
                    ScaffoldingProtocol.STATUS_OK,
                    connection.request(ScaffoldingProtocol.PING).status,
                )
            }
        }
    }

    @Test
    fun `survives peer closing before any frame`() =
        withServer { _, port ->
            Socket().use { socket ->
                socket.connect(InetSocketAddress(TEST_HOST, port), TEST_TIMEOUT_MILLIS.toInt())
            }
            RawScaffoldingConnection(port).use { connection ->
                assertEquals(
                    ScaffoldingProtocol.STATUS_OK,
                    connection.request(ScaffoldingProtocol.PING).status,
                )
            }
        }

    @Test
    fun `start reports bound port and close stops listening`() {
        val server = ScaffoldingServer()
        try {
            assertFailsWith<IllegalStateException> { server.port }
            val bound = server.start().getOrThrow()
            assertEquals(bound, server.port)
            assertTrue(server.start().isFailure)
        } finally {
            server.close()
        }
        assertFailsWith<IOException> {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(TEST_HOST, server.port), 500)
            }
        }
    }
}
