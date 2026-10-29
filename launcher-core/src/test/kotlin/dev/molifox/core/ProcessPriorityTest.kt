// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证游戏进程优先级的持久化容错、Windows 名称映射与失败降级。

package dev.molifox.core

import kotlin.test.*
import org.junit.jupiter.api.Test

class ProcessPriorityTest {
    @Test
    fun `unknown persisted priority falls back to Normal`() {
        val json = gson.toJsonTree(LauncherSettings()).asJsonObject
        json.addProperty("processPriority", "Ultra")
        assertEquals(
            ProcessPriority.Normal,
            gson.fromJson(json, LauncherSettings::class.java).processPriority,
        )
        json.remove("processPriority")
        assertEquals(
            ProcessPriority.Normal,
            gson.fromJson(json, LauncherSettings::class.java).processPriority,
        )
        json.addProperty("processPriority", "VeryHigh")
        assertEquals(
            ProcessPriority.VeryHigh,
            gson.fromJson(json, LauncherSettings::class.java).processPriority,
        )
        assertEquals(
            "High",
            gson.toJsonTree(LauncherSettings(processPriority = ProcessPriority.High))
                .asJsonObject
                .get("processPriority")
                .asString,
        )
    }

    @Test
    fun `priority names map to Windows priority classes`() {
        assertEquals("BelowNormal", windowsProcessPriorityName(ProcessPriority.Low))
        assertEquals("Normal", windowsProcessPriorityName(ProcessPriority.Normal))
        assertEquals("AboveNormal", windowsProcessPriorityName(ProcessPriority.High))
        assertEquals("High", windowsProcessPriorityName(ProcessPriority.VeryHigh))
    }

    @Test
    fun `non windows platform does nothing and does not throw`() {
        var executed = false
        val outcome =
            applyGameProcessPriority(ProcessPriority.High, 4242, osName = "Linux") {
                executed = true
            }
        assertNull(outcome.message)
        assertFalse(outcome.warning)
        assertFalse(executed)
    }

    @Test
    fun `windows success logs one line with label and pid`() {
        val commands = mutableListOf<List<String>>()
        val outcome =
            applyGameProcessPriority(ProcessPriority.VeryHigh, 1234, osName = "Windows 11") {
                commands += it
            }
        assertFalse(outcome.warning)
        assertEquals("游戏进程优先级：超高（pid 1234）", outcome.message)
        assertTrue(commands.single().joinToString(" ").contains("High"))
        assertFalse(commands.single().joinToString(" ").contains("RealTime"))
    }

    @Test
    fun `failing apply returns a warning instead of throwing`() {
        val attempts = mutableListOf<List<String>>()
        val outcome =
            applyGameProcessPriority(ProcessPriority.High, 1234, osName = "Windows 11") {
                attempts += it
                throw IllegalStateException("拒绝访问")
            }
        assertTrue(outcome.warning)
        assertEquals("设置优先级失败：拒绝访问", outcome.message)
        assertEquals(2, attempts.size)
        assertTrue(attempts.first().contains("wmic"))
        assertTrue(attempts.last().contains("powershell"))
        assertTrue(attempts.all { it.joinToString(" ").contains("AboveNormal") })
    }

    @Test
    fun `wmic failure falls back to powershell`() {
        val attempts = mutableListOf<List<String>>()
        val outcome =
            applyGameProcessPriority(ProcessPriority.Low, 77, osName = "Windows 10") { command ->
                attempts += command
                if (command.contains("wmic")) error("找不到 wmic")
            }
        assertFalse(outcome.warning)
        assertEquals("游戏进程优先级：低（pid 77）", outcome.message)
        assertEquals(2, attempts.size)
        assertTrue(attempts.last().contains("powershell"))
        assertTrue(attempts.last().joinToString(" ").contains("BelowNormal"))
    }
}