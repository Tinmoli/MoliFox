// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责世界包安装前的确认对话框：说明落点与同名世界的处理方式。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    Modal(
        "安装世界",
        "${target.name} · 安装到 saves/",
        onDismiss = { ui.confirmWorld = false },
    ) {
        Text(
            "将下载 ${hit.title} ${chosen.number.ifBlank { chosen.name }}，" +
                "并解压到该实例的 saves 文件夹。",
        )
        Text(
            "压缩包必须包含 level.dat；包内若有一层世界目录会保留为世界名。" +
                "存档里已有同名世界时不会覆盖。",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton({ ui.confirmWorld = false }) { Text("取消") }
            PrimaryButton(
                "安装世界",
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
