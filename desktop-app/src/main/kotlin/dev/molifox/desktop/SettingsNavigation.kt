// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 共享设置导航和焦点反馈，避免子分区各自实现键盘选中样式。

package dev.molifox.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.onClick
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 左侧设置条目：与下载页分类条目 ([DownloadKindItem] 同规格) 完全同一套选中态—— 项高 32、圆角 6、图标 17、内边距 10、选中
 * `selectedRowBackground()` 实底 + `onPrimaryContainer` 文字 + SemiBold，悬停用 `rowHoverHighlight`、
 * 按下 `pressScale`。
 * 没有矩形描边，也没有向外扩张的焦点环：键盘焦点画在条目内侧 2dp 的 1dp 细描边里，因此不会越过磨砂面板的圆角。
 */
@Composable
internal fun SettingsNavItem(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Metrics.cardRadius)
    val contentColor by
        animateColorAsState(
            if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
            tween(150),
            label = "settings-nav-color",
        )
    val background by
        animateColorAsState(
            if (selected) selectedRowBackground() else Color.Transparent,
            tween(150),
            label = "settings-nav-bg",
        )
    Row(
        Modifier
            .fillMaxWidth()
            .height(Metrics.navItemHeight)
            .clip(shape)
            .background(background)
            .rowHoverHighlight(shape, enabled = !selected, interactionSource = interaction)
            .insetFocusRing(
                focused,
                Metrics.cardRadius,
                if (selected) colors.onPrimaryContainer else colors.primary,
            ).pressScale()
            .selectable(
                selected,
                interaction,
                LocalIndication.current,
                role = Role.Tab,
                onClick = onClick,
            ).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(17.dp), contentColor)
        Spacer(Modifier.width(9.dp))
        Text(
            title,
            Modifier.weight(1f),
            color = contentColor,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 外观子页条目：缩进在「外观」下面，高 28、圆角 6、文字 12；选中与父条目同一套实底与文字色。 */
@Composable
internal fun SettingSubEntry(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Metrics.cardRadius)
    Row(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(shape)
            .background(if (selected) selectedRowBackground() else Color.Transparent)
            .rowHoverHighlight(shape, enabled = !selected, interactionSource = interaction)
            .insetFocusRing(
                focused,
                Metrics.cardRadius,
                if (selected) colors.onPrimaryContainer else colors.primary,
            ).selectable(
                selected,
                interaction,
                LocalIndication.current,
                role = Role.Tab,
                onClick = onClick,
            ).padding(start = 26.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 键盘焦点提示：在条目内侧 2dp 处描 1dp 主题色圆角边（不向外扩张、也不贴边），
 * 因此始终被条目的圆角包住，不会像 [focusRing] 那样探出父面板的圆角；无障碍焦点仍然可见。
 */
private fun Modifier.insetFocusRing(
    focused: Boolean,
    radius: Dp,
    color: Color,
): Modifier =
    if (!focused) {
        this
    } else {
        drawWithContent {
            drawContent()
            val stroke = 1.dp.toPx()
            val inset = 2.dp.toPx()
            drawRoundRect(
                color = color.copy(alpha = .6f),
                topLeft = Offset(inset, inset),
                size =
                    Size(
                        (size.width - inset * 2).coerceAtLeast(0f),
                        (size.height - inset * 2).coerceAtLeast(0f),
                    ),
                cornerRadius = CornerRadius((radius.toPx() - inset).coerceAtLeast(0f)),
                style = Stroke(width = stroke),
            )
        }
    }
