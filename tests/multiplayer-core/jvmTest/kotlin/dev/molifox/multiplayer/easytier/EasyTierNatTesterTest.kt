// 网络类型诊断：STUN JSON 解析、编码映射与日志前缀容忍。
package dev.molifox.multiplayer.easytier

import dev.molifox.multiplayer.common.NatType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EasyTierNatTesterTest {
    @Test
    fun `parses nat codes public ips and ipv6 support`() {
        val json =
            """
            {
              "udp_nat_type": 6,
              "tcp_nat_type": 5,
              "last_update_time": 1793444809,
              "public_ip": ["36.157.201.232", "240e:3b1:1234::1"],
              "min_port": 48752,
              "max_port": 48858
            }
            """.trimIndent()

        val status = parseNatDiagnostics(json).getOrThrow()

        assertEquals(NatType.Symmetric, status.udpNatType)
        assertEquals(NatType.SymmetricEasy, status.tcpNatType)
        assertTrue(status.supportsIpv6)
        assertEquals(2, status.publicIps.size)
    }

    @Test
    fun `tolerates log prefix and missing ipv6`() {
        val output =
            """[WARN] something before json
               {"udp_nat_type":2,"tcp_nat_type":0,"public_ip":["1.2.3.4"]}"""

        val status = parseNatDiagnostics(output).getOrThrow()

        assertEquals(NatType.FullCone, status.udpNatType)
        assertEquals(NatType.OpenInternet, status.tcpNatType)
        assertFalse(status.supportsIpv6)
    }

    @Test
    fun `maps every known code and treats unknown as unknown`() {
        val expected =
            listOf(
                NatType.OpenInternet,
                NatType.NoPat,
                NatType.FullCone,
                NatType.Restricted,
                NatType.PortRestricted,
                NatType.SymmetricEasy,
                NatType.Symmetric,
                NatType.SymmetricFirewall,
                NatType.UdpBlocked,
            )
        expected.forEachIndexed { code, type -> assertEquals(type, NatType.fromCode(code)) }
        assertEquals(NatType.Unknown, NatType.fromCode(99))
        assertEquals(NatType.Unknown, NatType.fromCode(null))
    }

    @Test
    fun `rejects output without json`() {
        assertTrue(parseNatDiagnostics("stun failed").isFailure)
        assertTrue(parseNatDiagnostics("[]").isFailure)
    }
}
