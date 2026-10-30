// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 键盘、滚轮和坐标点击的扩展：让 UiProbe 保持薄入口，场景用例按需组合。

package dev.molifox.desktop.checks

import java.awt.Rectangle
import java.awt.event.InputEvent
import javax.accessibility.AccessibleState

private const val KEY_SETTLE_MILLIS = 90
private const val SCROLL_STEP_MILLIS = 60
private const val POINTER_SETTLE_MILLIS = 180

/** 当前可访问节点的全部名称，用于按正则找动态文案（滑块数值、下拉当前值等）。 */
internal fun UiProbe.names(): List<String> = onTestUiThread { access.names() }

internal fun UiProbe.namesMatching(pattern: Regex): List<String> =
    names().filter { pattern.containsMatchIn(it) }

/** 最后一个同名节点的屏幕区域；行顺序、滑块几何都从这里取。 */
internal fun UiProbe.bounds(label: String): Rectangle? =
    onTestUiThread { access.matches(label).lastOrNull()?.let(access::bounds) }

/** 按屏幕坐标点击；用于没有语义标签、只能从相邻节点推算位置的控件（例如实例整行）。 */
internal fun UiProbe.clickAtPoint(
    x: Int,
    y: Int,
) {
    access.focus()
    robot.mouseMove(x, y)
    robot.waitForIdle()
    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
    robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
    robot.delay(POINTER_SETTLE_MILLIS)
    println("UI_CLICK_POINT ($x,$y)")
}

/** 鼠标滚轮滚动当前页面：正数向下、负数向上；用于把折叠区下方的控件滚进视口。 */
internal fun UiProbe.scroll(steps: Int) {
    access.focus()
    val area = onTestUiThread { access.captureBounds() }
    robot.mouseMove(area.x + area.width / 2, area.y + area.height / 2)
    repeat(kotlin.math.abs(steps)) {
        robot.mouseWheel(if (steps > 0) 1 else -1)
        robot.delay(SCROLL_STEP_MILLIS)
    }
    robot.delay(POINTER_SETTLE_MILLIS)
}

/** 当前键盘焦点所在的节点名称；用于确认 Tab 是否落到了目标行再发送按键。 */
internal fun UiProbe.focusedNames(): List<String> = onTestUiThread { access.focusedNames() }

internal fun UiProbe.press(keyCode: Int) {
    access.focus()
    robot.keyPress(keyCode)
    robot.keyRelease(keyCode)
    robot.delay(KEY_SETTLE_MILLIS)
}

/** Compose 通过 FOCUSED 状态暴露键盘焦点；用于驱动 Tab 遍历。 */
internal fun UiWindowAccess.focusedNames(): List<String> =
    contexts()
        .filter { context ->
            runCatching { context.accessibleStateSet }
                .getOrNull()
                ?.contains(AccessibleState.FOCUSED) == true
        }.mapNotNull { it.accessibleName }
