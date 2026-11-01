// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 集中存档复制的目标选择与重名处理说明。

package dev.molifox.desktop

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import dev.molifox.core.GameInstance

/** `.zip` 后缀占 4 个字符，去掉后缀只用于对话框里的默认名称展示。 */
private const val ZIP_SUFFIX_LENGTH = 4

/** 复制存档到其他实例：目标下拉只列 MoliFox 自建、启用版本隔离的实例（排除当前实例）。 新名称默认沿用原名，可清空表示沿用；目标已有同名存档时由后端自动加序号，从不覆盖。 */
@Composable
fun CopySaveDialog(
    instance: GameInstance,
    saveName: String,
    actions: UiActions,
    onDismiss: () -> Unit,
    onStarted: (String) -> Unit = {},
) {
    val strings = LocalStrings.current
    val state by actions.controller.state.collectAsState()
    val targets =
        state.data.instances.filter {
            it.id != instance.id &&
                it.installed &&
                actions.controller.store
                    .instancePath(it.id)
                    .toAbsolutePath()
                    .normalize() !=
                actions.controller.store
                    .instancePath(instance.id)
                    .toAbsolutePath()
                    .normalize()
        }
    val defaultName = saveNameWithoutZip(saveName)
    var targetId by remember(saveName) { mutableStateOf(targets.firstOrNull()?.id.orEmpty()) }
    var rename by remember(saveName) { mutableStateOf(defaultName) }
    var error by remember(saveName) { mutableStateOf<String?>(null) }
    val trimmed = rename.trim()
    Modal(
        strings.t("dialogs.copySave.title"),
        strings.t("dialogs.copySave.subtitle", saveName),
        onDismiss = onDismiss,
    ) {
        if (targets.isEmpty()) {
            EmptyState(
                strings.t("dialogs.copySave.noTarget"),
                strings.t("dialogs.copySave.noTargetHint"),
                Icons.Outlined.Info,
            )
            DialogActions(confirmText = strings.t("dialogs.common.gotIt"), onConfirm = onDismiss, onCancel = null)
        } else {
            SettingsSection(
                strings.t("dialogs.copySave.target"),
                strings.t("dialogs.copySave.targetHint"),
            ) {
                SimpleDropdown(
                    value = targetId,
                    values = targets.map { it.id to (it.name + " · " + it.gameVersion) },
                    onSelect = {
                        targetId = it
                        error = null
                    },
                )
            }
            CompactTextField(
                rename,
                { rename = it.take(80) },
                Modifier.fillMaxWidth(),
                label = strings.t("dialogs.copySave.newNameLabel"),
                placeholder = defaultName,
                hint = if (trimmed.isEmpty()) strings.t("dialogs.copySave.nameHint", defaultName) else null,
            )
            Text(
                strings.t("dialogs.copySave.sameNameNote"),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            DialogActions(
                confirmText = strings.t("dialogs.copySave.confirm"),
                onConfirm = {
                    try {
                        val jobId =
                            actions.controller.copySaveToInstance(
                                instance.id,
                                saveName,
                                targetId,
                                trimmed.ifBlank { null },
                            )
                        onStarted(jobId)
                        onDismiss()
                    } catch (failure: IllegalStateException) {
                        error = failure.message ?: strings.t("dialogs.copySave.failed")
                    }
                },
                onCancel = onDismiss,
                enabled = targetId.isNotBlank(),
            )
        }
    }
}

/** 存档名去掉 `.zip` 后缀（不区分大小写），只用于对话框里的默认名称展示。 */
private fun saveNameWithoutZip(name: String): String =
    if (name.endsWith(".zip", true)) name.dropLast(ZIP_SUFFIX_LENGTH) else name
