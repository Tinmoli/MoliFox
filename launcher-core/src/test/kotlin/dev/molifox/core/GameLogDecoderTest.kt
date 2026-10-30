// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证游戏进程日志的编码识别与流式解码的行为与边界。

package dev.molifox.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GameLogDecoderTest {
    @Test
    fun `XML errors retain their message throwable and severity`() {
        val logs = mutableListOf<DecodedGameLog>()
        val decoder = GameLogDecoder(logs::add)
        listOf(
            "<log4j:Event logger=\"game\" level=\"ERROR\" thread=\"Render thread\">",
            "<log4j:Message><![CDATA[Could not load resource]]></log4j:Message>",
            "<log4j:Throwable><![CDATA[java.io.IOException: missing",
            "\tat example.Main.run(Main.java:1)]]></log4j:Throwable>",
            "</log4j:Event>",
        ).forEach(decoder::accept)
        assertTrue(logs.single().error)
        assertTrue(logs.single().message.contains("Could not load resource"))
        assertTrue(logs.single().message.contains("example.Main.run"))
        assertFalse(logs.single().message.contains("log4j:"))
    }

    @Test
    fun `escaped messages CDATA plain output and incomplete records remain readable`() {
        val logs = mutableListOf<DecodedGameLog>()
        val decoder = GameLogDecoder(logs::add)
        decoder.accept(
            "<log4j:Event level=\"INFO\" thread=\"main\">" +
                "<log4j:Message>A &amp; B &#x4E2D;</log4j:Message></log4j:Event>",
        )
        decoder.accept(
            "<log4j:Event level=\"WARN\">" +
                "<log4j:Message><![CDATA[A & B]]></log4j:Message></log4j:Event>",
        )
        decoder.accept("MOLIFOX_GAME_STARTED")
        decoder.accept("<log4j:Event level=\"ERROR\">")
        decoder.flush()
        assertTrue(logs[0].message.endsWith("A & B 中"))
        assertFalse(logs[0].error)
        assertTrue(logs[1].message.endsWith("A & B"))
        assertEquals("MOLIFOX_GAME_STARTED", logs[2].message)
        assertTrue(logs.last().message.contains("log4j:Event"))
    }
}
