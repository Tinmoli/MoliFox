// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 只遍历和点击测试窗口及其子对话框，失败时保留可访问节点和当前窗口截图。

package dev.molifox.desktop.checks

import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.Window
import java.awt.datatransfer.StringSelection
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.accessibility.AccessibleRole
import javax.imageio.ImageIO

internal class UiProbe(
    private val mainWindow: Window,
    private val screenshots: Path,
) {
    internal val robot = Robot().apply { autoDelay = 60 }
    internal val access = UiWindowAccess(mainWindow)

    fun await(
        description: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TIMEOUT_MILLIS * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return
            check(mainWindow.isShowing) { "测试窗口提前关闭：$description" }
            Thread.sleep(POLL_MILLIS)
        }
        error("等待超时：$description\n${access.diagnostics()}")
    }

    fun exists(label: String): Boolean = onTestUiThread { access.matches(label).isNotEmpty() }

    /** 等待一段可以被读到的动态值（例如滑块旁的「4096 MB」），返回第一次读到的结果。 */
    fun <T : Any> awaitValue(
        description: String,
        read: () -> T?,
    ): T {
        var value: T? = null
        await(description) {
            value = read()
            value != null
        }
        return checkNotNull(value)
    }

    fun enabled(label: String): Boolean =
        onTestUiThread {
            access
                .matches(label)
                .lastOrNull()
                ?.accessibleComponent
                ?.isEnabled == true
        }

    fun tab(
        label: String,
        navigation: Boolean = false,
    ) = click(label, navigation, AccessibleRole.PAGE_TAB)

    /**
     * 在控件区域内的相对位置点击（0.5 为正中）。
     *
     * 滑块的语义没有「click」动作，直接走鼠标；等待位置稳定后再按下，避免布局动画期间点偏。
     */
    fun clickAt(
        label: String,
        xFraction: Float = .5f,
        yFraction: Float = .5f,
    ) {
        access.focus()
        var target: Rectangle? = null
        var stablePolls = 0
        await("可点击区域：$label") {
            val candidate = this.bounds(label)
            stablePolls = if (candidate != null && candidate == target) stablePolls + 1 else 0
            target = candidate
            stablePolls >= STABLE_POLLS
        }
        val area = checkNotNull(target)
        this.clickAtPoint(
            area.x + (area.width * xFraction).toInt().coerceIn(1, area.width - 1),
            area.y + (area.height * yFraction).toInt().coerceIn(1, area.height - 1),
        )
    }

    fun click(
        label: String,
        navigation: Boolean = false,
        role: AccessibleRole? = null,
    ) {
        access.focus()
        var target: Rectangle? = null
        var stablePolls = 0
        await("可点击控件：$label") {
            val candidate =
                onTestUiThread {
                    access
                        .matches(label)
                        .filter { context ->
                            (role == null || context.accessibleRole == role) &&
                                context.accessibleComponent.isEnabled
                        }.mapNotNull(access::bounds)
                        .filter {
                            !navigation || it.y < mainWindow.y + NAVIGATION_HEIGHT
                        }.lastOrNull()
                }
            stablePolls = if (candidate != null && candidate == target) stablePolls + 1 else 0
            target = candidate
            stablePolls >= STABLE_POLLS
        }
        val area = checkNotNull(target)
        // 按钮通过界面的可访问操作触发，避免原生窗口刚获焦时吞掉第一次点击。
        val semanticClick = access.activate(label, area)
        if (!semanticClick) {
            robot.mouseMove(area.x + area.width / 2, area.y + area.height / 2)
            robot.waitForIdle()
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        }
        robot.delay(SETTLE_MILLIS)
        println("UI_CLICK $label $area accessibility=$semanticClick")
    }

    fun type(
        label: String,
        value: String,
    ) {
        click(label)
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val original = clipboard.getContents(null)
        try {
            // 粘贴仍经过输入框事件，避免依赖用户当前的中英文输入法状态。
            clipboard.setContents(StringSelection(value), null)
            shortcut(KeyEvent.VK_A)
            shortcut(KeyEvent.VK_V)
            robot.waitForIdle()
            robot.delay(SETTLE_MILLIS)
        } finally {
            clipboard.setContents(original ?: StringSelection(""), null)
        }
    }

    private fun shortcut(key: Int) {
        robot.keyPress(KeyEvent.VK_CONTROL)
        robot.keyPress(key)
        robot.keyRelease(key)
        robot.keyRelease(KeyEvent.VK_CONTROL)
    }

    fun capture(name: String) {
        access.focus()
        Files.createDirectories(screenshots)
        val area = access.captureBounds()
        ImageIO.write(
            robot.createScreenCapture(area),
            "png",
            screenshots.resolve("MoliFox-$name.png").toFile(),
        )
    }

    private companion object {
        const val TIMEOUT_MILLIS = 15_000L
        const val POLL_MILLIS = 70L
        const val NAVIGATION_HEIGHT = 80
        const val SETTLE_MILLIS = 180
        const val STABLE_POLLS = 3
    }
}
