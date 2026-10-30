// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 将实例目录类型映射为界面文字，不执行文件检查。

package dev.molifox.desktop

import dev.molifox.core.domain.instance.GameFolder

internal val GameFolder.title: String
    get() =
        when (this) {
            GameFolder.Root -> "实例文件夹"
            GameFolder.Mods -> "模组文件夹"
            GameFolder.ResourcePacks -> "资源包文件夹"
            GameFolder.ShaderPacks -> "光影包文件夹"
            GameFolder.Saves -> "存档文件夹"
            GameFolder.Schematics -> "原理图文件夹"
            GameFolder.Config -> "配置文件夹"
            GameFolder.Logs -> "日志文件夹"
            GameFolder.CrashReports -> "崩溃报告文件夹"
            GameFolder.Screenshots -> "截图文件夹"
        }
