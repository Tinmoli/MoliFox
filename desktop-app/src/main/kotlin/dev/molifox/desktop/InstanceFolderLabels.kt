// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 将实例目录类型映射为界面文字，不执行文件检查。

package dev.molifox.desktop

import dev.molifox.core.domain.instance.GameFolder

internal val GameFolder.title: String
    get() =
        when (this) {
            GameFolder.Root -> tr("pages.folder.title.root")
            GameFolder.Mods -> tr("pages.folder.title.mods")
            GameFolder.ResourcePacks -> tr("pages.folder.title.resourcePacks")
            GameFolder.ShaderPacks -> tr("pages.folder.title.shaderPacks")
            GameFolder.Saves -> tr("pages.folder.title.saves")
            GameFolder.Schematics -> tr("pages.folder.title.schematics")
            GameFolder.Config -> tr("pages.folder.title.config")
            GameFolder.Logs -> tr("pages.folder.title.logs")
            GameFolder.CrashReports -> tr("pages.folder.title.crashReports")
            GameFolder.Screenshots -> tr("pages.folder.title.screenshots")
        }
