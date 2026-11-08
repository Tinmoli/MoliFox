// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责同名文件冲突的二次确认：替换、跳过或取消。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.ContentInstallPlan
import dev.molifox.core.PlannedContentFile

/** 安装计划里出现同名文件（含同版本同内容）时，先让用户决定，不静默覆盖。 */
@Composable
internal fun ContentConflictEffect(
    ui: ContentProjectUiState,
    actions: UiActions,
    onInstall: () -> Unit,
) {
    val plan = ui.pendingPlan ?: return
    if (ui.conflicts.isEmpty()) return
    val strings = LocalStrings.current
    Modal(strings.t("dialogs.content.conflict.title"), "", onDismiss = {
        ui.pendingPlan = null
        ui.conflicts = emptyList()
    }) {
        Text(strings.t("dialogs.content.conflict.message", ui.conflicts.size))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ui.conflicts.take(6).forEach {
                Text(it.filename, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (ui.conflicts.size > 6) {
                Text("… +${ui.conflicts.size - 6}", fontSize = 11.sp)
            }
        }
        Text(
            strings.t("dialogs.content.conflict.hint"),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            SecondaryButton({
                ui.pendingPlan = null
                ui.conflicts = emptyList()
            }) { Text(strings.t("dialogs.common.cancel")) }
            SecondaryButton({
                installPlan(
                    actions = actions,
                    ui = ui,
                    plan = plan,
                    skipped = ui.conflicts,
                    onInstall = onInstall,
                )
            }) { Text(strings.t("dialogs.content.conflict.skip")) }
            PrimaryButton(
                strings.t("dialogs.content.conflict.replace"),
                onClick = {
                    installPlan(
                        actions = actions,
                        ui = ui,
                        plan = plan,
                        replace = true,
                        onInstall = onInstall,
                    )
                },
            )
        }
    }
}

private fun installPlan(
    actions: UiActions,
    ui: ContentProjectUiState,
    plan: ContentInstallPlan,
    replace: Boolean = false,
    skipped: List<PlannedContentFile> = emptyList(),
    onInstall: () -> Unit,
) {
    ui.pendingPlan = null
    ui.conflicts = emptyList()
    val skippedSet = skipped.toSet()
    actions.run {
        val prepared =
            if (replace) {
                plan.copy(overwriteExisting = true)
            } else {
                plan.copy(files = plan.files.filterNot { it in skippedSet })
            }
        installContentPlan(actions, prepared)
            .onFailure { ui.installError = it.message }
            .onSuccess { onInstall() }
    }
}
