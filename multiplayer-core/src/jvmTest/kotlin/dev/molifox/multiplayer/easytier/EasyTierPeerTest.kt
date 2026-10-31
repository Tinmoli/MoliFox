// 校验 peer JSON 解析、hostname 端口解析与房主定位的异常分支。
package dev.molifox.multiplayer.easytier

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EasyTierPeerTest {
    @Test
    fun `parses cli peers with mixed output`() {
        val output =
            """
            INFO  using rpc portal 127.0.0.1:54321
            [
              {"hostname":"MoliFox-server-45678","ipv4":"10.114.51.41",
               "lat_ms":"12.5","loss_rate":"0%","nat_type":"PortRestricted","version":"2.6.4"},
              {"hostname":"7c9f-guest","ipv4":"10.114.51.99","lat_ms":"-"}
            ]
            """.trimIndent()

        val peers = EasyTierPeers.parse(output).getOrThrow()

        assertEquals(listOf("MoliFox-server-45678", "7c9f-guest"), peers.map { it.hostname })
        assertEquals(12.5, peers.first().latencyMillis)
        assertNull(peers.last().latencyMillis)
    }

    @Test
    fun `fails on missing or malformed json`() {
        assertTrue(EasyTierPeers.parse("network not ready").isFailure)
        assertTrue(EasyTierPeers.parse("[] trailing").isSuccess)
        assertTrue(EasyTierPeers.parse("[{broken").isFailure)
        assertTrue(EasyTierPeers.parse("{\"hostname\":\"x\"}").isFailure)
    }

    @Test
    fun `skips entries without hostname or address`() {
        val output =
            """
            [
              {"hostname":"","ipv4":"10.0.0.2"},
              {"hostname":"no-ip"},
              {"ipv4":"10.0.0.3"},
              {"hostname":"ok-node","ipv4":"10.0.0.4"}
            ]
            """.trimIndent()

        val peers = EasyTierPeers.parse(output).getOrThrow()

        assertEquals(1, peers.size)
        assertEquals("ok-node", peers.single().hostname)
    }

    @Test
    fun `locates host from hostname suffix`() {
        val peers =
            listOf(
                EasyTierPeer("7c9f-guest", "10.114.51.99", 20.0),
                EasyTierPeer("MoliFox-server-45678", "10.114.51.41", 3.0),
            )

        val host = EasyTierPeers.findHost(peers).getOrThrow()

        assertEquals(45_678, host.scaffoldingPort)
        assertEquals("10.114.51.41", host.peer.ipv4)
    }

    @Test
    fun `reports missing or duplicate host`() {
        assertTrue(EasyTierPeers.findHost(emptyList()).isFailure)
        assertTrue(
            EasyTierPeers.findHost(listOf(EasyTierPeer("guest", "10.0.0.2", null))).isFailure,
        )
        val duplicated =
            listOf(
                EasyTierPeer("MoliFox-server-1000", "10.0.0.1", null),
                EasyTierPeer("MoliFox-server-2000", "10.0.0.2", null),
            )
        assertTrue(EasyTierPeers.findHost(duplicated).isFailure)
    }

    @Test
    fun `parses only valid host ports`() {
        assertEquals(45_678, EasyTierPeers.portFromHostname("MoliFox-server-45678"))
        assertNull(EasyTierPeers.portFromHostname("MoliFox-server-"))
        assertNull(EasyTierPeers.portFromHostname("MoliFox-server-0"))
        assertNull(EasyTierPeers.portFromHostname("MoliFox-server-70000"))
        assertNull(EasyTierPeers.portFromHostname("other-host-25565"))
        assertNotNull(EasyTierPeers.portFromHostname("MoliFox-server-1"))
    }
}
