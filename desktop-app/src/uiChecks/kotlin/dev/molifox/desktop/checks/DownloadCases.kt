// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 用固定清单验收下载页的版本展示和七类资源入口，不下载真实游戏。

package dev.molifox.desktop.checks

internal fun checkDownloads(
    ui: UiProbe,
    fixture: UiFixture,
) {
    ui.tab("下载", navigation = true)
    ui.await("固定版本清单") { ui.exists("推荐 · 最新正式版") && ui.exists("1.20.1") }
    check(fixture.network.requested.any { it.contains("version_manifest_v2.json") })
    ui.capture("download-recommended")
    for (category in listOf("模组", "整合包", "资源包", "光影", "数据包", "原版")) {
        ui.tab(category)
        ui.capture("download-" + categoryName(category))
    }
    check(ui.exists("世界")) { "下载页缺少世界分类" }
    ui.tab("联机", navigation = true)
    ui.await("首次联机说明") { ui.exists("暂不使用") }
    ui.click("暂不使用")
    ui.await("联机大厅") { ui.exists("阅读联机说明") }
    ui.capture("multiplayer")
    ui.tab("开始", navigation = true)
    check(fixture.network.unexpected.isEmpty()) {
        "假服务缺少响应，未进行真实联网：${fixture.network.unexpected}"
    }
}

private fun categoryName(category: String): String =
    when (category) {
        "模组" -> "mods"
        "整合包" -> "packs"
        "资源包" -> "resources"
        "光影" -> "shaders"
        "数据包" -> "datapacks"
        else -> "vanilla"
    }
