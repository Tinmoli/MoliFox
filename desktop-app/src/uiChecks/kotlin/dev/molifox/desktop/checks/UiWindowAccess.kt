// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 提供测试窗口的可访问节点、可见边界与焦点，限制操作范围为该窗口及其子窗口。

package dev.molifox.desktop.checks

import TestWindowFocus
import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef.HWND
import java.awt.EventQueue
import java.awt.IllegalComponentStateException
import java.awt.Rectangle
import java.awt.Window
import java.util.concurrent.atomic.AtomicReference
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleRole
import javax.accessibility.AccessibleText

internal class UiWindowAccess(
    private val mainWindow: Window,
) {
    fun matches(label: String): List<AccessibleContext> =
        contexts().filter { matchesLabel(it, label) && bounds(it) != null }

    fun bounds(context: AccessibleContext): Rectangle? =
        try {
            val component = context.accessibleComponent
            if (component == null || !component.isShowing) {
                null
            } else {
                Rectangle(component.locationOnScreen, component.size)
                    .intersection(mainWindow.bounds)
                    .takeIf { it.width > 0 && it.height > 0 }
            }
        } catch (_: IllegalComponentStateException) {
            // 页面切换时节点可能刚被移除，此时重新查询而不复用失效坐标。
            null
        }

    fun focus() {
        val target =
            onTestUiThread {
                val candidates = Window.getWindows().filter { it.isShowing && owned(it) }
                candidates.lastOrNull { it.isActive } ?: candidates.last { it.isFocusableWindow }
            }
        check(TestWindowFocus.activate(HWND(Native.getWindowPointer(target)))) { "无法激活测试自己的窗口" }
    }

    fun captureBounds(): Rectangle =
        onTestUiThread { Rectangle(mainWindow.locationOnScreen, mainWindow.size) }

    fun activate(
        label: String,
        area: Rectangle,
    ): Boolean =
        onTestUiThread {
            val context =
                matches(label).lastOrNull {
                    bounds(it) == area && it.accessibleRole != AccessibleRole.TEXT &&
                        it.accessibleComponent.isEnabled && it.accessibleAction != null
                } ?: return@onTestUiThread false
            val actions = context.accessibleAction
            val click =
                (0 until actions.accessibleActionCount).firstOrNull { index ->
                    val description = actions.getAccessibleActionDescription(index).orEmpty()
                    description.contains("click", ignoreCase = true) ||
                        description.contains("点击") || description.contains("单击")
                } ?: return@onTestUiThread false
            actions.doAccessibleAction(click)
        }

    fun diagnostics(): String =
        onTestUiThread {
            val windows =
                Window.getWindows().joinToString("\n") {
                    "WINDOW ${it.javaClass.name} shown=${it.isShowing} bounds=${it.bounds} " +
                        "owner=${it.owner?.javaClass?.name} owned=${owned(it)}"
                }
            windows + "\n" +
                contexts().mapNotNull { it.accessibleName }.distinct().joinToString("\n")
        }

    private fun matchesLabel(
        context: AccessibleContext,
        label: String,
    ): Boolean {
        val name = context.accessibleName.orEmpty()
        if (name == label || name.split(',').any { it.trim() == label }) return true
        val text = context.accessibleText ?: return false
        if (text.charCount != label.length) return false
        return label.indices.all { index ->
            text.getAtIndex(AccessibleText.CHARACTER, index) == label[index].toString()
        }
    }

    private fun contexts(): List<AccessibleContext> =
        buildList {
            for (window in Window.getWindows().filter { it.isShowing && owned(it) }) {
                collect(window.accessibleContext, this)
            }
        }

    private fun collect(
        context: AccessibleContext?,
        output: MutableList<AccessibleContext>,
    ) {
        if (context == null) return
        output += context
        for (index in 0 until context.accessibleChildrenCount) {
            collect(context.getAccessibleChild(index)?.accessibleContext, output)
        }
    }

    private fun owned(window: Window): Boolean =
        generateSequence(window) { it.owner }.any { it === mainWindow }
}

internal fun <T> onTestUiThread(operation: () -> T): T {
    if (EventQueue.isDispatchThread()) return operation()
    val result = AtomicReference<T>()
    EventQueue.invokeAndWait { result.set(operation()) }
    return result.get()
}
