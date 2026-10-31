// LAN 广播报文解析的纯函数测试：合法、缺 MOTD 与各种非法输入。
package dev.molifox.multiplayer.lan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MinecraftLanPacketTest {
    @Test
    fun `parses motd and ad port`() {
        val parsed = parseLanPacket("[MOTD]我的世界[/MOTD][AD]25565[/AD]")
        assertEquals("我的世界", parsed?.motd)
        assertEquals(25565, parsed?.port)
    }

    @Test
    fun `accepts packet without motd`() {
        assertEquals(25565, parseLanPacket("[AD]25565[/AD]")?.port)
    }

    @Test
    fun `trims whitespace around port`() {
        assertEquals(25565, parseLanPacket("[MOTD]x[/MOTD][AD] 25565 [/AD]")?.port)
    }

    @Test
    fun `rejects packet without ad section`() {
        assertNull(parseLanPacket("[MOTD]x[/MOTD]"))
        assertNull(parseLanPacket(""))
    }

    @Test
    fun `rejects non numeric port`() {
        assertNull(parseLanPacket("[AD]not-a-port[/AD]"))
        assertNull(parseLanPacket("[AD][/AD]"))
    }

    @Test
    fun `rejects out of range port`() {
        assertNull(parseLanPacket("[AD]0[/AD]"))
        assertNull(parseLanPacket("[AD]65536[/AD]"))
        assertNull(parseLanPacket("[AD]-1[/AD]"))
    }
}
