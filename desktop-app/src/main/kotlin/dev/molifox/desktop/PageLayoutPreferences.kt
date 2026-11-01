// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责各页面布局与显示项的设置。

package dev.molifox.desktop

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.molifox.core.LauncherSettings
import dev.molifox.core.PagePreferences

@Composable
fun PageLayoutPreferences(
    settings: LauncherSettings,
    apply: AppearanceChange,
) {
    var page by remember { mutableIntStateOf(0) }
    val options = settings.pages
    val strings = LocalStrings.current
    SecondaryTabRow(page, containerColor = MaterialTheme.colorScheme.surface) {
        listOf(
            "settings.pageLayout.tab.home",
            "settings.pageLayout.tab.versions",
            "settings.pageLayout.tab.downloads",
            "settings.pageLayout.tab.logs",
            "settings.pageLayout.tab.launch",
        ).forEachIndexed { index, key ->
            Tab(page == index, { page = index }, text = { Text(strings.t(key)) })
        }
    }
    when (page) {
        0 -> HomePageLayout(options, apply)
        1 -> VersionsPageLayout(options, apply)
        2 -> DownloadsPageLayout(options, apply)
        3 -> LogsPageLayout(options, apply)
        else -> LaunchPageLayout(options, apply)
    }
}

/** 开始页：统计条与最近游玩面板的显示开关。 */
@Composable
private fun HomePageLayout(
    options: PagePreferences,
    apply: AppearanceChange,
) {
    val strings = LocalStrings.current
    SettingsSection(
        strings.t("settings.pageLayout.home.title"),
        strings.t("settings.pageLayout.home.detail"),
        contentGap = 0.dp,
    ) {
        PreferenceSwitch(
            strings.t("settings.pageLayout.home.statistics.title"),
            strings.t("settings.pageLayout.home.statistics.detail"),
            options.home.showStatistics,
        ) { value ->
            apply {
                it.copy(
                    pages =
                        it.pages.copy(
                            home = it.pages.home.copy(showStatistics = value),
                        ),
                )
            }
        }
        SettingDivider()
        PreferenceSwitch(
            strings.t("settings.pageLayout.home.recent.title"),
            strings.t("settings.pageLayout.home.recent.detail"),
            options.home.recentPlayExpanded,
        ) { value ->
            apply {
                it.copy(
                    pages =
                        it.pages.copy(
                            home = it.pages.home.copy(recentPlayExpanded = value),
                        ),
                )
            }
        }
    }
}

/** 版本库：默认视图、默认排序、收藏优先与备注显示。 */
@Composable
private fun VersionsPageLayout(
    options: PagePreferences,
    apply: AppearanceChange,
) {
    val strings = LocalStrings.current
    SettingsSection(strings.t("settings.pageLayout.versions.title"), contentGap = 0.dp) {
        SettingRow(strings.t("settings.pageLayout.versions.view")) {
            SimpleDropdown(
                options.versions.view,
                listOf(
                    "list" to strings.t("settings.pageLayout.versions.list"),
                    "grid" to strings.t("settings.pageLayout.versions.grid"),
                ),
                { value ->
                    apply {
                        it.copy(
                            pages =
                                it.pages.copy(
                                    versions = it.pages.versions.copy(view = value),
                                ),
                        )
                    }
                },
                Modifier.width(170.dp),
            )
        }
        SettingDivider()
        SettingRow(strings.t("settings.pageLayout.versions.sort")) {
            SimpleDropdown(
                options.versions.sort,
                listOf(
                    "recent" to strings.t("settings.pageLayout.versions.sort.recent"),
                    "name" to strings.t("settings.pageLayout.versions.sort.name"),
                    "created" to strings.t("settings.pageLayout.versions.sort.created"),
                ),
                { value ->
                    apply {
                        it.copy(
                            pages =
                                it.pages.copy(
                                    versions = it.pages.versions.copy(sort = value),
                                ),
                        )
                    }
                },
                Modifier.width(170.dp),
            )
        }
        SettingDivider()
        PreferenceSwitch(
            strings.t("settings.pageLayout.versions.favorites"),
            strings.t("settings.pageLayout.versions.favorites.detail"),
            options.versions.favoritesFirst,
        ) { value ->
            apply {
                it.copy(
                    pages =
                        it.pages.copy(
                            versions = it.pages.versions.copy(favoritesFirst = value),
                        ),
                )
            }
        }
        SettingDivider()
        PreferenceSwitch(
            strings.t("settings.pageLayout.versions.notes"),
            strings.t("settings.pageLayout.versions.notes.detail"),
            options.versions.showNotes,
        ) { value ->
            apply {
                it.copy(
                    pages =
                        it.pages.copy(versions = it.pages.versions.copy(showNotes = value)),
                )
            }
        }
    }
}

/** 下载中心：资源卡片的紧凑度与作者、简介显示。 */
@Composable
private fun DownloadsPageLayout(
    options: PagePreferences,
    apply: AppearanceChange,
) {
    val strings = LocalStrings.current
    SettingsSection(strings.t("settings.pageLayout.downloads.title"), contentGap = 0.dp) {
        PreferenceSwitch(
            strings.t("settings.pageLayout.downloads.compact"),
            strings.t("settings.pageLayout.downloads.compact.detail"),
            options.downloads.compactCards,
        ) { value ->
            apply {
                it.copy(
                    pages =
                        it.pages.copy(
                            downloads = it.pages.downloads.copy(compactCards = value),
                        ),
                )
            }
        }
        SettingDivider()
        PreferenceSwitch(
            strings.t("settings.pageLayout.downloads.authors"),
            strings.t("settings.pageLayout.downloads.authors.detail"),
            options.downloads.showAuthors,
        ) { value ->
            apply {
                it.copy(
                    pages =
                        it.pages.copy(
                            downloads = it.pages.downloads.copy(showAuthors = value),
                        ),
                )
            }
        }
        SettingDivider()
        PreferenceSwitch(
            strings.t("settings.pageLayout.downloads.descriptions"),
            strings.t("settings.pageLayout.downloads.descriptions.detail"),
            options.downloads.showDescriptions,
        ) { value ->
            apply {
                it.copy(
                    pages =
                        it.pages.copy(
                            downloads = it.pages.downloads.copy(showDescriptions = value),
                        ),
                )
            }
        }
    }
}

/** 运行日志：自动滚动与字号。 */
@Composable
private fun LogsPageLayout(
    options: PagePreferences,
    apply: AppearanceChange,
) {
    val strings = LocalStrings.current
    SettingsSection(strings.t("settings.pageLayout.logs.title"), contentGap = 0.dp) {
        PreferenceSwitch(
            strings.t("settings.pageLayout.logs.autoScroll"),
            strings.t("settings.pageLayout.logs.autoScroll.detail"),
            options.logs.autoScroll,
        ) { value ->
            apply {
                it.copy(
                    pages = it.pages.copy(logs = it.pages.logs.copy(autoScroll = value)),
                )
            }
        }
        SettingDivider()
        PreferenceSlider(
            strings.t("settings.pageLayout.logs.fontSize"),
            options.logs.fontSize.toFloat(),
            10f..18f,
            { it.toInt().toString() + " sp" },
        ) { value ->
            apply {
                it.copy(
                    pages =
                        it.pages.copy(logs = it.pages.logs.copy(fontSize = value.toInt())),
                )
            }
        }
    }
}

/** 启动设置：是否显示高级参数输入区。 */
@Composable
private fun LaunchPageLayout(
    options: PagePreferences,
    apply: AppearanceChange,
) {
    val strings = LocalStrings.current
    SettingsSection(strings.t("settings.pageLayout.launch.title")) {
        PreferenceSwitch(
            strings.t("settings.pageLayout.launch.advanced"),
            strings.t("settings.pageLayout.launch.advanced.detail"),
            options.showAdvancedLaunchOptions,
        ) { value ->
            apply { it.copy(pages = it.pages.copy(showAdvancedLaunchOptions = value)) }
        }
    }
}

@Composable
private fun PreferenceSwitch(
    title: String,
    detail: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val strings = LocalStrings.current
    SettingRow(title, detail) {
        Switch(
            checked,
            onChange,
            Modifier.semantics { contentDescription = strings.t("settings.common.switchA11y", title) },
        )
    }
}
