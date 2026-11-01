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
    val strings = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.t("pages.mods.confirm.title")) },
        text = {
            Text(
                strings.t(
                    "pages.mods.confirm.message",
                    item.title,
                    item.candidate?.projectTitle.orEmpty().ifBlank {
                        item.candidate?.projectId.orEmpty()
                    },
                ),
            )
        },
        confirmButton = {
            TextButton(onConfirm) {
                Text(strings.t("pages.mods.confirm.update"), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onDismiss) { Text(strings.t("pages.mods.confirm.cancel")) }
        },
    )
}

@Composable
internal fun DeleteModConfirmation(
    mod: ModFile,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.t("pages.mods.delete.title")) },
        text = {
            Text(
                strings.t(
                    "pages.mods.delete.message",
                    mod.metadata.title.ifBlank { mod.filename.removeSuffix(".disabled") },
                ),
            )
        },
        confirmButton = {
            TextButton(onConfirm) {
                Text(strings.t("pages.mods.delete.confirm"), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onDismiss) { Text(strings.t("pages.mods.delete.cancel")) }
        },
    )
}
