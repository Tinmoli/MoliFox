// 房主会话：服务端/节点参数、存档退出看门狗与关闭顺序。
package dev.molifox.multiplayer.lobby

import dev.molifox.multiplayer.common.LanWorld
import dev.molifox.multiplayer.easytier.EasyTierArguments
import dev.molifox.multiplayer.easytier.EasyTierRole
import dev.molifox.multiplayer.protocol.RoomCode
import dev.molifox.multiplayer.protocol.RoomCodeRandom
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LobbyHostSessionTest {
    private val code = RoomCode.generate(RoomCodeRandom { ByteArray(BYTES) { 7 } })

    @Test
    fun startsScaffoldingAndEasyTierWithHostContract() =
        runBlocking {
            val node = FakeEasyTierNode(fakeCli { "[]" }.first)
            var closed: String? = null
            val session =
                LobbyHostSession(
                    code = code,
                    world = WORLD,
                    machineId = "host-machine",
                    playerName = "Alice",
                    runtime = node,
                    logger = { _, _ -> },
                    onClosed = { closed = it },
                    probe = { true },
                    watchdogIntervalMillis = SHORT_INTERVAL,
                )
            try {
                session.start().getOrThrow()
                val scaffoldingPort = session.scaffoldingPort
                assertEquals(
                    EasyTierArguments.hostHostname(scaffoldingPort),
                    node.request?.hostname,
                )
                assertEquals(EasyTierRole.HOST, node.request?.role)
                assertEquals(listOf(scaffoldingPort, WORLD.port), node.request?.whitelistedPorts)
                assertEquals(code.networkName, node.request?.networkName)
                awaitUntil { session.players.value.isNotEmpty() }
                assertEquals(
                    "Alice",
                    session.players.value
                        .single()
                        .name,
                )
                assertNull(closed)
            } finally {
                session.close()
            }
            assertEquals(1, node.stops)
        }

    @Test
    fun closedWorldReportsOnceAndStopsResources() =
        runBlocking<Unit> {
            val node = FakeEasyTierNode(fakeCli { "[]" }.first)
            var closedCount = 0
            var closedMessage: String? = null
            val session =
                LobbyHostSession(
                    code = code,
                    world = WORLD,
                    machineId = "host-machine",
                    playerName = "Alice",
                    runtime = node,
                    logger = { _, _ -> },
                    onClosed = {
                        closedCount++
                        closedMessage = it
                    },
                    probe = { false },
                    watchdogIntervalMillis = SHORT_INTERVAL,
                )
            session.start().getOrThrow()
            val port = session.scaffoldingPort
            awaitUntil { closedMessage != null }
            assertEquals("存档已退出，房间已关闭。", closedMessage)
            session.close()
            session.close()
            assertEquals(1, closedCount)
            assertEquals(1, node.stops)
            assertFailsWith<IOException> { probePort(port) }
        }

    @Test
    fun runtimeExitClosesRoom() =
        runBlocking {
            val node = FakeEasyTierNode(fakeCli { "[]" }.first)
            var closed: String? = null
            val session =
                LobbyHostSession(
                    code = code,
                    world = WORLD,
                    machineId = "host-machine",
                    playerName = "Alice",
                    runtime = node,
                    logger = { _, _ -> },
                    onClosed = { closed = it },
                    probe = { true },
                    watchdogIntervalMillis = SHORT_INTERVAL,
                )
            try {
                session.start().getOrThrow()
                node.exit(code = 3)
                awaitUntil { closed != null }
                val message = closed
                assertTrue(message != null && message.contains("EasyTier"))
            } finally {
                session.close()
            }
        }

    @Test
    fun startFailureLeavesNoRunningNode() =
        runBlocking {
            val node = FakeEasyTierNode(startFailure = IllegalStateException("端口被占用"))
            val session =
                LobbyHostSession(
                    code = code,
                    world = WORLD,
                    machineId = "host-machine",
                    playerName = "Alice",
                    runtime = node,
                    logger = { _, _ -> },
                    onClosed = { },
                    probe = { true },
                )
            val result = session.start()
            assertTrue(result.isFailure)
            session.close()
            assertEquals(1, node.stops)
        }

    private fun probePort(port: Int) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), TIMEOUT_MILLIS)
        }
    }

    private companion object {
        const val BYTES = 16
        const val SHORT_INTERVAL = 30L
        const val TIMEOUT_MILLIS = 300
        val WORLD = LanWorld(name = "测试世界", version = "1.21", port = 25_565)
    }
}
