// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 统一下拉交互与键盘反馈，避免设置表单重复实现弹层。

package dev.molifox.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 紧凑下拉：控件 34dp 高、圆角 4、实色主题填充 + 1dp 细边，菜单宽 210–500、高上限 320。 填充与边框来自 Theme.kt 的 controlBackground /
 * controlBorder，与输入框、搜索框保持一致。
 */
@Composable
fun SimpleDropdown(
    value: String,
    values: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(Metrics.innerRadius)
    val animation: androidx.compose.animation.core.AnimationSpec<Color> =
        if (LocalReducedMotion.current) {
            androidx.compose.animation.core
                .snap<Color>()
        } else {
            tween<Color>(Motion.FAST)
        }
    val fill by
        animateColorAsState(
            controlBackground(hovered = hovered && enabled),
            animation,
            label = "dropdownFill",
        )
    val border by animateColorAsState(controlBorderColor(), animation, label = "dropdownBorder")
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(Metrics.controlHeight)
                .alpha(if (enabled) 1f else .45f)
                .clip(shape)
                .background(fill, shape)
                .border(1.dp, border, shape)
                .hoverable(interaction, enabled = enabled)
                .clickable(enabled = enabled, role = Role.DropdownList) { expanded = true }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                values.find { it.first == value }?.second ?: value.ifBlank { "请选择" },
                Modifier.weight(1f),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                Icons.Outlined.ExpandMore,
                null,
                Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(
            expanded,
            { expanded = false },
            modifier = Modifier.heightIn(max = 320.dp).widthIn(min = 210.dp, max = 500.dp),
        ) {
            values.forEach { (id, label) ->
                val selected = id == value
                DropdownMenuItem(
                    {
                        Text(
                            label,
                            fontSize = 13.sp,
                            color =
                                if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    {
                        onSelect(id)
                        expanded = false
                    },
                    modifier =
                        Modifier.background(
                            if (selected) selectedRowBackground() else Color.Transparent,
                        ),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                )
            }
        }
    }
}
