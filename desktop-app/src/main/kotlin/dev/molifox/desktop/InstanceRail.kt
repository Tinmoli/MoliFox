// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 实例导航、目录分组与管理动作的展示组件。

package dev.molifox.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.onClick
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.desktop.MoliButton as Button

/** 页头 30dp 图标按钮：浅圆角悬停底，保持标题区紧凑。 */
@Composable
internal fun HeaderIconButton(
    icon: ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Metrics.innerRadius)
    Box(
        Modifier
            .size(30.dp)
            .clip(shape)
            .hoverHighlight(shape, alpha = .06f)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(17.dp), tint)
    }
}

/** 二级栏条目（页签与「管理」动作共用）：高 32、圆角 6、左右 10dp，图标 17 与 13sp 文字同一行。 */
@Composable
internal fun InstanceRailItem(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Metrics.cardRadius)
    val contentColor =
        if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    Row(
        Modifier
            .fillMaxWidth()
            .height(Metrics.navItemHeight)
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            ).hoverHighlight(shape, enabled = !selected, alpha = .05f)
            .pressScale()
            .selectable(selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 10.dp),
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
        )
    }
}

/**
 * 「管理」分区标题：整行可点击/可聚焦，Enter 与空格切换展开，右侧箭头随状态旋转。
 *
 * 高度与动作行一致，展开后列表仍按 4dp 间距对齐；默认收起由调用方的状态决定。
 */
@Composable
internal fun InstanceRailSectionHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val shape = RoundedCornerShape(Metrics.cardRadius)
    val chevron by
        animateFloatAsState(
            targetValue = if (expanded) 180f else 0f,
            animationSpec = tween(if (LocalReducedMotion.current) 0 else Motion.FAST),
            label = "railSectionChevron",
        )
    Row(
        Modifier
            .fillMaxWidth()
            .height(Metrics.navItemHeight)
            .clip(shape)
            .hoverHighlight(shape, alpha = .05f)
            .pressScale()
            .clickable(role = Role.Button, onClick = onToggle)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else {
                    when (event.key) {
                        Key.Enter,
                        Key.NumPadEnter,
                        Key.Spacebar,
                        -> {
                            onToggle()
                            true
                        }
                        else -> false
                    }
                }
            }.focusable()
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            Modifier.weight(1f),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Icon(
            Icons.Outlined.ExpandMore,
            if (expanded) {
                tr("pages.instance.rail.collapse", title)
            } else {
                tr("pages.instance.rail.expand", title)
            },
            Modifier.size(14.dp).rotate(chevron),
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 「管理」区的动作行：与页签同宽、同 32dp 高、同排版，只把选中态换成可禁用。
 *
 * [hint] 在不可用时说明原因，直接显示在行内，而不是静默禁用。
 */
@Composable
internal fun InstanceRailAction(
    title: String,
    icon: ImageVector,
    enabled: Boolean = true,
    danger: Boolean = false,
    hint: String = "",
    // 行尾附加内容（例如检查更新的进度圈）；不改变 32dp 行高与图标文字排版。
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Metrics.cardRadius)
    val contentColor =
        when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .38f)
            danger -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    val showHint = hint.isNotBlank() && !enabled
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Metrics.navItemHeight)
            .clip(shape)
            .hoverHighlight(shape, enabled = enabled, alpha = .05f)
            .pressScale()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(17.dp), contentColor)
        Spacer(Modifier.width(9.dp))
        if (showHint) {
            Column(Modifier.weight(1f)) {
                Text(title, color = contentColor, fontSize = 13.sp, maxLines = 1)
                Text(
                    hint,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f),
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Text(
                title,
                Modifier.weight(1f),
                color = contentColor,
                fontSize = 13.sp,
                maxLines = 1,
            )
        }
        trailing?.invoke()
    }
}
