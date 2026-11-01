// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core

/** 只读查询允许目标已消失；沿用原查找的别名规则，严格操作仍调用 instance。 */
internal fun LauncherStore.instanceOrNull(id: String): GameInstance? =
    try {
        instance(id)
    } catch (_: IllegalStateException) {
        // instance 的解析不做 IO，这个异常只表示记录已不存在。
        null
    }
