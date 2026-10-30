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
    SecondaryTabRow(page, containerColor = MaterialTheme.colorScheme.surface) {
        listOf("开始页", "版本库", "下载中心", "运行日志", "启动设置").forEachIndexed { index, title ->
            Tab(page == index, { page = index }, text = { Text(title) })
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
    SettingsSection("开始页", "选择首页显示的内容，账户与启动操作始终保留。", contentGap = 0.dp) {
        PreferenceSwitch("游戏统计", "在页头显示版本和账户数量", options.home.showStatistics) { value ->
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
            "展开最近游玩",
            "首页「最近游玩的存档或服务器」是否展开；在卡片标题行折叠后这里同步变化",
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
    SettingsSection("版本库", contentGap = 0.dp) {
        SettingRow("默认视图") {
            SimpleDropdown(
                options.versions.view,
                listOf("list" to "列表", "grid" to "网格"),
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
        SettingRow("默认排序") {
            SimpleDropdown(
                options.versions.sort,
                listOf("recent" to "最近游玩", "name" to "按名称", "created" to "最近添加"),
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
            "收藏优先",
            "按最近游玩排列时，将收藏版本放在前面",
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
        PreferenceSwitch("显示备注", "在列表中显示版本备注", options.versions.showNotes) { value ->
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
    SettingsSection("下载中心资源卡片", contentGap = 0.dp) {
        PreferenceSwitch(
            "紧凑卡片",
            "缩短资源卡片高度，显示更多结果",
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
        PreferenceSwitch("显示作者", "显示资源作者名称", options.downloads.showAuthors) { value ->
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
        PreferenceSwitch("显示简介", "显示资源说明摘要", options.downloads.showDescriptions) { value ->
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
    SettingsSection("运行日志", contentGap = 0.dp) {
        PreferenceSwitch("日志自动滚动", "新日志出现时自动跟随到末尾", options.logs.autoScroll) { value ->
            apply {
                it.copy(
                    pages = it.pages.copy(logs = it.pages.logs.copy(autoScroll = value)),
                )
            }
        }
        SettingDivider()
        PreferenceSlider(
            "日志字号",
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
    SettingsSection("启动设置") {
        PreferenceSwitch(
            "显示高级参数",
            "控制全局与版本设置中的 JVM 输入区域；已有参数仍会生效",
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
    SettingRow(title, detail) {
        Switch(checked, onChange, Modifier.semantics { contentDescription = title + "开关" })
    }
}
