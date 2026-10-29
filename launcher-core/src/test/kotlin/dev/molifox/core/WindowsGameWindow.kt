// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责定位并操作 Windows 测试游戏窗口。

package dev.molifox.core

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.*
import com.sun.jna.ptr.IntByReference
import java.awt.Rectangle

internal object WindowsGameWindow {
    private fun handle(window: GameWindow) = HWND(Pointer.createConstant(window.id.toLong()))

    private fun owns(handle: HWND, pid: Long): Boolean {
        val actual = IntByReference()
        User32.INSTANCE.GetWindowThreadProcessId(handle, actual)
        return actual.value.toLong() == pid
    }

    fun find(pid: Long): GameWindow? {
        var found: GameWindow? = null
        User32.INSTANCE.EnumWindows(
            { handle, _ ->
                if (owns(handle, pid) && User32.INSTANCE.IsWindowVisible(handle)) {
                    val rect = RECT()
                    if (
                        User32.INSTANCE.GetWindowRect(handle, rect) &&
                            rect.right - rect.left > 200 &&
                            rect.bottom - rect.top > 200
                    ) {
                        found =
                            GameWindow(
                                "windows",
                                Pointer.nativeValue(handle.pointer).toString(),
                                pid,
                                Rectangle(
                                    rect.left,
                                    rect.top,
                                    rect.right - rect.left,
                                    rect.bottom - rect.top,
                                ),
                            )
                        return@EnumWindows false
                    }
                }
                true
            },
            null,
        )
        return found
    }

    fun activate(window: GameWindow): Boolean {
        val target = handle(window)
        if (!owns(target, window.pid)) return false
        User32.INSTANCE.ShowWindow(target, 9)
        User32.INSTANCE.BringWindowToTop(target)
        User32.INSTANCE.SetForegroundWindow(target)
        return target == User32.INSTANCE.GetForegroundWindow()
    }

    fun close(window: GameWindow) {
        val target = handle(window)
        check(owns(target, window.pid)) { "The game window no longer belongs to this test" }
        User32.INSTANCE.PostMessage(target, 0x0010, WPARAM(0), LPARAM(0))
    }

    fun clientSize(window: GameWindow): Pair<Int, Int> {
        val target = handle(window)
        check(owns(target, window.pid)) { "The game window no longer belongs to this test" }
        val rectangle = RECT()
        check(User32.INSTANCE.GetClientRect(target, rectangle))
        return (rectangle.right - rectangle.left) to (rectangle.bottom - rectangle.top)
    }
}
