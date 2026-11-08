// 公开 GitHub 清单、更新包与联机核心共用设置。
package dev.molifox.desktop

import androidx.compose.runtime.Composable
import dev.molifox.core.GitHubSource
import dev.molifox.core.LauncherSettings

@Composable
internal fun githubSourceSettingCard(
    actions: UiActions,
    writer: SettingsWriter,
    settings: LauncherSettings,
): SettingCard {
    val strings = LocalStrings.current
    return SettingCard(
        strings.t("settings.github.title"),
        strings.t("settings.github.detail"),
        listOf(
            settingField(strings.t("settings.github.source")) {
                SimpleDropdown(
                    settings.githubSource,
                    GitHubSource.entries.map {
                        it.key to
                            strings.t(
                                "settings.github.source.${it.key.replace("-", "")}",
                            )
                    },
                    { value ->
                        writer.commit {
                            it.copy(
                                githubSource = value,
                                githubCustomProxy =
                                    if (value == "custom" && it.githubCustomProxy.isBlank()) {
                                        GitHubSource.GhProxy.prefix
                                    } else {
                                        it.githubCustomProxy
                                    },
                            )
                        }
                    },
                )
            },
            settingField(strings.t("settings.github.custom"), strings.t("settings.github.customDetail")) {
                LiveTextField(settings.githubCustomProxy, { value ->
                    applySetting(actions) { it.copy(githubCustomProxy = value.trim()) }
                }, placeholder = GitHubSource.GhProxy.prefix)
            },
        ),
    )
}
