// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 提供本地内容列表统一的行尾操作组件。

package dev.molifox.desktop

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.onClick
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 行尾动作按钮：模组 / 资源包 / 光影 / 存档共用。
 *
 * 文字动作高 28、12sp、左右 8dp；图标动作同高（28dp）同间距。调用方统一按「主动作 → 开关 → 删除」排序， 动作之间间隔 4dp；删除一律用图标并放最后。
 */
@Composable
internal fun RowTextAction(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(
        onClick,
        enabled = enabled,
        modifier = Modifier.height(28.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        Text(text, fontSize = 12.sp)
    }
}

/** 行尾图标动作：与 [RowTextAction] 同高同间距，删除类动作放最后。 */
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
