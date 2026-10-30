// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 展示模组来源确认与删除确认，只向调用方发送确认和关闭事件。

package dev.molifox.desktop

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import dev.molifox.core.ModFile
import dev.molifox.core.ModUpdateItem

@Composable
internal fun ModUpdateConfirmation(
    item: ModUpdateItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("可能不是同一项目") },
        text = {
            Text(
                "「" + item.title + "」是按名称匹配的，来源项目为「" +
                    item.candidate?.projectTitle.orEmpty().ifBlank {
                        item.candidate?.projectId.orEmpty()
                    } + "」。确认是同一项目后再更新。",
            )
        },
        confirmButton = {
            TextButton(onConfirm) {
                Text("仍然更新", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun DeleteModConfirmation(
    mod: ModFile,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除模组？") },
        text = {
            Text(
                "「" + mod.metadata.title.ifBlank { mod.filename.removeSuffix(".disabled") } +
                    "」将移至回收站，需要时可以再找回。",
            )
        },
        confirmButton = {
            TextButton(onConfirm) { Text("移入回收站", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}
