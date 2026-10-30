// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 实例列表的重命名对话框：复用控制器的 renameInstance，页面只负责输入与错误展示。

package dev.molifox.desktop.instances

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import dev.molifox.core.GameInstance
import dev.molifox.core.instanceFolderName
import dev.molifox.desktop.CompactTextField
import dev.molifox.desktop.DialogActions
import dev.molifox.desktop.Modal
import dev.molifox.desktop.UiActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 重命名对话框：复用控制器的 renameInstance（移动 versions 目录并同步引用），本页只负责输入与错误展示。 */
@Composable
internal fun RenameInstanceDialog(
    instance: GameInstance,
    actions: UiActions,
    onDismiss: () -> Unit,
) {
    var name by remember(instance.id) { mutableStateOf(instance.name) }
    var error by remember(instance.id) { mutableStateOf("") }
    var running by remember(instance.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val trimmed = name.trim()
    val valid = trimmed.isNotEmpty() && trimmed.length <= 80 && trimmed != instance.name
    Modal(
        "重命名实例",
        "会重命名 versions/" + instance.instanceFolderName() + "，并同步同名 JSON/JAR 与其他版本的引用。",
        onDismiss = onDismiss,
    ) {
        CompactTextField(
            name,
            {
                name = it.take(80)
                error = ""
            },
            Modifier.fillMaxWidth(),
            label = "实例名称",
            placeholder = instance.name,
        )
        if (error.isNotBlank()) {
            Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
        DialogActions(
            confirmText = "保存",
            onConfirm = {
                running = true
                scope.launch {
                    val failure =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                actions.controller.renameInstance(instance.id, trimmed)
                            }.exceptionOrNull()
                        }
                    running = false
                    if (failure == null) {
                        onDismiss()
                    } else {
                        error = failure.message ?: "重命名失败，请稍后重试"
                    }
                }
            },
            onCancel = onDismiss,
            enabled = valid,
            busy = running,
            hint = if (valid) "" else "名称需要 1–80 个字符，且不能与当前名称相同",
        )
    }
}
