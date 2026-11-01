// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验收实例真实目录、各页签、导出预览以及移动确认前后的文件归属。

package dev.molifox.desktop.checks

import java.nio.file.Files

private const val DELETION_CALLBACK_SETTLE_MILLIS = 1000L

internal fun checkInstance(
    ui: UiProbe,
    fixture: UiFixture,
) {
    ui.click("版本设置")
    ui.await("实例详情") { ui.exists("管理") }
    ui.click("游戏文件夹")
    ui.await("已有目录可见") { ui.exists("日志文件夹") }
    check(!ui.exists("崩溃报告文件夹"))
    ui.click("日志文件夹")
    ui.await(
        "打开正确目录",
    ) { fixture.platform.openedFolders.any { it == fixture.source.resolve("logs") } }
    Files.createDirectories(fixture.source.resolve("crash-reports"))
    ui.click("游戏文件夹")
    ui.click("游戏文件夹")
    ui.await("重新查询新增目录") { ui.exists("崩溃报告文件夹") }
    ui.click("游戏文件夹")
    for (tab in listOf("模组", "资源包", "光影", "存档", "原理图", "设置")) {
        ui.tab(tab)
        ui.capture("instance-" + instanceTabName(tab))
    }
    ui.click("管理")
    ui.await("移动入口可用") { ui.exists("移动实例") && ui.enabled("移动实例") }
    ui.click("导出整合包")
    ui.await("导出内容预览") { ui.exists("打包内容") }
    ui.capture("export-preview")
    ui.click("取消")
    ui.await("导出页关闭") { !ui.exists("打包内容") }
}

internal fun checkMove(
    ui: UiProbe,
    fixture: UiFixture,
) {
    fixture.picker.chooseNext(fixture.destination)
    // 导出弹窗关闭后页面会重建折叠区，移动前明确展开，原移动断言保持不变。
    if (ui.exists("展开管理")) ui.click("管理")
    ui.click("移动实例")
    ui.await("移动预览完成") { ui.exists("开始移动") && ui.enabled("开始移动") }
    ui.capture("instance-move")
    check(Files.isRegularFile(fixture.source.resolve("UI-Main.json")))
    check(!Files.exists(fixture.destination.resolve("versions/UI-Main/UI-Main.json")))
    ui.click("取消")
    ui.await("取消预览关闭") { !ui.exists("移动到其他游戏目录") }
    check(Files.isRegularFile(fixture.source.resolve("config/preserve.txt")))
    fixture.picker.chooseNext(fixture.destination)
    ui.click("移动实例")
    ui.await("可确认移动") { ui.exists("开始移动") && ui.enabled("开始移动") }
    ui.click("开始移动")
    val target = fixture.destination.resolve("versions/UI-Main")
    ui.await("移动任务完成") {
        Files.isRegularFile(target.resolve("UI-Main.json")) &&
            !Files.exists(fixture.source)
    }
    check(Files.readString(target.resolve("config/preserve.txt")) == "keep me")
    ui.tab("开始", navigation = true)
    ui.await("移动后仍可选中实例") {
        val store = fixture.controller.store
        store.data.instances.any { instance ->
            instance.id == store.data.selectedInstanceId &&
                store.instancePath(instance.id) == target
        }
    }
}

private fun instanceTabName(tab: String): String =
    when (tab) {
        "模组" -> "mods"
        "资源包" -> "resources"
        "光影" -> "shaders"
        "存档" -> "saves"
        "原理图" -> "schematics"
        else -> "settings"
    }

/** 删除确认、记录消失和后续导航必须在同一个测试窗口内完成。 */
internal fun checkDeletion(ui: UiProbe, fixture: UiFixture) {
    val instance = fixture.selectedInstance()
    val directory = fixture.instanceDirectory(instance)
    ui.click("删除实例")
    ui.await("删除确认可用") { ui.exists("删除") && ui.enabled("删除") }
    ui.click("删除")
    ui.await("实例记录和目录已移除") {
        fixture.controller.store.data.instances.none { it.id == instance.id } &&
            !Files.exists(directory)
    }
    // 留出迟到的目录占用查询和退出补写执行时间，再通过导航确认界面仍可交互。
    Thread.sleep(DELETION_CALLBACK_SETTLE_MILLIS)
    check(!ui.exists("找不到游戏实例"))
    ui.tab("设置", navigation = true)
    ui.await("删除后仍可使用设置") { ui.exists("外观") }
    ui.tab("开始", navigation = true)
    ui.await("删除后仍可返回开始页") { ui.exists("版本设置") }
    check(fixture.controller.store.data.instances.none { it.id == instance.id })
    ui.capture("instance-deleted")
}
