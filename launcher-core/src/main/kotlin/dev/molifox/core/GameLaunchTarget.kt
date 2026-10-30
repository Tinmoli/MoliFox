// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 描述并校验临时启动的服务器或存档目标。

package dev.molifox.core

data class GameLaunchTarget(
    val kind: EntryKind,
    val key: String,
) {
    init {
        require(key.isNotBlank() && key.none { it == '\n' || it == '\r' || it == '\u0000' }) {
            "游戏目标无效"
        }
        when (kind) {
            EntryKind.Server -> require(parseServerAddress(key) != null) { "服务器地址无效" }
            EntryKind.World ->
                require(key !in listOf(".", "..") && '/' !in key && '\\' !in key) { "存档目录无效" }
        }
    }
}
