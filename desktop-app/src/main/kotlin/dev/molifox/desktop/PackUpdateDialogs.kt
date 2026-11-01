// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 展示资源包/光影的来源确认，只向调用方发送确认和关闭事件。

package dev.molifox.desktop

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import dev.molifox.core.PackUpdateItem

@Composable
internal fun PackUpdateConfirmation(
    item: PackUpdateItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.t("dialogs.packUpdate.mismatch.title")) },
        text = {
            Text(
                strings.t(
                    "dialogs.packUpdate.mismatch.message",
                    item.filename,
                    item.candidate?.projectTitle.orEmpty().ifBlank {
                        item.candidate?.projectId.orEmpty()
                    },
                ),
            )
        },
        confirmButton = {
            TextButton(onConfirm) {
                Text(strings.t("dialogs.packUpdate.mismatch.confirm"), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(strings.t("dialogs.common.cancel")) } },
    )
}
