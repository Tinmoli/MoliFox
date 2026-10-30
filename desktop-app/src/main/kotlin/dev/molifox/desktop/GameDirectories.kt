// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责游戏目录的选择、登记和管理界面。

package dev.molifox.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.GameDirectory
import dev.molifox.core.LauncherState

/** 游戏目录控件：34dp 工具行（下拉 + 选择 / 刷新 / 解绑）+ 44dp 目录行。 开始页之外的实例页与设置页共用，路径一律 12sp 单行省略。 */
@Composable
fun GameDirectoryControls(
    state: LauncherState,
    actions: UiActions,
) {
    val selected = state.data.gameDirectories.find { it.id == state.data.activeGameDirectoryId }
    var removing by remember { mutableStateOf<GameDirectory?>(null) }
    val busy = actions.controller.busy
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Outlined.FolderOpen, null, Modifier.size(16.dp), MutedGreen)
            CompactSelect(
                selected?.id.orEmpty(),
                listOf("" to "MoliFox 默认目录") + state.data.gameDirectories.map { it.id to it.name },
                { id ->
                    actions.run { actions.controller.selectGameDirectory(id.ifBlank { null }) }
                },
                Modifier.weight(1f),
                enabled = !busy,
            )
            SmallIconButton(
                Icons.Outlined.CreateNewFolder,
                "选择游戏目录",
                { actions.importExisting() },
                enabled = !busy,
            )
            SmallIconButton(
                Icons.Outlined.Refresh,
                "刷新游戏目录",
                { actions.run { actions.controller.refreshGameDirectory() } },
                enabled = selected != null && !busy,
            )
            if (selected != null) {
                SmallIconButton(
                    Icons.Outlined.LinkOff,
                    "移除游戏目录",
                    { removing = selected },
                    enabled = !busy,
                )
            }
        }
        // 当前目录行：路径单行省略，行尾保留解绑入口
        DirectoryRow(
            name = selected?.name ?: "MoliFox 默认目录",
            path =
                selected?.path ?: actions.controller.store.minecraftRoot
                    .toString(),
            current = true,
            onMakeDefault = null,
            onRemove = if (selected != null) ({ removing = selected }) else null,
        )
        // 其它目录：点击整行或行尾图标都能设为默认
        state.data.gameDirectories
            .filter { it.id != selected?.id }
            .forEach { directory ->
                DirectoryRow(
                    name = directory.name,
                    path = directory.path,
                    current = false,
                    onMakeDefault = {
                        actions.run { actions.controller.selectGameDirectory(directory.id) }
                    },
                    onRemove = { removing = directory },
                )
            }
    }
    removing?.let { directory ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("移除目录「" + directory.name + "」？") },
            text = { Text("移除该目录及其版本在 MoliFox 中的记录，游戏文件保留在原位置。") },
            confirmButton = {
                TextButton({
                    actions.run { actions.controller.removeGameDirectory(directory.id) }
                    removing = null
                }) {
                    Text("移除目录记录")
                }
            },
            dismissButton = { TextButton({ removing = null }) { Text("取消") } },
        )
    }
}

/** 34dp 圆角 4 的紧凑下拉：M3 默认下拉按钮太高，会把工具行整行撑开。目录与排序共用。 */
@Composable
internal fun CompactSelect(
    value: String,
    values: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    SimpleDropdown(value, values, onSelect, modifier, enabled)
}

/** 目录行：44dp，路径 12sp 单行省略；非当前目录可整行点击设为默认。 */
@Composable
private fun DirectoryRow(
    name: String,
    path: String,
    current: Boolean,
    onMakeDefault: (() -> Unit)?,
    onRemove: (() -> Unit)?,
) {
    val shape = RoundedCornerShape(Metrics.innerRadius)
    Row(
        Modifier
            .fillMaxWidth()
            .height(Metrics.rowHeight)
            .then(
                if (onMakeDefault != null) {
                    Modifier.hoverHighlight(shape).clickable(onClick = onMakeDefault)
                } else {
                    Modifier
                },
            ).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(2.dp)
                .height(20.dp)
                .background(
                    if (current) MaterialTheme.colorScheme.primary else Color.Transparent,
                    RoundedCornerShape(1.dp),
                ),
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            if (current) Icons.Outlined.Folder else Icons.Outlined.FolderOpen,
            null,
            Modifier.size(16.dp),
            if (current) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                name,
                fontSize = 13.sp,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                path,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (current) Tag("当前")
        if (onMakeDefault != null) {
            SmallIconButton(Icons.Outlined.CheckCircle, "设为默认目录", onMakeDefault)
        }
        if (onRemove != null) SmallIconButton(Icons.Outlined.DeleteOutline, "移除目录", onRemove)
    }
}
