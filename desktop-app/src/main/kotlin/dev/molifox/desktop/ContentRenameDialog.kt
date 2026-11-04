// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.desktop

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import dev.molifox.desktop.MoliTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun ContentRenameDialog(
    title: String,
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    var value by remember(currentName) { mutableStateOf(currentName) }
    AlertDialog(
        properties = DialogProperties(dismissOnClickOutside = false),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(strings.t("content.rename.label")) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton({ onConfirm(value) }, enabled = value.isNotBlank()) {
                Text(strings.t("content.rename.confirm"))
            }
        },
        dismissButton = {
            TextButton(onDismiss) { Text(strings.t("content.rename.cancel")) }
        },
    )
}
