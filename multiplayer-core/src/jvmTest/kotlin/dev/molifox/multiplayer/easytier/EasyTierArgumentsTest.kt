// 校验 EasyTier 启动参数：网络码、hostname、白名单、RPC 仅回环与无 TUN。
package dev.molifox.multiplayer.easytier

import dev.molifox.multiplayer.protocol.ScaffoldingCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EasyTierArgumentsTest {
    private val code =
        ScaffoldingCode(
            code = CODE,
            networkName = "scaffolding-mc-YNZE-U61D",
            networkSecret = "2206-HXRG",
        )

    @Test
    fun `host carries fixed interface hostname and whitelisted ports`() {
        val request =
            EasyTierNodeRequest.host(
                code = code,
                machineId = MACHINE_ID,
                scaffoldingPort = SCAFFOLDING_PORT,
                minecraftPort = MINECRAFT_PORT,
            )
        val arguments = EasyTierArguments.build(request, RPC_PORT)

        assertTrue(
            arguments.containsAll(
                listOf(
                    "--no-tun",
                    "--multi-thread",
                    "--enable-kcp-proxy",
                    "--enable-quic-proxy",
                    "--p2p-only",
                ),
            ),
        )
        assertTrue(arguments.containsAll(listOf("--private-mode", "true")))
        assertTrue(arguments.containsAll(listOf("--network-name", code.networkName)))
        assertTrue(arguments.containsAll(listOf("--network-secret", code.networkSecret)))
        assertTrue(arguments.containsAll(listOf("--machine-id", MACHINE_ID)))
        assertTrue(
            arguments.containsAll(listOf("--hostname", "scaffolding-mc-server-$SCAFFOLDING_PORT")),
        )
        assertTrue(arguments.containsAll(listOf("--rpc-portal", "127.0.0.1:$RPC_PORT")))
        assertTrue(arguments.containsAll(listOf("-i", EasyTierArguments.HOST_INTERFACE)))
        assertTrue(arguments.containsAll(listOf("--tcp-whitelist", SCAFFOLDING_PORT.toString())))
        assertTrue(arguments.containsAll(listOf("--tcp-whitelist", MINECRAFT_PORT.toString())))
        assertTrue(arguments.containsAll(listOf("--udp-whitelist", MINECRAFT_PORT.toString())))
        assertFalse(arguments.contains("-d"))
        assertFalse(arguments.contains("--disable-sys-hole-punching"))
    }

    @Test
    fun `guest uses dhcp and does not declare host identity`() {
        val request = EasyTierNodeRequest.guest(code, MACHINE_ID, hostname = GUEST_HOSTNAME)
        val arguments = EasyTierArguments.build(request, RPC_PORT)

        assertTrue(arguments.contains("-d"))
        assertFalse(arguments.contains("-i"))
        assertTrue(arguments.containsAll(listOf("--hostname", GUEST_HOSTNAME)))
        assertTrue(arguments.containsAll(listOf("--tcp-whitelist", "0")))
        assertEquals(EasyTierRelays.DEFAULT.size, arguments.count { it == "-p" })
    }

    @Test
    fun `relays deduplicate default nodes and keep custom ones`() {
        val request =
            EasyTierNodeRequest.guest(
                code = code,
                machineId = MACHINE_ID,
                extraRelays = listOf(EasyTierRelays.DEFAULT.first(), "udp://custom.example:11010"),
            )
        val relays = relaysOf(EasyTierArguments.build(request, RPC_PORT))

        assertEquals(3, relays.size)
        assertEquals(1, relays.count { it == EasyTierRelays.DEFAULT.first() })
        assertTrue(relays.contains("udp://custom.example:11010"))
    }

    @Test
    fun `rejects incomplete or invalid requests`() {
        val guest = EasyTierNodeRequest.guest(code, MACHINE_ID, hostname = GUEST_HOSTNAME)
        assertFailsWith<IllegalArgumentException> { EasyTierArguments.build(guest, 0) }
        assertFailsWith<IllegalArgumentException> {
            EasyTierArguments.build(guest.copy(networkName = " "), RPC_PORT)
        }
        assertFailsWith<IllegalArgumentException> {
            EasyTierArguments.build(guest.copy(networkSecret = ""), RPC_PORT)
        }
        assertFailsWith<IllegalArgumentException> {
            EasyTierArguments.build(
                EasyTierNodeRequest.host(code, MACHINE_ID, 0, MINECRAFT_PORT),
                RPC_PORT,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            EasyTierArguments.build(
                EasyTierNodeRequest.host(code, MACHINE_ID, SCAFFOLDING_PORT, MINECRAFT_PORT),
                70_000,
            )
        }
    }

    @Test
    fun `host hostname is parseable by the guest rule`() {
        val hostname = EasyTierArguments.hostHostname(SCAFFOLDING_PORT)

        assertEquals("scaffolding-mc-server-$SCAFFOLDING_PORT", hostname)
        assertEquals(SCAFFOLDING_PORT, EasyTierPeers.portFromHostname(hostname))
    }

    /** 把 `-p <地址>` 拆出来，便于断言中继节点。 */
    private fun relaysOf(arguments: List<String>): List<String> =
        arguments.windowed(2).filter { pair -> pair[0] == "-p" }.map { pair -> pair[1] }

    private companion object {
        const val CODE = "U/YNZE-U61D-2206-HXRG"
        const val MACHINE_ID = "machine-0001"
        const val SCAFFOLDING_PORT = 45_678
        const val MINECRAFT_PORT = 25_565
        const val RPC_PORT = 54_321
        const val GUEST_HOSTNAME = "8c5d6f2a-guest"
    }
}
