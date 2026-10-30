// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 实例设置页签的「高级启动参数」：独立启动设置开关与编辑器。

package dev.molifox.desktop.instance

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import dev.molifox.core.GameInstance
import dev.molifox.core.LauncherSettings
import dev.molifox.desktop.AdvancedLaunchEditor
import dev.molifox.desktop.CompactSwitch
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
    if (settings.pages.showAdvancedLaunchOptions) {
        SettingsSection(
            "高级启动参数",
            "留空继承全局 JVM 参数；填写后覆盖全局附加参数。",
        ) {
            OutlinedTextField(
                draft.extraJvmArgs,
                { onDraftChange(draft.copy(extraJvmArgs = it)) },
                Modifier.fillMaxWidth().onFocusChanged { focus ->
                    if (
                        !focus.isFocused &&
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
                label = { Text("此版本的附加 JVM 参数") },
                minLines = 1,
                maxLines = 4,
            )
            SettingRow("使用独立高级启动设置") {
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
