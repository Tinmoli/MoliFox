// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 通过当前账户入口和设置导航验收真实输入、即时保存及主题切换。

package dev.molifox.desktop.checks

import com.google.gson.JsonParser
import java.nio.file.Files

internal fun checkAccount(
    ui: UiProbe,
    fixture: UiFixture,
) {
    ui.click("打开账户列表")
    ui.await("账户列表") { ui.exists("离线账户") }
    ui.click("离线账户")
    ui.await("离线账户表单") { ui.exists("玩家名称") }
    check(!ui.enabled("添加账户"))
    ui.type("玩家名称", "ab")
    check(!ui.enabled("添加账户"))
    ui.type("玩家名称", "UiPlayer")
    ui.await("合法名称可提交") { ui.enabled("添加账户") }
    ui.click("添加账户")
    ui.await("账户保存") {
        fixture.controller.store.data.accounts
            .any { it.name == "UiPlayer" }
    }
    ui.tab("开始", navigation = true)
    ui.await("首页使用新账户") { ui.exists("UiPlayer") }
    val config =
        JsonParser.parseString(
            Files.readString(fixture.controller.store.directories.configuration),
        )
    check(
        config.asJsonObject.getAsJsonArray("accounts").any {
            it.asJsonObject.get("name").asString == "UiPlayer"
        },
    )
    ui.capture("home-account")
}

internal fun checkSettings(
    ui: UiProbe,
    fixture: UiFixture,
) {
    ui.tab("设置", navigation = true)
    ui.await("全局内存设置") { ui.exists("自动分配内存开关") }
    val before = fixture.controller.store.data.settings.autoMemory
    ui.click("自动分配内存开关")
    ui.await("内存策略保存") { fixture.controller.store.data.settings.autoMemory != before }
    ui.click("自动分配内存开关")
    ui.await("内存策略还原") { fixture.controller.store.data.settings.autoMemory == before }
    ui.tab("外观")
    ui.await("外观主题") { ui.exists("深色") }
    ui.click("深色")
    ui.await("深色主题生效") { fixture.controller.store.data.settings.themeMode == "dark" }
    ui.capture("appearance-dark")
    ui.click("浅色")
    ui.await("浅色主题生效") { fixture.controller.store.data.settings.themeMode == "light" }
    for (section in listOf("Java 管理", "通用", "下载", "日志", "关于 MoliFox", "全局游戏设置")) {
        ui.tab(section)
        ui.capture("settings-" + sectionName(section))
    }
    ui.tab("开始", navigation = true)
}

private fun sectionName(section: String): String =
    when (section) {
        "Java 管理" -> "java"
        "通用" -> "general"
        "下载" -> "download"
        "日志" -> "logs"
        "关于 MoliFox" -> "about"
        else -> "game"
    }
