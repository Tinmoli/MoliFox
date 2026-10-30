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
        "复制存档",
        "把「" + saveName + "」复制到另一个实例 · 原存档不会被移动或删除",
        onDismiss = onDismiss,
    ) {
        if (targets.isEmpty()) {
            EmptyState(
                "没有可用的目标实例",
                "需要另一个已安装且使用不同游戏目录的实例。",
                Icons.Outlined.Info,
            )
            DialogActions(confirmText = "知道了", onConfirm = onDismiss, onCancel = null)
        } else {
            SettingsSection(
                "目标实例",
                "支持外部实例；原存档保留，同名存档会自动加序号。",
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
                label = "新名称（可留空沿用原名）",
                placeholder = defaultName,
                hint = if (trimmed.isEmpty()) "留空时沿用「" + defaultName + "」" else null,
            )
            Text(
                "目标已有同名存档时会自动加序号（如「World 2」），不会覆盖。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            DialogActions(
                confirmText = "开始复制",
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
                        error = failure.message ?: "无法开始复制，请稍后重试"
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
