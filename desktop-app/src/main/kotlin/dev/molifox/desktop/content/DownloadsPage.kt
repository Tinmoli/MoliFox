// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 日志页入口：内容保留在 LogsPage.kt，主界面与设置页的调用点不变。

package dev.molifox.desktop.content

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import dev.molifox.core.LauncherState
import dev.molifox.desktop.LogsPage
import dev.molifox.desktop.UiActions

/**
 * 日志页实现已移到 LogsPage.kt（操作行、级别筛选、搜索与列表）；这里保留原入口，
 * 主界面与设置页的调用点不变。
 */
@Composable
fun ColumnScope.DownloadsPage(
    state: LauncherState,
    actions: UiActions,
) {
    LogsPage(state, actions)
}
