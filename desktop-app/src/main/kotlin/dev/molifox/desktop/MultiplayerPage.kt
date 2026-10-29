// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责「联机」占位页：只有标题和空态，不提供任何看似可用但实际无效的入口。

package dev.molifox.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 联机页：功能尚未实现。 服务器列表、局域网与联机大厅都还没有接入，因此这里只显示空态，不做假按钮、也不放假服务器列表。 */
@Composable
fun ColumnScope.MultiplayerPage() {
    Text("联机", Modifier.padding(bottom = 10.dp), style = MaterialTheme.typography.titleLarge)
    Panel(Modifier.fillMaxWidth().weight(1f), padding = 0) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                "联机功能还没做",
                "计划提供服务器列表、局域网与联机大厅，目前都还没有接入。",
                Icons.Outlined.Groups,
            )
        }
    }
}
