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
    val strings = LocalStrings.current
    var name by remember(instance.id, strings) {
        mutableStateOf(strings.t("dialogs.copyInstance.defaultName", instance.name))
    }
    var copySaves by remember(instance.id) { mutableStateOf(true) }
    var copyMods by remember(instance.id) { mutableStateOf(true) }
    var error by remember(instance.id) { mutableStateOf<String?>(null) }
    val trimmed = name.trim()
    val valid = trimmed.isNotEmpty() && trimmed.length <= 80
    Modal(
        strings.t("dialogs.copyInstance.title"),
        strings.t("dialogs.copyInstance.subtitle", instance.name),
        onDismiss = onDismiss,
    ) {
        CompactTextField(
            name,
            { name = it.take(80) },
            Modifier.fillMaxWidth(),
            label = strings.t("dialogs.copyInstance.nameLabel"),
            placeholder = strings.t("dialogs.copyInstance.defaultName", instance.name),
        )
        SettingsSection(strings.t("dialogs.copyInstance.section"), strings.t("dialogs.copyInstance.sectionHint")) {
            SettingRow(strings.t("dialogs.copyInstance.saves"), strings.t("dialogs.copyInstance.savesHint")) {
                CompactSwitch(copySaves, { copySaves = it })
            }
            SettingRow(strings.t("dialogs.copyInstance.mods"), strings.t("dialogs.copyInstance.modsHint")) {
                CompactSwitch(copyMods, { copyMods = it })
            }
        }
        Text(
            strings.t("dialogs.copyInstance.note"),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        DialogActions(
            confirmText = strings.t("dialogs.copyInstance.confirm"),
            onConfirm = {
                try {
                    actions.controller.copyInstance(instance.id, trimmed, copySaves, copyMods)
                    onDismiss()
                } catch (failure: IllegalStateException) {
                    // 后台任务繁忙、实例不存在或队列拒绝登记时只更新对话框内的错误文案。
                    error = failure.message ?: strings.t("dialogs.copyInstance.failed")
                } catch (failure: IllegalArgumentException) {
                    // 参数校验失败（与 requireIdle 的取值检查同类）同样只提示，不关闭对话框。
                    error = failure.message ?: strings.t("dialogs.copyInstance.failed")
                }
            },
            onCancel = onDismiss,
            enabled = valid,
            hint = if (valid) "" else strings.t("dialogs.copyInstance.nameInvalid"),
        )
    }
}
