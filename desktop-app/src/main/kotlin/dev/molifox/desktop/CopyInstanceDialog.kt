// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 集中实例复制的输入和提交，避免与导出流程混用状态。

package dev.molifox.desktop

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import dev.molifox.core.GameInstance

/** 复制实例：名称 +「复制存档」「复制模组」两个开关；确认后复制在任务队列里进行。 */
@Composable
fun CopyInstanceDialog(
    instance: GameInstance,
    actions: UiActions,
    onDismiss: () -> Unit,
) {
    var name by remember(instance.id) { mutableStateOf("${instance.name} - 副本") }
    var copySaves by remember(instance.id) { mutableStateOf(true) }
    var copyMods by remember(instance.id) { mutableStateOf(true) }
    var error by remember(instance.id) { mutableStateOf<String?>(null) }
    val trimmed = name.trim()
    val valid = trimmed.isNotEmpty() && trimmed.length <= 80
    Modal("复制实例", "以「${instance.name}」为模板新建独立实例", onDismiss = onDismiss) {
        CompactTextField(
            name,
            { name = it.take(80) },
            Modifier.fillMaxWidth(),
            label = "新实例名称",
            placeholder = "${instance.name} - 副本",
        )
        SettingsSection("复制内容", "日志、崩溃报告、缓存、升级备份与链接都不会复制。") {
            SettingRow("复制存档", "saves 目录中的世界存档") {
                CompactSwitch(copySaves, { copySaves = it })
            }
            SettingRow("复制模组", "mods 目录中的模组与已停用模组") {
                CompactSwitch(copyMods, { copyMods = it })
            }
        }
        Text(
            "副本拥有独立目录、文件账本与整合包归档；复制在后台任务中进行，进度显示在日志页。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        DialogActions(
            confirmText = "开始复制",
            onConfirm = {
                try {
                    actions.controller.copyInstance(instance.id, trimmed, copySaves, copyMods)
                    onDismiss()
                } catch (failure: IllegalStateException) {
                    // 后台任务繁忙、实例不存在或队列拒绝登记时只更新对话框内的错误文案。
                    error = failure.message ?: "无法开始复制，请稍后重试"
                } catch (failure: IllegalArgumentException) {
                    // 参数校验失败（与 requireIdle 的取值检查同类）同样只提示，不关闭对话框。
                    error = failure.message ?: "无法开始复制，请稍后重试"
                }
            },
            onCancel = onDismiss,
            enabled = valid,
            hint = if (valid) "" else "名称需要 1–80 个字符",
        )
    }
}
