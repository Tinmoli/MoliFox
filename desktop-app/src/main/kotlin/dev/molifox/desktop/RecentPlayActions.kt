// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 声明最近游玩条目的操作回调，供行组件与页面复用。

package dev.molifox.desktop

import dev.molifox.core.domain.server.ServerStatusQuery

internal data class RecentPlayActions(
    val play: (RecentPlayItem) -> Unit,
    val primary: (RecentPlayItem) -> Unit,
    val hide: (RecentPlayItem) -> Unit,
    val delete: (RecentPlayItem) -> Unit,
    val statusQuery: ServerStatusQuery,
)
