// LAN 发现测试：回环 UDP 注入 + 假 ping，验证解析、去重、忽略坏包与 stop 语义。
package dev.molifox.multiplayer.lan

import dev.molifox.multiplayer.common.LanWorld
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MinecraftLanDiscoveryTest {
    @Test
    fun `publishes world discovered from lan packet`() {
        withDiscovery(FakePinger()) { discovery, port ->
            sendLanPacket(port, lanPacket(TEST_WORLD_NAME, 25565))
            awaitTrue { discovery.worlds.value.isNotEmpty() }
            assertEquals(
                listOf(LanWorld(TEST_WORLD_NAME, TEST_VERSION, 25565)),
                discovery.worlds.value,
            )
        }
    }

    @Test
    fun `pings each port only once`() {
        val pinger = FakePinger()
        withDiscovery(pinger) { discovery, port ->
            sendLanPacket(port, lanPacket("first", 25565))
            awaitTrue { discovery.worlds.value.size == 1 }
            sendLanPacket(port, lanPacket("second", 25565))
            assertStaysFalse { pinger.calls.size > 1 }
            assertEquals(listOf(25565), pinger.calls)
            assertEquals(1, discovery.worlds.value.size)
        }
    }

    @Test
    fun `ignores malformed packets`() {
        val pinger = FakePinger()
        withDiscovery(pinger) { discovery, port ->
            val malformed =
                listOf(
                    "",
                    "hello",
                    "[MOTD]x[/MOTD]",
                    "[AD]not-a-port[/AD]",
                    "[AD]0[/AD]",
                    "[AD]65536[/AD]",
                )
            malformed.forEach { raw -> sendLanPacket(port, raw) }
            assertStaysFalse { discovery.worlds.value.isNotEmpty() }
            assertTrue(pinger.calls.isEmpty())
        }
    }

    @Test
    fun `stops updating after stop`() {
        val pinger = FakePinger()
        withDiscovery(pinger) { discovery, port ->
            sendLanPacket(port, lanPacket("first", 25565))
            awaitTrue { discovery.worlds.value.size == 1 }
            discovery.stop()
            val published = discovery.worlds.value
            val calls = pinger.calls.size
            sendLanPacket(port, lanPacket("second", 25566))
            assertStaysFalse {
                pinger.calls.size != calls || discovery.worlds.value != published
            }
        }
    }

    @Test
    fun `start and stop are idempotent`() {
        val discovery = MinecraftLanDiscovery(listenPort = 0, pinger = FakePinger())
        try {
            val first = discovery.start().getOrThrow()
            assertEquals(first, discovery.start().getOrThrow())
            discovery.stop()
            discovery.stop()
            val restarted = discovery.start().getOrThrow()
            sendLanPacket(restarted, lanPacket(TEST_WORLD_NAME, 25565))
            awaitTrue { discovery.worlds.value.size == 1 }
        } finally {
            discovery.close()
        }
        assertTrue(discovery.start().isFailure)
    }

    @Test
    fun `reports bind failure without crashing`() {
        val occupied = DatagramSocket(null)
        occupied.reuseAddress = false
        occupied.bind(InetSocketAddress(InetAddress.getByName(ANY_TEST_ADDRESS), 0))
        try {
            val discovery =
                MinecraftLanDiscovery(
                    listenPort = occupied.localPort,
                    pinger = FakePinger(),
                    reuseAddress = false,
                )
            try {
                val result = discovery.start()
                assertTrue(result.isFailure)
                assertTrue(
                    result
                        .exceptionOrNull()
                        ?.message
                        .orEmpty()
                        .contains("端口"),
                )
            } finally {
                discovery.close()
            }
        } finally {
            occupied.close()
        }
    }
}

private const val ANY_TEST_ADDRESS = "0.0.0.0"
