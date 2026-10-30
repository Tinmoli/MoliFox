// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 集中启动器通用和关于入口，避免游戏选项影响程序设置。

package dev.molifox.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.molifox.core.LauncherSettings

/** 通用：更新检查与语言。 */
@Composable
internal fun generalSettingCards(
    actions: UiActions,
    writer: SettingsWriter,
    settings: LauncherSettings,
): List<SettingCard> =
    listOf(
        SettingCard(
            "启动器更新",
            "从 MoliFox 官方 GitHub Releases 检查正式版本。",
            listOf(
                settingControl("GitHub Releases") {
                    val state by actions.controller.state.collectAsState()
                    LauncherUpdatePanel(state.update, actions)
                },
                settingRow("启动时自动检查更新", "检查过程中可以正常使用启动器") {
                    CompactSwitch(
                        settings.autoCheckUpdates,
                        { enabled -> writer.commit { it.copy(autoCheckUpdates = enabled) } },
                    )
                },
            ),
        ),
        SettingCard(
            "语言",
            "当前仅提供简体中文；多语言在后续版本提供。",
            listOf(
                settingRow("界面语言", "切换语言需要重启启动器") { Tag("简体中文") },
                settingRow("游戏语言跟随启动器", "下次启动时将所选实例的游戏语言设为简体中文；关闭后保留游戏内选择。") {
                    CompactSwitch(
                        settings.gameLanguageFollowsLauncher,
                        { enabled ->
                            writer.commit { it.copy(gameLanguageFollowsLauncher = enabled) }
                        },
                        Modifier.semantics { contentDescription = "游戏语言跟随启动器开关" },
                    )
                },
            ),
        ),
    )

/** 关于页提供项目入口、运行信息与随程序分发的许可文本。 */
@Composable
internal fun aboutSettingCards(actions: UiActions): List<SettingCard> =
    listOf(
        SettingCard(
            "关于 MoliFox",
            blocks = listOf(settingControl("MoliFox") { LauncherAboutPanel(actions) }),
        ),
    )
