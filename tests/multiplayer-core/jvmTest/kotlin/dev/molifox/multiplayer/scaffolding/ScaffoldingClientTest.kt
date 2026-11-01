// Scaffolding 客户端的回环集成测试：握手、并发连接、断线回调与失败路径。
package dev.molifox.multiplayer.scaffolding

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScaffoldingClientTest {
    @Test
    fun `connects handshakes and reads players and server port`() =
        runBlocking<Unit> {
            val (server, port) = startTestServer()
            val client = ScaffoldingClient(TEST_HOST, port, testPlayer("machine-a", "Alice"))
            try {
                server.setMinecraftPort(25565)
                assertTrue(client.connect().isSuccess)
                assertEquals(25565, client.serverPort.value)
                val players = client.refreshPlayers().getOrThrow()
                assertEquals(listOf("machine-a"), players.map { it.machineId })
                awaitTrue { server.players.value.any { it.machineId == "machine-a" } }
            } finally {
                client.close()
                server.close()
            }
        }

    @Test
    fun `server port stays null until host registers minecraft port`() =
        runBlocking<Unit> {
            val (server, port) = startTestServer()
            val client = ScaffoldingClient(TEST_HOST, port, testPlayer("machine-b", "Bob"))
            try {
                assertTrue(client.connect().isSuccess)
                assertNull(client.serverPort.value)
                server.setMinecraftPort(25565)
                assertEquals(25565, client.refreshServerPort().getOrThrow())
                assertEquals(25565, client.serverPort.value)
            } finally {
                client.close()
                server.close()
            }
        }

    @Test
    fun `two clients connect concurrently and see each other`() =
        runBlocking<Unit> {
            val (server, port) = startTestServer()
            val alice = ScaffoldingClient(TEST_HOST, port, testPlayer("machine-a", "Alice"))
            val bob = ScaffoldingClient(TEST_HOST, port, testPlayer("machine-b", "Bob"))
            try {
                val results =
                    listOf(
                        async(Dispatchers.IO) { alice.connect() },
                        async(Dispatchers.IO) { bob.connect() },
                    ).awaitAll()
                results.forEach { result -> assertTrue(result.isSuccess) }
                awaitTrue { server.players.value.size == 2 }
                val names =
                    alice
                        .refreshPlayers()
                        .getOrThrow()
                        .map { it.name }
                        .toSet()
                assertEquals(setOf("Alice", "Bob"), names)
            } finally {
                alice.close()
                bob.close()
                server.close()
            }
        }

    @Test
    fun `fires disconnected callback when server closes`() =
        runBlocking<Unit> {
            val (server, port) = startTestServer()
            val disconnected = CompletableDeferred<Throwable?>()
            val client =
                ScaffoldingClient(
                    host = TEST_HOST,
                    port = port,
                    player = testPlayer("machine-c", "Carol"),
                    onDisconnected = { cause -> disconnected.complete(cause) },
                )
            try {
                assertTrue(client.connect().isSuccess)
                server.close()
                withTimeout(TEST_TIMEOUT_MILLIS) { disconnected.await() }
            } finally {
                client.close()
                server.close()
            }
        }

    @Test
    fun `connect fails fast when nothing listens`() =
        runBlocking<Unit> {
            val port = freePort()
            val client = ScaffoldingClient(TEST_HOST, port, testPlayer("machine-d", "Dave"))
            try {
                val result = client.connect()
                assertTrue(result.isFailure)
                assertTrue(result.exceptionOrNull() is IOException)
            } finally {
                client.close()
            }
        }
}

private fun freePort(): Int = ServerSocket(0).use { socket -> socket.localPort }
