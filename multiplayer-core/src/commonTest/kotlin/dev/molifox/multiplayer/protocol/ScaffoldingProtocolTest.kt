// 校验 Scaffolding 协议常量与固定格式协议体编解码。
package dev.molifox.multiplayer.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScaffoldingProtocolTest {
    @Test
    fun `standard protocol names match the specification`() {
        assertEquals("c:ping", ScaffoldingProtocol.PING)
        assertEquals("c:protocols", ScaffoldingProtocol.PROTOCOLS)
        assertEquals("c:server_port", ScaffoldingProtocol.SERVER_PORT)
        assertEquals("c:player_ping", ScaffoldingProtocol.PLAYER_PING)
        assertEquals("c:player_profiles_list", ScaffoldingProtocol.PLAYER_PROFILES_LIST)
        assertEquals(
            listOf(
                "c:ping",
                "c:protocols",
                "c:server_port",
                "c:player_ping",
                "c:player_profiles_list",
            ),
            ScaffoldingProtocol.STANDARD_PROTOCOLS,
        )
        assertTrue(ScaffoldingProtocol.isStandardProtocol("c:ping"))
        assertFalse(ScaffoldingProtocol.isStandardProtocol("c:server_addresses"))
    }

    @Test
    fun `classifies response statuses`() {
        assertTrue(ScaffoldingProtocol.isKnownStatus(0))
        assertTrue(ScaffoldingProtocol.isProtocolError(32))
        assertTrue(ScaffoldingProtocol.isProtocolError(63))
        assertFalse(ScaffoldingProtocol.isProtocolError(64))
        assertTrue(ScaffoldingProtocol.isKnownStatus(255))
        assertFalse(ScaffoldingProtocol.isKnownStatus(1))
        assertFalse(ScaffoldingProtocol.isKnownStatus(64))
    }

    @Test
    fun `round trips server port as big endian`() {
        val encoded = ScaffoldingPayloads.encodeServerPort(25_565)
        assertContentEquals(byteArrayOf(0x63, 0xDD.toByte()), encoded)
        assertEquals(25_565, ScaffoldingPayloads.decodeServerPort(encoded))
        assertEquals(0, ScaffoldingPayloads.decodeServerPort(byteArrayOf(0, 0)))
        assertEquals(
            65_535,
            ScaffoldingPayloads.decodeServerPort(byteArrayOf(0xFF.toByte(), 0xFF.toByte())),
        )
        assertNull(ScaffoldingPayloads.decodeServerPort(byteArrayOf(1)))
        assertNull(ScaffoldingPayloads.decodeServerPort(ByteArray(3)))
    }

    @Test
    fun `rejects out of range server ports`() {
        assertFailsWith<IllegalArgumentException> { ScaffoldingPayloads.encodeServerPort(-1) }
        assertFailsWith<IllegalArgumentException> { ScaffoldingPayloads.encodeServerPort(65_536) }
    }

    @Test
    fun `round trips protocol name lists`() {
        val names = listOf("c:ping", "c:server_port")
        val encoded = ScaffoldingPayloads.encodeProtocolNames(names)
        assertContentEquals("c:ping\u0000c:server_port".encodeToByteArray(), encoded)
        assertEquals(names, ScaffoldingPayloads.decodeProtocolNames(encoded))
        assertTrue(ScaffoldingPayloads.encodeProtocolNames(emptyList()).isEmpty())
        assertEquals(emptyList<String>(), ScaffoldingPayloads.decodeProtocolNames(ByteArray(0)))
    }

    @Test
    fun `rejects malformed protocol name payloads`() {
        assertFailsWith<IllegalArgumentException> {
            ScaffoldingPayloads.encodeProtocolNames(listOf("C:ping"))
        }
        assertFailsWith<IllegalArgumentException> {
            ScaffoldingPayloads.encodeProtocolNames(listOf(""))
        }
        assertNull(ScaffoldingPayloads.decodeProtocolNames("c:ping\u0000".encodeToByteArray()))
        assertNull(
            ScaffoldingPayloads.decodeProtocolNames("c:ping\u0000C:ping".encodeToByteArray()),
        )
    }
}
