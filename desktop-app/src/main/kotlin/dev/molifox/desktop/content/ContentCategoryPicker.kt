// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 内容浏览的分类多选控件：按钮显示已选数量，弹出复选列表并可一键清除。

package dev.molifox.desktop.content

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.desktop.Metrics
import dev.molifox.desktop.controlBackground
import dev.molifox.desktop.controlBorder
import dev.molifox.desktop.hoverHighlight
import dev.molifox.desktop.rowHoverHighlight
import dev.molifox.desktop.selectedRowBackground

/**
 * 分类多选控件：按钮显示「分类」/「分类 · N」，弹出复选列表并可一键清除。 单选下拉是 [SimpleDropdown]，无法表达集合，所以这里用 DropdownMenu 自己维护选中集合。
 */
@Composable
internal fun ContentCategoryPicker(
    selected: Set<String>,
    options: List<Pair<String, String>>,
    onChange: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val pickerShape = RoundedCornerShape(Metrics.innerRadius)
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(Metrics.controlHeight)
                .clip(pickerShape)
                .background(controlBackground(), pickerShape)
                .border(controlBorder(), pickerShape)
                .hoverHighlight(pickerShape)
                .clickable(role = Role.Button) { expanded = true }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (selected.isEmpty()) "分类" else "分类 · ${selected.size}",
                Modifier.weight(1f),
                fontSize = 13.sp,
                color =
                    if (selected.isEmpty()) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
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
            modifier = Modifier.heightIn(max = 360.dp).widthIn(min = 200.dp, max = 260.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "分类",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onChange(emptySet()) }, enabled = selected.isNotEmpty()) {
                    Text("清除", fontSize = 12.sp)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
            for ((slug, label) in options) {
                val checked = slug in selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 38.dp)
                        .background(if (checked) selectedRowBackground() else Color.Transparent)
                        .rowHoverHighlight(enabled = !checked)
                        .clickable {
                            onChange(if (checked) selected - slug else selected + slug)
                        }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked, null)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        label,
                        fontSize = 13.sp,
                        color =
                            if (checked) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
