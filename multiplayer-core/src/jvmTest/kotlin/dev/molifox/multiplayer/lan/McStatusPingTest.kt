// status 协议实现测试：假 TCP 服务器验证线格式，纯函数验证 JSON 映射。
package dev.molifox.multiplayer.lan

import dev.molifox.multiplayer.common.LanWorld
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class McStatusPingTest {
    @Test
    fun `pings fake status server and maps world fields`() {
        val server = FakeStatusServer("""{"version":{"name":"1.20.1"},"description":"Hello"}""")
        try {
            val world = McStatusPing().ping(TEST_HOST, server.port, TEST_TIMEOUT_MILLIS)
            assertEquals(LanWorld("Hello", "1.20.1", server.port), world)
            val handshake = readHandshake(server.frames.first())
            assertEquals(0, handshake.packetId)
            assertEquals(4, handshake.protocolVersion)
            assertEquals(TEST_HOST, handshake.address)
            assertEquals(server.port, handshake.port)
            assertEquals(1, handshake.nextState)
            assertContentEquals(byteArrayOf(0), server.frames[1])
        } finally {
            server.close()
        }
    }

    @Test
    fun `flattens component description and tolerates missing version`() {
        val json =
            """{"description":{"text":"A","extra":[{"text":"B"},""" +
                """{"text":"C","extra":[{"text":"D"}]}]}}"""
        assertEquals(LanWorld("ABCD", "", 25565), parseStatusJson(json, 25565))
    }

    @Test
    fun `flattens description array`() {
        val json = """{"description":[{"text":"X"},{"text":"Y"}]}"""
        assertEquals("XY", parseStatusJson(json, 25565)?.name)
    }

    @Test
    fun `falls back to default name when description is blank`() {
        assertEquals(DEFAULT_WORLD_NAME, parseStatusJson("""{"description":""}""", 25565)?.name)
    }

    @Test
    fun `rejects malformed or non object json`() {
        assertNull(parseStatusJson("{", 25565))
        assertNull(parseStatusJson("", 25565))
        assertNull(parseStatusJson("[]", 25565))
        assertNull(parseStatusJson("not json", 25565))
    }

    @Test
    fun `returns null when fake server replies malformed json`() {
        val server = FakeStatusServer("{oops")
        try {
            assertNull(McStatusPing().ping(TEST_HOST, server.port, TEST_TIMEOUT_MILLIS))
        } finally {
            server.close()
        }
    }

    @Test
    fun `returns null when nothing listens`() {
        val freePort = ServerSocket(0).use { it.localPort }
        assertNull(McStatusPing().ping(TEST_HOST, freePort, SHORT_TIMEOUT_MILLIS))
    }

    @Test
    fun `varint roundtrip covers boundaries`() {
        listOf(0, 1, 127, 128, 25565, 2_097_151, 2_097_152).forEach { value ->
            val encoded = ByteArrayOutputStream().apply { writeVarInt(this, value) }.toByteArray()
            assertEquals(value, readVarInt(ByteArrayInputStream(encoded)))
        }
    }

    private fun readHandshake(frame: ByteArray): HandshakeFields {
        val input = DataInputStream(ByteArrayInputStream(frame))
        val packetId = readVarInt(input)
        val protocolVersion = readVarInt(input)
        val addressLength = readVarInt(input) ?: 0
        val address = ByteArray(addressLength)
        input.readFully(address)
        val port = input.readUnsignedShort()
        val nextState = readVarInt(input)
        return HandshakeFields(packetId, protocolVersion, port, nextState, address.decodeToString())
    }
}

private data class HandshakeFields(
    val packetId: Int?,
    val protocolVersion: Int?,
    val port: Int,
    val nextState: Int?,
    val address: String,
)

private const val SHORT_TIMEOUT_MILLIS = 500L
