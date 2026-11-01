// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 描述并校验临时启动的服务器或存档目标。

package dev.molifox.core

import dev.molifox.core.i18n.I18n

data class GameLaunchTarget(
    val kind: EntryKind,
    val key: String,
) {
    init {
        require(key.isNotBlank() && key.none { it == '\n' || it == '\r' || it == '\u0000' }) {
            I18n.t("diagA1.gameLaunchTarget.invalidTarget")
        }
        when (kind) {
            EntryKind.Server ->
                require(parseServerAddress(key) != null) { I18n.t("diagA1.gameLaunchTarget.invalidServer") }
            EntryKind.World ->
                require(key !in listOf(".", "..") && '/' !in key && '\\' !in key) {
                    I18n.t("diagA1.gameLaunchTarget.invalidSave")
                }
        }
    }
}
