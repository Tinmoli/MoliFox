// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.desktop.checks

import dev.molifox.core.PackExportFormat
import dev.molifox.core.i18n.I18n
import java.nio.file.Files
import java.util.zip.ZipFile

/** 不等预览就能开始；改格式和远程开关不发起来源查询，实际归档包含已选配置。 */
internal fun checkDirectExport(ui: UiProbe, fixture: UiFixture) {
    ui.click("版本设置")
    ui.click("管理")
    ui.click("导出整合包")
    ui.await("选择内容后可直接导出") { ui.exists("打包内容") && ui.enabled("开始导出") }
    check(!ui.exists("正在读取实例文件…"))
    val requests = remoteExportQueries(fixture)
    ui.click("远程资源")
    checkExportFormats(ui)
    check(remoteExportQueries(fixture) == requests) { "切换格式或开关不应查询来源" }
    val target = fixture.root.resolve("selected.mrpack")
    ui.type(I18n.t("pages.export.target"), target.toString())
    ui.await("无需预览即可确认") { ui.enabled("开始导出") }
    ui.click("开始导出")
    ui.await("已选配置实际写入且任务完成") {
        Files.isRegularFile(target) && !fixture.controller.jobs.busy
    }
    ZipFile(target.toFile()).use { zip ->
        val config = checkNotNull(zip.getEntry("client-overrides/config/preserve.txt"))
        check(zip.getInputStream(config).use { it.readBytes().toString(Charsets.UTF_8) } == "keep me")
    }
    check(remoteExportQueries(fixture) == requests) { "关闭远程的导出不应查询来源" }
    check(fixture.network.unexpected.isEmpty())
    ui.capture("direct-export-complete")
}

private fun checkExportFormats(ui: UiProbe) {
    var current = PackExportFormat.Modrinth
    for (format in PackExportFormat.offeredForExport.drop(1) + PackExportFormat.Modrinth) {
        ui.click(current.title)
        ui.click(format.title)
        val confirm = if (format == PackExportFormat.Backup) "生成备份" else "开始导出"
        ui.await("切换格式后可直接导出") { ui.enabled(confirm) }
        current = format
    }
}

private fun remoteExportQueries(fixture: UiFixture): Int =
    fixture.network.requested.count { it.substringBefore('?').endsWith("/version_files") }
