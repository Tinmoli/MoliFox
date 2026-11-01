// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 实例详情页的重命名对话框：输入名称并调用控制器移动 versions 目录。

package dev.molifox.desktop.instance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.GameInstance
import dev.molifox.core.validateInstanceDirectoryName
import dev.molifox.desktop.LocalStrings
import dev.molifox.desktop.UiActions

/** 重命名实例：确认后由控制器移动目录，失败时保留输入并显示原因。 */
@Composable
internal fun InstanceRenameDialog(
    instance: GameInstance,
    actions: UiActions,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    var name by remember { mutableStateOf(instance.name) }
    var renameError by remember { mutableStateOf("") }
    val trimmed = name.trim()
    val validation =
        if (trimmed == instance.name) {
            null
        } else {
            runCatching { validateInstanceDirectoryName(trimmed) }.exceptionOrNull()?.message
        }
    val currentDirectory =
        runCatching {
            actions.controller.store
                .versionGamePath(instance)
                .fileName
                .toString()
        }.getOrDefault(instance.gameDirectoryName)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.t("dialogs.rename.title")) },
        text = {
            Column {
                OutlinedTextField(
                    name,
                    {
                        name = it.take(80)
                        renameError = ""
                    },
                    Modifier.fillMaxWidth(),
                    label = { Text(strings.t("dialogs.rename.nameLabel")) },
                    singleLine = true,
                    isError = validation != null || renameError.isNotBlank(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        validation != null -> validation
                        renameError.isNotBlank() -> renameError
                        trimmed == instance.name ->
                            strings.t("dialogs.rename.inputHint", currentDirectory)
                        actions.controller.isInstanceBusy(instance.id) ->
                            strings.t("dialogs.rename.busyHint")
                        else ->
                            strings.t("dialogs.rename.message", currentDirectory, trimmed)
                    },
                    fontSize = 11.sp,
                    color =
                        if (validation != null || renameError.isNotBlank()) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled =
                    trimmed != instance.name &&
                        validation == null &&
                        !actions.controller.isInstanceBusy(instance.id),
                onClick = {
                    val failure =
                        runCatching { actions.controller.renameInstance(instance.id, trimmed) }
                            .exceptionOrNull()
                    if (failure == null) {
                        onDismiss()
                    } else {
                        renameError = failure.message ?: strings.t("dialogs.rename.failed")
                    }
                },
            ) {
                Text(strings.t("dialogs.common.save"))
            }
        },
        dismissButton = { TextButton({ onDismiss() }) { Text(strings.t("dialogs.common.cancel")) } },
    )
}
