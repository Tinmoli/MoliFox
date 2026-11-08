// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 设置页签的「当前安装」卡片；加载器版本更换已移到「修改」页签。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.GameInstance
import dev.molifox.core.LoaderType

internal fun packSourceOf(instance: GameInstance): String =
    when {
        instance.packFile.isNotBlank() ->
            tr("pages.instance.install.packsource.pack", instance.packFile.substringAfterLast('/'))
        instance.externalProfile -> tr("pages.instance.install.packsource.external")
        else -> tr("pages.instance.install.packsource.launcher")
    }

/** 设置页签的「当前安装」卡片：只展示当前组件，并提供修复入口；版本更换见「修改」页签。 */
@Composable
internal fun ColumnScope.InstanceInstallSections(
    instance: GameInstance,
    actions: UiActions,
) {
    val strings = LocalStrings.current
    SettingsSection(strings.t("pages.instance.install.current.title")) {
        SettingRow(
            strings.t("pages.instance.install.current.gameVersion"),
            instance.gameVersion.ifBlank { strings.t("pages.instance.meta.versionUnknown") },
        ) {
            InstanceArtwork(instance, actions, Modifier.size(24.dp))
        }
        SettingRow(
            strings.t("pages.instance.install.current.loader"),
            if (instance.loader == LoaderType.Vanilla) {
                strings.t("pages.instance.install.current.vanillaLoader")
            } else {
                instance.loader.title + " " + instance.loaderVersion
            },
        ) {}
        Text(
            strings.t("pages.instance.install.current.versionId", instance.versionId),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                {
                    actions.run { actions.controller.repair(instance.id) }
                    actions.taskQueued()
                },
                enabled = !rememberInstanceBusy(actions, instance.id),
            ) {
                Text(strings.t("pages.instance.install.current.repair"), fontSize = 12.sp)
            }
        }
    }
}
