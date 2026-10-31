// 校验旧版 25 字符房间码的只解析兼容：向量、归一化、端口与拒绝路径。
package dev.molifox.multiplayer.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LegacyRoomCodeTest {
    @Test
    fun `parses documented legacy code`() {
        val parsed = LegacyRoomCodeParser.parse("12345-6789A-BCDEF-GHJKL-MNPQU")
        assertEquals("12345-6789A-BCDEF-GHJKL-MNPQU", parsed?.code)
        assertEquals("123456789abcdef", parsed?.networkName)
        assertEquals("ghjklmnpqu", parsed?.networkSecret)
        assertEquals(3009, parsed?.port)
    }

    @Test
    fun `parses all z legacy code with maximum port`() {
        val parsed = LegacyRoomCodeParser.parse("ZZZZZ-ZZZZZ-ZZZZZ-ZZZZZ-ZZZZA")
        assertEquals(65535, parsed?.port)
        assertEquals("zzzzzzzzzzzzzzz", parsed?.networkName)
        assertEquals("zzzzzzzzza", parsed?.networkSecret)
    }

    @Test
    fun `accepts compact lowercase embedded and normalized forms`() {
        val canonical = LegacyRoomCodeParser.parse("12345-6789A-BCDEF-GHJKL-MNPQU")
        assertEquals(canonical, LegacyRoomCodeParser.parse("123456789ABCDEFGHJKLMNPQU"))
        assertEquals(canonical, LegacyRoomCodeParser.parse("12345-6789a-bcdef-ghjkl-mnpqu"))
        assertEquals(canonical, LegacyRoomCodeParser.parse("邀请码 12345-6789A-BCDEF-GHJKL-MNPQU 请复制"))
        assertEquals(canonical, LegacyRoomCodeParser.parse("I2345-6789A-BCDEF-GHJKL-MNPQU"))
    }

    @Test
    fun `room code entry point delegates legacy parsing`() {
        assertEquals(3009, RoomCode.parseLegacy("12345-6789A-BCDEF-GHJKL-MNPQU")?.port)
        assertNull(RoomCode.parseLegacy("U/YNZE-U61D-2206-HXRG"))
    }

    @Test
    fun `rejects invalid legacy codes`() {
        assertNull(LegacyRoomCodeParser.parse(""))
        assertNull(LegacyRoomCodeParser.parse("12345-6789A-BCDEF-GHJKL-MNPQV"))
        assertNull(LegacyRoomCodeParser.parse("00000-00000-00000-00000-00000"))
        assertNull(LegacyRoomCodeParser.parse("12345-6789A-BCDEF-GHJKL-MNPQ"))
        assertNull(LegacyRoomCodeParser.parse("1234-56789A-BCDEF-GHJKL-MNPQU"))
        assertNull(LegacyRoomCodeParser.parse("12345-6789A-BCDEF-GHJKL-MNPQ!"))
        assertNull(LegacyRoomCodeParser.parse("legacy 12345-6789A-BCDEF-GHJKL-MNPQV extra"))
    }
}
