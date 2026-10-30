// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验收实例详情的「管理」折叠区：默认收起、鼠标与键盘展开、行顺序和缺失的旧按钮。

package dev.molifox.desktop.checks

import java.awt.event.KeyEvent

private const val MAX_TAB_PRESSES = 40

private val MANAGE_ROWS =
    listOf(
        "查看启动参数",
        "打开游戏目录",
        "导出整合包",
        "游戏截图",
        "整理实例目录",
        "删除实例",
    )

internal fun checkManageRail(ui: UiProbe) {
    ui.tab("开始", navigation = true)
    ui.await("开始页") { ui.exists("版本设置") }
    ui.click("版本设置")
    ui.await("实例详情") { ui.exists("管理") }
    // 每次进入页面都从收起开始：提示图标与缺失的动作行一起确认。
    check(ui.exists("展开管理")) { "「管理」首次打开应处于收起状态" }
    check(!ui.exists("查看启动参数")) { "收起时不应显示管理动作" }
    ui.capture("instance-manage-collapsed")

    ui.click("管理")
    ui.await("鼠标展开管理") { ui.exists("折叠管理") && ui.exists("查看启动参数") }
    assertManageRows(ui)
    // 「移动实例」也在同一区域：只记录，不把它当成需求里未列出的错误。
    println("UI_MANAGE_MOVE_ROW ${ui.bounds("移动实例")}")
    check(!ui.exists("保存设置")) { "实例详情不应出现「保存设置」按钮（设置是自动保存）" }
    check(!ui.exists("启动")) { "实例详情不应出现「启动」按钮" }
    ui.capture("instance-manage-expanded")

    ui.click("管理")
    ui.await("重新收起") { ui.exists("展开管理") }
    // 收起时键盘焦点为空：Tab 从顶部导航开始走，第 16 次落到「管理」标题（聚焦名取自折叠图标）。
    focusManageHeader(ui)
    ui.press(KeyEvent.VK_SPACE)
    ui.await("键盘展开管理") { ui.exists("折叠管理") && ui.exists("查看启动参数") }
    ui.capture("instance-manage-keyboard")
}

/** 六行都在，且自上而下与需求顺序一致。 */
private fun assertManageRows(ui: UiProbe) {
    for (row in MANAGE_ROWS) {
        check(ui.exists(row)) { "管理区缺少「$row」" }
    }
    val areas =
        MANAGE_ROWS.map { row ->
            checkNotNull(ui.bounds(row)) { "找不到管理行：$row" }
        }
    areas.zipWithNext().forEachIndexed { index, (above, below) ->
        check(above.y < below.y) {
            "管理行顺序应为 ${MANAGE_ROWS[index]} → ${MANAGE_ROWS[index + 1]}：${above.y} / ${below.y}"
        }
    }
}

/** 用 Tab 把键盘焦点移到「管理」标题；Compose 的折叠区标题支持 Enter / 空格切换。 */
private fun focusManageHeader(ui: UiProbe) {
    repeat(MAX_TAB_PRESSES) {
        ui.press(KeyEvent.VK_TAB)
        if (ui.focusedNames().any { it.contains("管理") }) return
    }
    error("Tab 无法把焦点移到「管理」，当前焦点：${ui.focusedNames()}")
}
