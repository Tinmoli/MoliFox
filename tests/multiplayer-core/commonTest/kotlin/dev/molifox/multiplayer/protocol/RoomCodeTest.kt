// 校验 Scaffolding 房间码：固定向量、归一化、拒绝路径与随机往返。
package dev.molifox.multiplayer.protocol

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomCodeTest {
    @Test
    fun `parses documented code and derives network credentials`() {
        val parsed = RoomCode.parse("U/YNZE-U61D-2206-HXRG")
        assertEquals("U/YNZE-U61D-2206-HXRG", parsed?.code)
        assertEquals("MoliFox-YNZE-U61D", parsed?.networkName)
        assertEquals("2206-HXRG", parsed?.networkSecret)
    }

    @Test
    fun `parses zero and boundary vectors`() {
        assertEquals("U/0000-0000-0000-0000", RoomCode.parse("U/0000-0000-0000-0000")?.code)
        assertEquals("U/7000-0000-0000-0000", RoomCode.parse("U/7000-0000-0000-0000")?.code)
        assertEquals("U/EF0E-NPXH-SSSC-K7WC", RoomCode.parse("U/EF0E-NPXH-SSSC-K7WC")?.code)
    }

    @Test
    fun `rejects invalid room codes without throwing`() {
        assertNull(RoomCode.parse(""))
        assertNull(RoomCode.parse("not a room code"))
        assertNull(RoomCode.parse("U/YNZE-U61D-2206-HXRJ"))
        assertNull(RoomCode.parse("U/YNZEU61D2206HXRG"))
        assertNull(RoomCode.parse("U/YNZE-U61D-2206"))
        assertNull(RoomCode.parse("U/YNZ-EU61D-2206-HXRG"))
        assertNull(RoomCode.parse("U/YNZE-U61D-2206-HXR!"))
        assertNull(RoomCode.parse("12345-6789A-BCDEF-GHJKL-MNPQU"))
    }

    @Test
    fun `normalizes lowercase and i o confusables`() {
        val expected = RoomCode.parse("U/1100-0000-0000-0000")
        assertEquals(expected, RoomCode.parse("u/1100-0000-0000-0000"))
        assertEquals(expected, RoomCode.parse("U/IIOO-OOOO-OOOO-OOOO"))
        assertEquals(expected, RoomCode.parse("u/iioo-oooo-oooo-oooo"))
    }

    @Test
    fun `finds code inside pasted text`() {
        val parsed = RoomCode.parse("联机码：u/ynze-u61d-2206-hxrg，复制到启动器")
        assertEquals("U/YNZE-U61D-2206-HXRG", parsed?.code)
    }

    @Test
    fun `generates fixed vectors from injected random bytes`() {
        val seeded = RoomCode.generate(fixedRandom(bytesFromLong(123_456_789L)))
        assertEquals("U/0J2D-Q200-0000-0000", seeded.code)
        assertEquals("MoliFox-0J2D-Q200", seeded.networkName)
        assertEquals("0000-0000", seeded.networkSecret)
        assertEquals("U/0000-0000-0000-0000", RoomCode.generate(fixedRandom(ByteArray(16))).code)
        assertEquals(
            "U/7000-0000-0000-0000",
            RoomCode.generate(fixedRandom(bytesFromLong(7L))).code,
        )
    }

    @Test
    fun `generates boundary vector at two to the eightieth`() {
        val boundary = ByteArray(16).also { bytes -> bytes[5] = 1 }
        val generated = RoomCode.generate(fixedRandom(boundary))
        assertEquals("U/EF0E-NPXH-SSSC-K7WC", generated.code)
        assertEquals("MoliFox-EF0E-NPXH", generated.networkName)
        assertEquals("SSSC-K7WC", generated.networkSecret)
    }

    @Test
    fun `generated codes parse back and stay canonical`() {
        val random = Random(20_251_031)
        val source = RoomCodeRandom { size -> ByteArray(size).also { random.nextBytes(it) } }
        repeat(ROUND_TRIP_COUNT) {
            val generated = RoomCode.generate(source)
            assertEquals(21, generated.code.length)
            assertTrue(generated.code.startsWith("U/"))
            assertEquals(generated, RoomCode.parse(generated.code))
        }
    }

    @Test
    fun `rejects random source with wrong byte count`() {
        assertFailsWith<IllegalArgumentException> {
            RoomCode.generate(RoomCodeRandom { ByteArray(15) })
        }
    }
}

private const val ROUND_TRIP_COUNT = 10_000
private const val BYTE_BITS = 8
private const val BYTE_MASK = 0xFFL

private fun fixedRandom(bytes: ByteArray): RoomCodeRandom = RoomCodeRandom { bytes.copyOf() }

private fun bytesFromLong(value: Long): ByteArray {
    val bytes = ByteArray(16)
    var remaining = value
    for (index in bytes.indices.reversed()) {
        bytes[index] = (remaining and BYTE_MASK).toByte()
        remaining = remaining ushr BYTE_BITS
    }
    return bytes
}
