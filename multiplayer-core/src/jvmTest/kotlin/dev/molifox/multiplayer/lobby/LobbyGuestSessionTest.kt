// 房客会话：等待房主、Scaffolding 握手、端口转发链条与局域网广播。
package dev.molifox.multiplayer.lobby

import dev.molifox.multiplayer.common.ConnectionRoute
import dev.molifox.multiplayer.easytier.EasyTierForwardProtocol
import dev.molifox.multiplayer.lan.MinecraftLanBroadcast
import dev.molifox.multiplayer.protocol.RoomCode
import dev.molifox.multiplayer.protocol.RoomCodeRandom
import dev.molifox.multiplayer.scaffolding.ScaffoldingPlayer
import dev.molifox.multiplayer.scaffolding.ScaffoldingPlayerKind
import dev.molifox.multiplayer.scaffolding.ScaffoldingServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LobbyGuestSessionTest {
    private val code = RoomCode.generate(RoomCodeRandom { ByteArray(BYTES) { 3 } })

    @Test
    fun joinsRoomAndBroadcastsLocalWorld() =
        runBlocking {
            val minecraft = FakeTcpServer()
            val scaffolding = ScaffoldingServer(hostProfile = HOST_PLAYER)
            scaffolding.start().getOrThrow()
            scaffolding.setMinecraftPort(minecraft.port)
            val listener = DatagramSocket(0, InetAddress.getLoopbackAddress())
            val loopbackForwarder = LoopbackForwarder()
            val node = FakeEasyTierNode(fakeCli { hostPeersJson(scaffolding.port) }.first)
            val session = guestSession(node, loopbackForwarder, listener)
            try {
                session.start().getOrThrow()
                assertEquals(ConnectionRoute.Direct, session.connectionRoute)
                val localPort = session.localAddress.substringAfterLast(':').toInt()
                assertForwardChain(loopbackForwarder.calls, scaffolding.port, minecraft.port)
                assertEquals(listOf("Alice", "Bob"), awaitPlayers(scaffolding))
                assertReachable(localPort)
                assertLanPacket(listener, localPort, session.hostName)
            } finally {
                session.close()
                loopbackForwarder.close()
                scaffolding.close()
                minecraft.close()
                listener.close()
                assertEquals(1, node.stops)
            }
        }

    @Test
    fun retriesUntilP2pConnectionIsAvailable() =
        runBlocking {
            val minecraft = FakeTcpServer()
            val port = ServerSocket(0).use { socket -> socket.localPort }
            val scaffolding = ScaffoldingServer(requestedPort = port, hostProfile = HOST_PLAYER)
            scaffolding.setMinecraftPort(minecraft.port)
            val listener = DatagramSocket(0, InetAddress.getLoopbackAddress())
            val loopbackForwarder = LoopbackForwarder()
            val node = FakeEasyTierNode(fakeCli { hostPeersJson(port) }.first)
            val closed = mutableListOf<String>()
            val session =
                guestSession(
                    node,
                    loopbackForwarder,
                    listener,
                    p2pWaitTimeoutMillis = 5_000L,
                    p2pRetryIntervalMillis = 100L,
                    onClosed = closed::add,
                )
            // 先让连接失败几次，再启动 Scaffolding 服务端，模拟“房主已发现但 P2P 刚打通”。
            val starter =
                launch {
                    delay(P2P_START_DELAY_MILLIS)
                    scaffolding.start().getOrThrow()
                }
            try {
                session.start().getOrThrow()
                starter.join()
                assertTrue(session.localAddress.isNotBlank())
                assertTrue(closed.isEmpty(), "重试期间不应误报与房主断开：$closed")
                assertForwardChain(loopbackForwarder.calls, port, minecraft.port)
            } finally {
                session.close()
                loopbackForwarder.close()
                scaffolding.close()
                minecraft.close()
                listener.close()
                assertEquals(1, node.stops)
            }
        }

    @Test
    fun missingHostFailsWithReadableTimeout() =
        runBlocking {
            val loopbackForwarder = LoopbackForwarder()
            val node = FakeEasyTierNode(fakeCli { "[]" }.first)
            val session =
                LobbyGuestSession(
                    code = code,
                    machineId = "guest-machine",
                    playerName = "Bob",
                    runtime = node,
                    logger = { _, _ -> },
                    onClosed = { },
                    hostWaitTimeoutMillis = SHORT_WAIT_MILLIS,
                    pollIntervalMillis = POLL_INTERVAL_MILLIS,
                    forwarderFactory = { loopbackForwarder.forwarder },
                    broadcastFactory = { _, port ->
                        MinecraftLanBroadcast("unused", port, multicastEnabled = false)
                    },
                )
            try {
                val result = session.start()
                assertTrue(result.isFailure)
                val message = result.exceptionOrNull()?.message.orEmpty()
                assertTrue(message.contains("等待房主超时"), message)
                assertTrue(loopbackForwarder.calls.isEmpty())
            } finally {
                session.close()
                assertEquals(1, node.stops)
            }
        }

    private fun guestSession(
        node: FakeEasyTierNode,
        loopbackForwarder: LoopbackForwarder,
        listener: DatagramSocket,
        p2pWaitTimeoutMillis: Long = 30_000L,
        p2pRetryIntervalMillis: Long = 1_500L,
        onClosed: (String) -> Unit = {},
    ): LobbyGuestSession =
        LobbyGuestSession(
            code = code,
            machineId = "guest-machine",
            playerName = "Bob",
            runtime = node,
            logger = { _, _ -> },
            onClosed = onClosed,
            hostWaitTimeoutMillis = WAIT_TIMEOUT_MILLIS,
            pollIntervalMillis = POLL_INTERVAL_MILLIS,
            p2pWaitTimeoutMillis = p2pWaitTimeoutMillis,
            p2pRetryIntervalMillis = p2pRetryIntervalMillis,
            forwarderFactory = { loopbackForwarder.forwarder },
            broadcastFactory = { description, port ->
                MinecraftLanBroadcast(
                    description,
                    port,
                    targetPort = listener.localPort,
                    multicastEnabled = false,
                )
            },
            motdFactory = { host -> "$host 的世界" },
        )

    private fun assertForwardChain(
        calls: List<ForwardCall>,
        scaffoldingPort: Int,
        minecraftPort: Int,
    ) {
        assertEquals(4, calls.size)
        assertEquals(EasyTierForwardProtocol.TCP, calls[0].protocol)
        assertEquals(scaffoldingPort, calls[0].targetPort)
        assertEquals(EasyTierForwardProtocol.TCP, calls[2].protocol)
        assertEquals(minecraftPort, calls[2].targetPort)
    }

    private suspend fun awaitPlayers(scaffolding: ScaffoldingServer): List<String> {
        awaitUntil { scaffolding.players.value.size == PLAYER_COUNT }
        return scaffolding.players.value.map { it.name }
    }

    private fun assertReachable(localPort: Int) {
        Socket().use { it.connect(InetSocketAddress(LOOPBACK, localPort), CONNECT_TIMEOUT_MILLIS) }
    }

    private fun assertLanPacket(
        listener: DatagramSocket,
        localPort: Int,
        hostName: String,
    ) {
        listener.soTimeout = PACKET_TIMEOUT_MILLIS
        val buffer = ByteArray(PACKET_BUFFER_BYTES)
        val packet = DatagramPacket(buffer, buffer.size)
        listener.receive(packet)
        val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
        assertTrue(text.contains("[AD]$localPort[/AD]"), text)
        assertTrue(text.contains("$hostName 的世界"), text)
    }

    private companion object {
        const val BYTES = 16
        const val P2P_START_DELAY_MILLIS = 400L
        const val LOOPBACK = "127.0.0.1"
        const val PLAYER_COUNT = 2
        const val WAIT_TIMEOUT_MILLIS = 5_000L
        const val SHORT_WAIT_MILLIS = 300L
        const val POLL_INTERVAL_MILLIS = 20L
        const val CONNECT_TIMEOUT_MILLIS = 2_000
        const val PACKET_TIMEOUT_MILLIS = 5_000
        const val PACKET_BUFFER_BYTES = 512
        val HOST_PLAYER =
            ScaffoldingPlayer("host-machine", "Alice", kind = ScaffoldingPlayerKind.HOST)
    }
}
