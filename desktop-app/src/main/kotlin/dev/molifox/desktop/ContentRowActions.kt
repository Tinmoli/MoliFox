// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 提供本地内容列表统一的行尾操作组件。

package dev.molifox.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * 行尾图标动作：高 28dp，与其它行尾动作同间距。调用方统一按「主动作 → 开关 → 删除」排序，
 * 动作之间间隔 4dp；删除一律用图标并放最后。
 */
@Composable
internal fun RowIconAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit,
) {
    SmallIconButton(icon, label, onClick, enabled = enabled, tint = tint, size = 28.dp)
}
