// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证打开文件夹的回退链：Desktop → cmd start → explorer，最后复制路径并提示。

package dev.molifox.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PathOpenerTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `desktop failure falls back to cmd start and logs both attempts`() {
        val target = temporary.resolve("instance").createDirectories()
        val launched = mutableListOf<List<String>>()
        val logs = mutableListOf<String>()
        val notices = mutableListOf<String>()
        val opener =
            PathOpener(
                osName = "windows",
                openWithDesktop = { throw IOException("CreateProcess error=5") },
                launch = { command -> launched += command },
                copyToClipboard = { false },
                log = { logs += it },
                notice = { notices += it },
            )

        assertTrue(opener.open(target))

        val path = target.toAbsolutePath().normalize().toString()
        assertEquals(listOf(listOf("cmd", "/c", "start", "", path)), launched)
        assertTrue(logs.any { it.contains("java.awt.Desktop") && it.contains("error=5") }, "$logs")
        assertTrue(logs.any { it.contains("已打开") && it.contains("cmd /c start") }, "$logs")
        assertTrue(notices.isEmpty(), "$notices")
    }

    @Test
    fun `all strategies failing copies the path and shows the required notice`() {
        val target = temporary.resolve("instance").createDirectories()
        val path = target.toAbsolutePath().normalize().toString()
        var clipboard = ""
        val notices = mutableListOf<String>()
        val opener =
            PathOpener(
                osName = "windows",
                openWithDesktop = { throw IOException("denied") },
                launch = { throw IOException("CreateProcess error=5") },
                copyToClipboard = { value ->
                    clipboard = value
                    true
                },
                log = {},
                notice = { notices += it },
            )

        assertFalse(opener.open(target))

        assertEquals(path, clipboard)
        assertEquals("系统拒绝打开（可能被安全软件拦截），路径已复制", notices.single())
    }

    @Test
    fun `explorer selects files and opens directories`() {
        val directory = temporary.resolve("logs").createDirectories()
        val file = temporary.resolve("latest.log")
        file.writeText("log")

        val directoryCommands = pathOpenCommands("windows", directory)
        assertEquals(
            listOf("explorer.exe", directory.absolutePathString()),
            directoryCommands.last().second,
        )

        val fileCommands = pathOpenCommands("windows", file)
        assertEquals(
            "explorer.exe",
            fileCommands.last().second.first(),
        )
        assertEquals("/select," + file.absolutePathString(), fileCommands.last().second.last())
        assertTrue(
            fileCommands.none {
                it.second.first() == "explorer.exe" && it.second.size == 2 &&
                    it.second.last() == file.absolutePathString()
            },
            "文件不能用 explorer.exe <path> 直接打开：$fileCommands",
        )
    }

    @Test
    fun `non windows platforms use open or xdg-open after desktop`() {
        val directory = temporary.resolve("mods").createDirectories()
        val path = directory.absolutePathString()
        assertEquals(listOf("open", path), pathOpenCommands("osx", directory).single().second)
        assertEquals(listOf("xdg-open", path), pathOpenCommands("linux", directory).single().second)
    }

    @Test
    fun `headless without desktop still opens through the command strategies`() {
        val directory = temporary.resolve("saves").createDirectories()
        val launched = mutableListOf<List<String>>()
        val opener =
            PathOpener(
                osName = "windows",
                openWithDesktop = null,
                launch = { command -> launched += command },
                copyToClipboard = { false },
                log = {},
                notice = {},
            )

        assertTrue(opener.open(directory))
        assertEquals(
            listOf(listOf("cmd", "/c", "start", "", directory.absolutePathString())),
            launched,
        )
    }
}
