// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责控件填充、边框、悬停底色与通用面板/标签样式。

package dev.molifox.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 输入框 / 下拉框 / 搜索框的**实色填充**：surfaceVariant 掺入少量主题色。 浅色主题下比白卡片深一档（#ECDBE3 级别），深色主题下比页面底色亮一档（#4F404B
 * 级别）； 悬停与聚焦时再向主题色靠 6%–18%，不改变尺寸与圆角。取「实色、比页面深一档」的填充，
 * 配可见细边，既压得住白卡片又保留樱花粉的柔和。
 */
@Composable
fun controlBackground(
    focused: Boolean = false,
    hovered: Boolean = false,
): Color {
    val colors = MaterialTheme.colorScheme
    val dark = isDarkScheme()
    val base = lerp(colors.surfaceVariant, colors.primary, if (dark) .16f else .14f)
    val lift =
        when {
            focused -> if (dark) .18f else .12f
            hovered -> if (dark) .10f else .06f
            else -> 0f
        }
    return if (lift == 0f) base else lerp(base, colors.primary, lift)
}

/** 控件边框颜色：默认把 outlineVariant 压向 onSurfaceVariant（浅色 #BFB7BC 级别），聚焦用主题色。 */
@Composable
fun controlBorderColor(focused: Boolean = false): Color {
    val colors = MaterialTheme.colorScheme
    if (focused) return colors.primary.copy(alpha = .78f)
    val dark = isDarkScheme()
    return lerp(
        colors.outlineVariant,
        colors.onSurfaceVariant,
        if (dark) .45f else .30f,
    ).copy(alpha = .85f)
}

/** 控件 1dp 边框：与 [controlBorderColor] 配套。 */
@Composable
fun controlBorder(focused: Boolean = false): BorderStroke = BorderStroke(1.dp, controlBorderColor(focused))

/** 列表行悬停底色：主题色 7%（浅）/ 12%（深）。 */
@Composable
fun rowHoverColor(): Color = MaterialTheme.colorScheme.primary.copy(alpha = if (isDarkScheme()) .12f else .07f)

/**
 * 选中行（下拉项、选择器行、已选分类 chip）的实色底色：surfaceVariant 向主题色推进 34%（浅）/ 30%（深），
 * 悬停态再淡一档，保证与未选中行有明确区分。
 */
@Composable
fun selectedRowBackground(): Color {
    val colors = MaterialTheme.colorScheme
    return lerp(colors.surfaceVariant, colors.primary, if (isDarkScheme()) .30f else .34f)
}

/** 白卡片容器：1dp 细边、无阴影、圆角 6、内边距 16。 传入 title 时渲染 14sp SemiBold 标题行，并用 1dp 分隔线与内容隔开。 */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    padding: Int = 16,
    title: String? = null,
    detail: String = "",
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier,
        shape = RoundedCornerShape(Metrics.cardRadius),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, hairline()),
    ) {
        Column {
            if (!title.isNullOrBlank()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 11.dp, bottom = 10.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (detail.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            detail,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                HorizontalDivider(color = hairline())
            }
            Column(Modifier.padding(padding.dp), content = content)
        }
    }
}

@Composable
fun Tag(
    text: String,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(color.copy(alpha = .10f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}
