// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 集中页面选择与状态，避免每个设置分组自行维护导航。

package dev.molifox.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.onClick
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.JavaRuntime
import dev.molifox.core.LauncherState
import dev.molifox.desktop.content.DownloadsPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

// 设置页七个分区：全局游戏设置 / Java 管理 / 通用 / 外观 / 下载 / 日志 / 关于。
private val settingsIndex =
    listOf(
        "全局游戏设置" to Icons.Outlined.Tune,
        "Java 管理" to Icons.Outlined.Memory,
        "通用" to Icons.Outlined.Settings,
        "外观" to Icons.Outlined.Palette,
        "下载" to Icons.Outlined.CloudDownload,
        "日志" to Icons.Outlined.Article,
        "关于 MoliFox" to Icons.Outlined.Info,
    )

// 搜索结果为独立状态：目标 -1 表示正在显示跨分区搜索结果。
private const val SEARCH_RESULTS = -1

// 「外观」是唯一带子页的分区：子条目在左栏「外观」下面展开，索引与 settingsIndex / when 分支一致。
private const val APPEARANCE_SECTION = 3

@Composable
fun ColumnScope.SettingsPage(
    state: LauncherState,
    actions: UiActions,
) {
    val settings = state.data.settings
    var section by remember { mutableIntStateOf(0) }
    // 外观子页（主题与效果 / 字体与缩放 / 页面布局）：选择器在左栏，「外观」下面的子条目里。
    var appearanceSub by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var runtimes by remember { mutableStateOf<List<JavaRuntime>>(emptyList()) }
    var detecting by remember { mutableStateOf(false) }
    var detectionError by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var advancedValid by remember { mutableStateOf(true) }
    // 还原默认配置后 +1：高级参数编辑器据此丢弃内部文本，按新的已保存值重建。
    var advancedRevision by remember { mutableIntStateOf(0) }
    // 写盘失败（多半是后台任务进行中不允许改动目录 / 隔离）时在标题下如实提示，不静默丢失。
    var saveError by remember { mutableStateOf<String?>(null) }
    val maximum = maxOf(512, actions.controller.platform.totalMemoryMb - 512)
    val duration = if (LocalReducedMotion.current) 0 else Motion.FAST
    LaunchedEffect(section, refresh, query.isNotBlank()) {
        // 搜索会把 Java 卡片一起展示，所以搜索时也需要检测结果。
        if (section != 1 && query.isBlank()) return@LaunchedEffect
        detecting = true
        detectionError = null
        try {
            runtimes = withContext(Dispatchers.IO) { actions.controller.java.detect() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            detectionError = error.message ?: "检测失败"
        } finally {
            detecting = false
        }
    }
    val writer = remember(actions) { SettingsWriter(actions) { saveError = it } }
    // 还原默认配置：设置页负责的选项全部回到出厂值并立即写盘；高级参数编辑器随之重建。
    val resetDefaults: () -> Unit = {
        writer.commit { current -> defaultSettings(current, maximum) }
        advancedRevision++
    }
    val javaCards =
        javaSettingCards(
            actions,
            writer,
            settings,
            runtimes,
            detecting,
            detectionError,
            {
                actions.controller.java.invalidate()
                refresh++
            },
        )
    val gameCards =
        gameSettingCards(
            state,
            actions,
            writer,
            settings,
            maximum,
            advancedRevision,
            advancedValid,
            { advancedValid = it },
        )
    val generalCards = generalSettingCards(actions, writer, settings)
    val downloadCards = downloadSettingCards(actions, writer, settings)
    val aboutCards = aboutSettingCards(actions)
    val searchCards = gameCards + javaCards + generalCards + downloadCards + aboutCards
    // 搜索框位于右上角；右侧留出与滚动列一致的 12dp，
    // 否则会比下面白卡的右边缘多探出一截。
    Row(Modifier.fillMaxWidth().padding(end = 12.dp), horizontalArrangement = Arrangement.End) {
        Row(
            Modifier.width(250.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CompactTextField(
                query,
                { query = it },
                Modifier.weight(1f),
                placeholder = "搜索设置项",
            )
            AnimatedVisibility(
                query.isNotBlank(),
                enter = fadeIn(tween(duration)),
                exit = fadeOut(tween(duration)),
            ) {
                SmallIconButton(Icons.Outlined.Close, "清空搜索", { query = "" })
            }
        }
    }
    saveError?.let { message ->
        Text(
            "有设置未保存：" + message,
            Modifier.padding(top = 6.dp),
            color = MaterialTheme.colorScheme.error,
            fontSize = 12.sp,
        )
    }
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        // 左侧二级列表与下载页分类栏用同一套条目规格：项高 32、圆角 6、图标 17、内边距 10、
        // 选中 selectedRowBackground 实底 + onPrimaryContainer 文字，悬停 rowHoverHighlight、按下 pressScale。
        // 整栏套磨砂面板，上下留 8dp 内边距，条目因此整体缩在面板圆角以内；高度撑满，与右侧内容列同高。
        FrostedPanel(
            Modifier.width(168.dp).fillMaxHeight(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            Column(
                // 内容超出面板高度时滚动，而不是画到面板圆角外面。
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                SettingsGroupTitle("设置", topPadding = 2.dp)
                settingsIndex.forEachIndexed { index, (title, icon) ->
                    SettingsNavItem(
                        title,
                        icon,
                        section == index && query.isBlank(),
                        onClick = { section = index },
                    )
                    // 外观的子页在「外观」下面向下展开；切到别的分区时收起（子页选择保留）。
                    if (index == APPEARANCE_SECTION) {
                        AnimatedVisibility(
                            section == APPEARANCE_SECTION,
                            enter = fadeIn(tween(duration)) + expandVertically(tween(duration)),
                            exit = fadeOut(tween(duration)) + shrinkVertically(tween(duration)),
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(top = 2.dp),
                                verticalArrangement = Arrangement.spacedBy(1.dp),
                            ) {
                                appearanceSubSections.forEachIndexed { subIndex, subTitle ->
                                    SettingSubEntry(
                                        subTitle,
                                        appearanceSub == subIndex,
                                        onClick = { appearanceSub = subIndex },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        val target = if (query.isNotBlank()) SEARCH_RESULTS else section
        Crossfade(
            target,
            Modifier.weight(1f).fillMaxHeight(),
            animationSpec = tween(if (LocalReducedMotion.current) 0 else 150),
            label = "settingsSection",
        ) { index ->
            when (index) {
                0 ->
                    ScrollColumn(Modifier.fillMaxSize()) {
                        SettingCardFlow(gameCards, "")
                        SettingsResetFooter(resetDefaults)
                    }
                1 ->
                    ScrollColumn(Modifier.fillMaxSize()) {
                        SettingCardFlow(javaCards, "")
                        SettingsResetFooter(resetDefaults)
                    }
                2 ->
                    ScrollColumn(Modifier.fillMaxSize()) {
                        SettingCardFlow(generalCards, "")
                        SettingsResetFooter(resetDefaults)
                    }
                // 外观与日志由子页面提供，内部使用 weight / LazyColumn，必须放在无滚动容器中。
                3 ->
                    Column(Modifier.fillMaxSize()) {
                        AppearanceSection(
                            state,
                            actions,
                            resetDefaults,
                            subSection = appearanceSub,
                            onOpenFont = { appearanceSub = 1 },
                        )
                    }
                4 ->
                    ScrollColumn(Modifier.fillMaxSize()) {
                        SettingCardFlow(downloadCards, "")
                        SettingsResetFooter(resetDefaults)
                    }
                5 -> Column(Modifier.fillMaxSize()) { DownloadsPage(state, actions) }
                SEARCH_RESULTS ->
                    ScrollColumn(Modifier.fillMaxSize()) {
                        SettingCardFlow(searchCards, query, onClearSearch = { query = "" })
                    }
                else ->
                    ScrollColumn(Modifier.fillMaxSize()) {
                        SettingCardFlow(aboutCards, "")
                        SettingsResetFooter(resetDefaults)
                    }
            }
        }
    }
}
