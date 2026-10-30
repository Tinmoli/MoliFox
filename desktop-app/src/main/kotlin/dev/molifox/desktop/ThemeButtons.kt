// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责主/次按钮、弱化文本与空态等通用按钮组件。

package dev.molifox.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 主按钮：高 34、圆角 4、13sp Medium、内边距 14/8，按下 0.98 缩放。 */
@Composable
fun PrimaryButton(
    text: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick,
        Modifier.pressScale().height(Metrics.controlHeight),
        enabled = enabled,
        shape = RoundedCornerShape(Metrics.innerRadius),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun MutedText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        modifier,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}

/** 次按钮：与主按钮同高同圆角，透明底 + 1dp `outlineVariant` 描边。 */
@Composable
fun SecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(Metrics.innerRadius),
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    border: BorderStroke? = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick,
        modifier.height(Metrics.controlHeight),
        enabled = enabled,
        shape = shape,
        colors = colors,
        border = border,
        contentPadding = contentPadding,
    ) {
        ProvideTextStyle(
            value = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
        ) {
            content()
        }
    }
}

/** 空态：40dp 图标 + 14sp 标题 + 12sp 说明，垂直留白 28dp。 */
@Composable
fun EmptyState(
    title: String,
    detail: String,
    icon: ImageVector,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            icon,
            null,
            Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = .7f),
        )
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        action?.invoke()
    }
}
