// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 实例行的右键 / 「更多操作」上下文菜单。

package dev.molifox.desktop.instances

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.GameInstance
import dev.molifox.desktop.hairline

internal enum class InstanceMenuAnchor {
    /** 右键：菜单出现在指针位置（行内坐标）。 */
    Pointer,

    /** ⋯ 按钮：菜单贴着按钮打开，右边缘与按钮对齐。 */
    Button,
}

// ⋯ 菜单：锚点就是按钮自身的 Box，+4dp 让菜单从按钮下沿开始；横向越界交给 M3 弹窗夹回窗口内
// （实测 1000×640 下自动右对齐按钮右缘 x=820–996，1180×720 下 x=1000–1176）。
internal val ButtonMenuOffset = DpOffset(0.dp, 4.dp)

// 紧凑菜单项：整份六项菜单约 241dp，1000×640 下贴着按钮完整展开（不会因上下都放不下被居中到按钮中间）。
private val MenuItemModifier = Modifier.height(40.dp)
private val MenuItemPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)

/**
 * 实例行的上下文菜单：启动 / 重命名 / 复制实例 / 打开游戏目录 / 导出整合包 / 删除。
 * 使用 Material3 DropdownMenu（可聚焦弹窗）：Esc 与点击外部都会关闭，位置由弹窗自己限制在窗口内，
 * 因此在 1000×640 下不会溢出；菜单高度上限 320dp，六项内容都不需要滚动。
 */
@Composable
internal fun InstanceContextMenu(
    expanded: Boolean,
    offset: DpOffset,
    instance: GameInstance,
    busy: Boolean,
    onDismiss: () -> Unit,
    onLaunch: () -> Unit,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onOpenFolder: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.heightIn(max = 320.dp).widthIn(min = 176.dp),
        offset = offset,
    ) {
        DropdownMenuItem(
            { Text("启动", fontSize = 13.sp) },
            {
                onDismiss()
                onLaunch()
            },
            modifier = MenuItemModifier,
            contentPadding = MenuItemPadding,
            enabled = !instance.broken && !busy,
        )
        DropdownMenuItem(
            { Text("重命名…", fontSize = 13.sp) },
            {
                onDismiss()
                onRename()
            },
            modifier = MenuItemModifier,
            contentPadding = MenuItemPadding,
            enabled = !busy,
        )
        DropdownMenuItem(
            { Text("复制实例…", fontSize = 13.sp) },
            {
                onDismiss()
                onCopy()
            },
            modifier = MenuItemModifier,
            contentPadding = MenuItemPadding,
            enabled = !busy,
        )
        DropdownMenuItem(
            { Text("打开游戏目录", fontSize = 13.sp) },
            {
                onDismiss()
                onOpenFolder()
            },
            modifier = MenuItemModifier,
            contentPadding = MenuItemPadding,
        )
        HorizontalDivider(color = hairline())
        DropdownMenuItem(
            { Text("导出整合包…", fontSize = 13.sp) },
            {
                onDismiss()
                onExport()
            },
            modifier = MenuItemModifier,
            contentPadding = MenuItemPadding,
            enabled = !busy,
        )
        DropdownMenuItem(
            { Text("删除…", fontSize = 13.sp, color = MaterialTheme.colorScheme.error) },
            {
                onDismiss()
                onDelete()
            },
            modifier = MenuItemModifier,
            contentPadding = MenuItemPadding,
        )
    }
}
