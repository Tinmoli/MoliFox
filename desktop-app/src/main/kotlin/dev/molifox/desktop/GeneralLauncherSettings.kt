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
import dev.molifox.core.i18n.AppLanguage

/** 通用：更新检查与语言。 */
@Composable
internal fun generalSettingCards(
    actions: UiActions,
    writer: SettingsWriter,
    settings: LauncherSettings,
): List<SettingCard> {
    val strings = LocalStrings.current
    return listOf(
        SettingCard(
            strings.t("settings.general.update.title"),
            strings.t("settings.general.update.detail"),
            listOf(
                settingControl("GitHub Releases") {
                    val state by actions.controller.state.collectAsState()
                    LauncherUpdatePanel(state.update, actions)
                },
                settingRow(
                    strings.t("settings.general.update.autoCheck"),
                    strings.t("settings.general.update.autoCheckDetail"),
                ) {
                    CompactSwitch(
                        settings.autoCheckUpdates,
                        { enabled -> writer.commit { it.copy(autoCheckUpdates = enabled) } },
                    )
                },
            ),
        ),
        SettingCard(
            strings.t("settings.general.language.title"),
            strings.t("settings.general.language.detail"),
            listOf(
                settingField(
                    strings.t("settings.language.interface.title"),
                    strings.t("settings.language.interface.detail"),
                ) {
                    SimpleDropdown(
                        settings.language,
                        AppLanguage.options.map { language -> language.settingValue to strings.t(language.labelKey) },
                        { value -> writer.commit { it.copy(language = value) } },
                    )
                },
                settingRow(
                    strings.t("settings.language.gameFollow.title"),
                    strings.t("settings.language.gameFollow.detail"),
                ) {
                    CompactSwitch(
                        settings.gameLanguageFollowsLauncher,
                        { enabled ->
                            writer.commit { it.copy(gameLanguageFollowsLauncher = enabled) }
                        },
                        Modifier.semantics {
                            contentDescription = strings.t("settings.language.gameFollow.switch")
                        },
                    )
                },
            ),
        ),
    )
}

/** 关于页提供项目入口、运行信息与随程序分发的许可文本。 */
@Composable
internal fun aboutSettingCards(actions: UiActions): List<SettingCard> {
    val strings = LocalStrings.current
    return listOf(
        SettingCard(
            strings.t("settings.about.title"),
            blocks = listOf(settingControl("MoliFox") { LauncherAboutPanel(actions) }),
        ),
    )
}
