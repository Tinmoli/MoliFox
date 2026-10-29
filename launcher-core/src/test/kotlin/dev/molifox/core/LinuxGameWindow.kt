// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责定位并操作 Linux 测试游戏窗口。

package dev.molifox.core

import com.sun.jna.NativeLong
import com.sun.jna.platform.unix.X11
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.NativeLongByReference
import com.sun.jna.ptr.PointerByReference

/** Sends ICCCM close directly to the owned client, without relying on a desktop WM relay. */
internal object LinuxGameWindow {
    fun close(window: GameWindow) {
        require(window.os == "linux")
        val x11 = X11.INSTANCE
        val display = checkNotNull(x11.XOpenDisplay(null)) { "Cannot open the test X11 display" }
        try {
            val target = X11.Window(window.id.toLong())
            // JNA true maps to -1, which is invalid for X11's wire Bool; false also works for
            // existing atoms.
            val pidProperty = x11.XInternAtom(display, "_NET_WM_PID", false)
            val value = PointerByReference()
            val type = X11.AtomByReference()
            val format = IntByReference()
            val count = NativeLongByReference()
            val remaining = NativeLongByReference()
            try {
                val status =
                    x11.XGetWindowProperty(
                        display,
                        target,
                        pidProperty,
                        NativeLong(0),
                        NativeLong(1),
                        false,
                        X11.XA_CARDINAL,
                        type,
                        format,
                        count,
                        remaining,
                        value,
                    )
                check(
                    status == X11.Success &&
                        format.value == 32 &&
                        count.value.toLong() == 1L &&
                        value.value != null &&
                        value.value.getNativeLong(0).toLong() == window.pid
                ) {
                    "The window no longer belongs to this test game; close was not sent"
                }
            } finally {
                value.value?.let { x11.XFree(it) }
            }
            val delete = x11.XInternAtom(display, "WM_DELETE_WINDOW", false)
            val protocols = PointerByReference()
            val protocolCount = IntByReference()
            try {
                check(
                    x11.XGetWMProtocols(display, target, protocols, protocolCount) != 0 &&
                        protocols.value != null &&
                        protocolCount.value in 1..256
                )
                check(
                    (0 until protocolCount.value).any {
                        protocols.value.getNativeLong(it.toLong() * NativeLong.SIZE).toLong() ==
                            delete.toLong()
                    }
                ) {
                    "The test game does not advertise WM_DELETE_WINDOW"
                }
            } finally {
                protocols.value?.let { x11.XFree(it) }
            }
            val message =
                X11.XClientMessageEvent().apply {
                    this.type = X11.ClientMessage
                    serial = NativeLong(0)
                    send_event = 1
                    this.display = display
                    this.window = target
                    message_type = x11.XInternAtom(display, "WM_PROTOCOLS", false)
                    this.format = 32
                    data.setType("l")
                    data.l = Array(5) { NativeLong(0) }
                    data.l[0] = delete
                    data.l[1] = NativeLong(X11.CurrentTime.toLong())
                }
            val event =
                X11.XEvent().apply {
                    setType(X11.XClientMessageEvent::class.java)
                    xclient = message
                    write()
                }
            check(
                x11.XSendEvent(display, target, 0, NativeLong(X11.NoEventMask.toLong()), event) != 0
            ) {
                "Cannot request normal game shutdown"
            }
            x11.XFlush(display)
        } finally {
            x11.XCloseDisplay(display)
        }
    }
}
