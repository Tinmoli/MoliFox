// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责实例详情各内容页签共用的工具行。

package dev.molifox.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 内容页签工具行的尺寸令牌：所有页签从同一份数值取尺寸，不各写一套内边距。
 *
 * 32dp 高的按钮 + 8dp 间距 + 12sp 文字 + 16dp 图标；搜索框与按钮同高，整行共用一条垂直中线。
 */
internal object ContentToolbarMetrics {
    val height = 32.dp
    val gap = 8.dp
    val iconSize = 16.dp
    val iconTextGap = 6.dp
    val labelSize = 12.sp
    val horizontalPadding = 10.dp
    val minButtonWidth = 64.dp
}

/** 工具行按钮的视觉层级：普通描边（默认）、主操作（实心）、次要（无边框）、危险操作。 */
internal enum class ToolbarActionStyle {
    Outline,
    Filled,
    Quiet,
    Danger,
}

/**
 * 工具行里的一个动作。
 *
 * [icon] 为 16dp 图标，可省略；省略时按钮宽度由文字决定，高度与间距仍完全相同。
 */
internal data class ToolbarAction(
    val label: String,
    val icon: ImageVector? = null,
    val style: ToolbarActionStyle = ToolbarActionStyle.Outline,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * 实例详情「模组 / 资源包 / 光影 / 存档 / 数据包 / 原理图」共用的工具行。
 *
 * 顺序由本组件固定，调用方只能填槽位，无法把「刷新」插到别的动作中间：
 * `[返回] · 搜索/筛选（占满剩余宽度） · 刷新 · 导入… · 下载 · 打开文件夹 · 分隔线 · 清空 / 删除全部`
 *
 * - 所有按钮 32dp 高、8dp 间距，图标 16dp、文字 12sp，整行共用一条垂直中线；
 * - 搜索框或筛选控件放在 [flexible]，用 `weight(1f)` 吸收剩余宽度，右侧动作组因此始终贴右，
 *   切换页签时按钮不会左右跳动；
 * - [refresh] 永远排在动作组第一位，其后才是导入、下载与打开文件夹；
 * - 破坏性或低频动作（清空、删除全部）放在 [destructive]，与常用动作之间有一条分隔线。
 */
@Composable
internal fun ContentToolbar(
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    flexible: (@Composable () -> Unit)? = null,
    refresh: ToolbarAction? = null,
    importAction: ToolbarAction? = null,
    downloads: List<ToolbarAction> = emptyList(),
    openFolder: ToolbarAction? = null,
    destructive: List<ToolbarAction> = emptyList(),
) {
    Row(
        modifier.fillMaxWidth().height(ContentToolbarMetrics.height),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ContentToolbarMetrics.gap),
    ) {
        leading?.invoke()
        // 搜索框 / 世界下拉吸收剩余宽度；右侧动作组宽度固定，因此始终贴右、切换页签不跳动。
        // 内容区在受支持的窗口尺寸内（≥ 760dp）比动作组宽得多，搜索框实测不会窄于 300dp。
        if (flexible !=
            null
        ) {
            Box(Modifier.weight(1f)) { flexible() }
        } else {
            Spacer(Modifier.weight(1f))
        }
        refresh?.let { ToolbarButton(it) }
        importAction?.let { ToolbarButton(it) }
        downloads.forEach { ToolbarButton(it) }
        openFolder?.let { ToolbarButton(it) }
        if (destructive.isNotEmpty()) {
            ToolbarSeparator()
            destructive.forEach { ToolbarButton(it) }
        }
    }
}

/** 工具行搜索框：与按钮同高（32dp）、同一条基线，宽度由外层 [ContentToolbar] 的 `weight(1f)` 决定。 */
@Composable
internal fun ToolbarSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    CompactSearchField(
        value,
        onValueChange,
        Modifier.fillMaxWidth().height(ContentToolbarMetrics.height),
        placeholder = placeholder,
    )
}

/** 工具行最左侧的图标动作（如「返回上一级目录」）：32dp 方形触区、16dp 图标。 */
@Composable
internal fun ToolbarIconAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Metrics.innerRadius)
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .size(ContentToolbarMetrics.height)
            .clip(shape)
            .hoverHighlight(shape, enabled = enabled)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = label,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            label,
            Modifier.size(ContentToolbarMetrics.iconSize),
            if (enabled) tint else tint.copy(alpha = .38f),
        )
    }
}

/** 单个动作按钮：32dp 高、固定 10dp 左右内边距，图标与文字同一条中线。 */
@Composable
private fun ToolbarButton(action: ToolbarAction) {
    val shape = RoundedCornerShape(Metrics.innerRadius)
    val colors = MaterialTheme.colorScheme
    val enabled = action.enabled
    val contentColor =
        when {
            !enabled -> colors.onSurfaceVariant.copy(alpha = .38f)
            action.style == ToolbarActionStyle.Filled -> colors.onPrimary
            action.style == ToolbarActionStyle.Danger -> colors.error
            action.style == ToolbarActionStyle.Quiet -> colors.onSurfaceVariant
            else -> colors.onSurface
        }
    val background =
        if (action.style == ToolbarActionStyle.Filled) colors.primary else Color.Transparent
    val border =
        if (action.style == ToolbarActionStyle.Outline) {
            BorderStroke(1.dp, controlBorderColor())
        } else {
            null
        }
    Row(
        Modifier
            .height(ContentToolbarMetrics.height)
            .widthIn(min = ContentToolbarMetrics.minButtonWidth)
            .clip(shape)
            .then(if (border == null) Modifier else Modifier.border(border, shape))
            .background(background, shape)
            .hoverHighlight(shape, enabled = enabled)
            .clickable(enabled = enabled, role = Role.Button, onClick = action.onClick)
            .padding(horizontal = ContentToolbarMetrics.horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ContentToolbarMetrics.iconTextGap),
    ) {
        action.icon?.let {
            Icon(it, null, Modifier.size(ContentToolbarMetrics.iconSize), contentColor)
        }
        Text(
            action.label,
            color = contentColor,
            fontSize = ContentToolbarMetrics.labelSize,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** 常用动作与破坏性动作之间的细分隔线：1dp 宽、16dp 高，不参与文字对齐。 */
@Composable
private fun ToolbarSeparator() {
    Box(
        Modifier
            .width(1.dp)
            .height(16.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f)),
    )
}
