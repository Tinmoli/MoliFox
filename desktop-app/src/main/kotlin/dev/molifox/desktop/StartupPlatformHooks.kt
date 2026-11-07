// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.desktop

import dev.molifox.core.LauncherController
import dev.molifox.core.i18n.I18n
import dev.molifox.core.platform.ensureLinuxDesktopEntry

/** Linux 应用菜单注册：只处理 `.run` 便携模式；成功与跳过都算就绪，失败写错误日志。 */
internal fun registerLinuxDesktopEntry(controller: LauncherController) {
    runCatching { ensureLinuxDesktopEntry(controller.dataDirectory) }
        .onSuccess { path ->
            path?.let { controller.store.log(I18n.t("desktop.startup.desktopEntryDone", it)) }
        }.onFailure {
            controller.store.log(
                I18n.t("desktop.startup.desktopEntryFailed", it.message ?: ""),
                true,
            )
        }
}

/**
 * macOS 应用迁移保护（Translocation）检测：从未随证签名的 DMG/浏览器下载直接运行时，
 * macOS 会把应用挂到只读随机路径（固定落在 /private/var/folders/ 或 /var/folders/），
 * 配置与下载写进临时目录、退出即丢。路径前缀本身就是 macOS 专属特征，其他平台天然不匹配。
 */
internal fun warnMacTranslocation(controller: LauncherController) {
    val location = controller.dataDirectory.toString()
    val translocated = location.startsWith("/private/var/folders/") || location.startsWith("/var/folders/")
    if (translocated) controller.store.log(I18n.t("desktop.startup.macTranslocation", location), true)
}
