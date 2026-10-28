// 负责各页面布局与显示项的设置。

package dev.molifox.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.molifox.core.*

@Composable
fun PageLayoutPreferences(settings: LauncherSettings, apply: AppearanceChange) {
    var page by remember { mutableIntStateOf(0) }
    val options = settings.pages
    SecondaryTabRow(page, containerColor = MaterialTheme.colorScheme.surface) {
        listOf("开始页", "版本库", "下载中心", "运行日志", "启动设置").forEachIndexed { index, title ->
            Tab(page == index, { page = index }, text = { Text(title) })
        }
    }
    when (page) {
        0 ->
            SettingsSection("开始页", "选择首页显示的内容，账户与启动操作始终保留。", contentGap = 0.dp) {
                PreferenceSwitch("游戏统计", "在页头显示版本和账户数量", options.home.showStatistics) { value ->
                    apply {
                        it.copy(
                            pages = it.pages.copy(home = it.pages.home.copy(showStatistics = value))
                        )
                    }
                }
                SettingDivider()
                PreferenceSwitch("最近游玩", "在首页列出最近使用的版本", options.home.showRecent) { value ->
                    apply {
                        it.copy(
                            pages = it.pages.copy(home = it.pages.home.copy(showRecent = value))
                        )
                    }
                }
                SettingDivider()
                SettingRow("最近版本数量", "首页最多显示 6 条，其余请到实例页查看") {
                    SimpleDropdown(
                        options.home.recentCount.toString(),
                        listOf(3, 5, 8, 12).map { it.toString() to (it.toString() + " 个版本") },
                        { value ->
                            apply {
                                it.copy(
                                    pages =
                                        it.pages.copy(
                                            home = it.pages.home.copy(recentCount = value.toInt())
                                        )
                                )
                            }
                        },
                        Modifier.width(170.dp),
                    )
                }
            }
        1 ->
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
                                            versions = it.pages.versions.copy(view = value)
                                        )
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
                                            versions = it.pages.versions.copy(sort = value)
                                        )
                                )
                            }
                        },
                        Modifier.width(170.dp),
                    )
                }
                SettingDivider()
                PreferenceSwitch("收藏优先", "按最近游玩排列时，将收藏版本放在前面", options.versions.favoritesFirst) {
                    value ->
                    apply {
                        it.copy(
                            pages =
                                it.pages.copy(
                                    versions = it.pages.versions.copy(favoritesFirst = value)
                                )
                        )
                    }
                }
                SettingDivider()
                PreferenceSwitch("显示备注", "在列表中显示版本备注", options.versions.showNotes) { value ->
                    apply {
                        it.copy(
                            pages =
                                it.pages.copy(versions = it.pages.versions.copy(showNotes = value))
                        )
                    }
                }
            }
        2 ->
            SettingsSection("下载中心资源卡片", contentGap = 0.dp) {
                PreferenceSwitch("紧凑卡片", "缩短资源卡片高度，显示更多结果", options.downloads.compactCards) { value
                    ->
                    apply {
                        it.copy(
                            pages =
                                it.pages.copy(
                                    downloads = it.pages.downloads.copy(compactCards = value)
                                )
                        )
                    }
                }
                SettingDivider()
                PreferenceSwitch("显示作者", "显示资源作者名称", options.downloads.showAuthors) { value ->
                    apply {
                        it.copy(
                            pages =
                                it.pages.copy(
                                    downloads = it.pages.downloads.copy(showAuthors = value)
                                )
                        )
                    }
                }
                SettingDivider()
                PreferenceSwitch("显示简介", "显示资源说明摘要", options.downloads.showDescriptions) { value ->
                    apply {
                        it.copy(
                            pages =
                                it.pages.copy(
                                    downloads = it.pages.downloads.copy(showDescriptions = value)
                                )
                        )
                    }
                }
            }
        3 ->
            SettingsSection("运行日志", contentGap = 0.dp) {
                PreferenceSwitch("日志自动滚动", "新日志出现时自动跟随到末尾", options.logs.autoScroll) { value ->
                    apply {
                        it.copy(
                            pages = it.pages.copy(logs = it.pages.logs.copy(autoScroll = value))
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
                                it.pages.copy(logs = it.pages.logs.copy(fontSize = value.toInt()))
                        )
                    }
                }
            }
        else ->
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
