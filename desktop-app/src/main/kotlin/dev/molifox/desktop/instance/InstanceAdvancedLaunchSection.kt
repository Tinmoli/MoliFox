// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 实例设置页签的「高级启动参数」：独立启动设置开关与编辑器。

package dev.molifox.desktop.instance

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.molifox.core.GameInstance
import dev.molifox.core.LauncherSettings
import dev.molifox.desktop.AdvancedLaunchEditor
import dev.molifox.desktop.CompactSwitch
import dev.molifox.desktop.CompactTextField
import dev.molifox.desktop.LocalStrings
import dev.molifox.desktop.SettingRow
import dev.molifox.desktop.SettingsSection
import dev.molifox.desktop.UiActions

/** 高级启动参数卡片：只在设置页开启「高级启动参数」时出现。 */
@Composable
internal fun InstanceAdvancedLaunchSection(
    instance: GameInstance,
    actions: UiActions,
    settings: LauncherSettings,
    draft: GameInstance,
    onDraftChange: (GameInstance) -> Unit,
    onAdvancedValidChange: (Boolean) -> Unit,
) {
    val strings = LocalStrings.current
    if (settings.pages.showAdvancedLaunchOptions) {
        var expanded by remember { mutableStateOf(true) }
        SettingsSection(
            strings.t("pages.instance.advanced.title"),
            strings.t("pages.instance.advanced.hint"),
            action = {
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                )
            },
            onHeaderClick = { expanded = !expanded },
            showContent = expanded,
        ) {
            CompactTextField(
                draft.extraJvmArgs,
                { onDraftChange(draft.copy(extraJvmArgs = it)) },
                Modifier.fillMaxWidth(),
                onFocusChange = { focused ->
                    if (
                        !focused &&
                        extraArgumentError(
                            draft.extraJvmArgs,
                            "",
                        ) != null
                    ) {
                        onDraftChange(
                            draft.copy(
                                extraJvmArgs = instance.extraJvmArgs,
                            ),
                        )
                    }
                },
                label = strings.t("pages.instance.advanced.jvmLabel"),
                maxLines = 4,
            )
            SettingRow(strings.t("pages.instance.advanced.ownSettings")) {
                CompactSwitch(
                    draft.advancedLaunch != null,
                    { enabled ->
                        onDraftChange(
                            draft.copy(
                                advancedLaunch =
                                    if (enabled) {
                                        settings.advancedLaunch
                                            .copy()
                                    } else {
                                        null
                                    },
                            ),
                        )
                        onAdvancedValidChange(true)
                    },
                )
            }
            draft.advancedLaunch?.let { value ->
                AdvancedLaunchEditor(
                    value,
                    actions.controller.platform.osName,
                    { onDraftChange(draft.copy(advancedLaunch = it)) },
                    onAdvancedValidChange,
                )
            }
        }
    }
}
