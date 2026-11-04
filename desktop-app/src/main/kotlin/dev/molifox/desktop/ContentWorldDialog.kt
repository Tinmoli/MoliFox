// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责世界包安装前的确认对话框：说明落点与同名世界的处理方式。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import dev.molifox.desktop.MoliTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.molifox.core.ContentHit
import dev.molifox.core.ContentVersion
import dev.molifox.core.GameInstance

/** 世界确认：说明会解压到目标实例的 saves/，同名世界不会覆盖。 */
@Composable
internal fun ContentWorldConfirmDialog(
    hit: ContentHit,
    ui: ContentProjectUiState,
    target: GameInstance,
    chosen: ContentVersion,
    actions: UiActions,
    onInstallStarted: () -> Unit,
) {
    val strings = LocalStrings.current
    Modal(
        strings.t("dialogs.world.install.title"),
        strings.t("dialogs.world.install.subtitle", target.name),
        onDismiss = { ui.confirmWorld = false },
    ) {
        Text(
            strings.t(
                "dialogs.world.install.download",
                hit.title,
                chosen.number.ifBlank { chosen.name },
            ),
        )
        Text(
            strings.t("dialogs.world.install.requirement"),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton({ ui.confirmWorld = false }) { Text(strings.t("dialogs.common.cancel")) }
            PrimaryButton(
                strings.t("dialogs.world.install.title"),
                enabled = !actions.controller.busy,
                onClick = {
                    actions.run {
                        actions.controller.installWorld(hit, chosen.id, target.id)
                    }
                    onInstallStarted()
                },
            )
        }
    }
}
