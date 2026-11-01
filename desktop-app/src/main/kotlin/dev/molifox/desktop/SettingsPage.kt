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
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.JavaRuntime
import dev.molifox.core.LauncherState
import dev.molifox.core.i18n.Strings
import dev.molifox.desktop.content.DownloadsPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import java.io.IOException

// 设置页七个分区：全局游戏设置 / Java 管理 / 通用 / 外观 / 下载 / 日志 / 关于。
// 标题按当前语言生成；顺序固定，APPEARANCE_SECTION 等索引常量以此为准。
private fun settingsIndex(strings: Strings): List<Pair<String, ImageVector>> =
    listOf(
        strings.t("settings.section.game") to Icons.Outlined.Tune,
        strings.t("settings.section.java") to Icons.Outlined.Memory,
        strings.t("settings.section.general") to Icons.Outlined.Settings,
        strings.t("settings.section.appearance") to Icons.Outlined.Palette,
        strings.t("settings.section.download") to Icons.Outlined.CloudDownload,
        strings.t("settings.section.logs") to Icons.Outlined.Article,
        strings.t("settings.section.about") to Icons.Outlined.Info,
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
    val strings = LocalStrings.current
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
    LaunchedEffect(section, refresh, query.isNotBlank()) {
        // 搜索会把 Java 卡片一起展示，所以搜索时也需要检测结果。
        if (section != 1 && query.isBlank()) return@LaunchedEffect
        detecting = true
        detectionError = null
        try {
            runtimes = withContext(Dispatchers.IO) { actions.controller.java.detect() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            detectionError = error.message ?: strings.t("settings.java.detectionFailed")
        } catch (error: IllegalStateException) {
            detectionError = error.message ?: strings.t("settings.java.detectionFailed")
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
    val cards =
        SettingsCardSet(
            game = gameCards,
            java = javaCards,
            general = generalCards,
            download = downloadCards,
            about = aboutCards,
            search = gameCards + javaCards + generalCards + downloadCards + aboutCards,
        )
    SettingsSearchHeader(query) { query = it }
    saveError?.let { message ->
        Text(
            strings.t("settings.unsavedPrefix") + message,
            Modifier.padding(top = 6.dp),
            color = MaterialTheme.colorScheme.error,
            fontSize = 12.sp,
        )
    }
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        SettingsNavigationPanel(
            section = section,
            appearanceSub = appearanceSub,
            query = query,
            onSelectSection = { section = it },
            onSelectSub = { appearanceSub = it },
        )
        SettingsContent(
            SettingsContentModel(
                state = state,
                actions = actions,
                cards = cards,
                target = if (query.isNotBlank()) SEARCH_RESULTS else section,
                query = query,
                appearanceSub = appearanceSub,
                resetDefaults = resetDefaults,
                onClearSearch = { query = "" },
            ),
        )
    }
}

/** 右上角搜索框；右侧留出与滚动列一致的 12dp，否则会比下面白卡的右边缘多探出一截。 */
@Composable
private fun SettingsSearchHeader(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val duration = if (LocalReducedMotion.current) 0 else Motion.FAST
    val strings = LocalStrings.current
    Row(Modifier.fillMaxWidth().padding(end = 12.dp), horizontalArrangement = Arrangement.End) {
        Row(
            Modifier.width(250.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CompactTextField(
                query,
                onQueryChange,
                Modifier.weight(1f),
                placeholder = strings.t("settings.search.placeholder"),
            )
            AnimatedVisibility(
                query.isNotBlank(),
                enter = fadeIn(tween(duration)),
                exit = fadeOut(tween(duration)),
            ) {
                SmallIconButton(Icons.Outlined.Close, strings.t("settings.search.clear"), { onQueryChange("") })
            }
        }
    }
}

/**
 * 左侧二级列表与下载页分类栏用同一套条目规格：项高 32、圆角 6、图标 17、内边距 10、
 * 选中 selectedRowBackground 实底 + onPrimaryContainer 文字，悬停 rowHoverHighlight、按下 pressScale。
 * 整栏套磨砂面板，上下留 8dp 内边距，条目因此整体缩在面板圆角以内；高度撑满，与右侧内容列同高。
 */
@Composable
private fun SettingsNavigationPanel(
    section: Int,
    appearanceSub: Int,
    query: String,
    onSelectSection: (Int) -> Unit,
    onSelectSub: (Int) -> Unit,
) {
    val duration = if (LocalReducedMotion.current) 0 else Motion.FAST
    val strings = LocalStrings.current
    FrostedPanel(
        Modifier.width(168.dp).fillMaxHeight(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        Column(
            // 内容超出面板高度时滚动，而不是画到面板圆角外面。
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            SettingsGroupTitle(strings.t("settings.groupTitle"), topPadding = 2.dp)
            settingsIndex(strings).forEachIndexed { index, (title, icon) ->
                SettingsNavItem(
                    title,
                    icon,
                    section == index && query.isBlank(),
                    onClick = { onSelectSection(index) },
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
                            appearanceSubSectionKeys.forEachIndexed { subIndex, subKey ->
                                SettingSubEntry(
                                    strings.t(subKey),
                                    appearanceSub == subIndex,
                                    onClick = { onSelectSub(subIndex) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 右侧内容区所需的分组卡片：搜索页把它们拼成一列，各分区只取自己那份。 */
private data class SettingsCardSet(
    val game: List<SettingCard>,
    val java: List<SettingCard>,
    val general: List<SettingCard>,
    val download: List<SettingCard>,
    val about: List<SettingCard>,
    val search: List<SettingCard>,
)

private data class SettingsContentModel(
    val state: LauncherState,
    val actions: UiActions,
    val cards: SettingsCardSet,
    val target: Int,
    val query: String,
    val appearanceSub: Int,
    val resetDefaults: () -> Unit,
    val onClearSearch: () -> Unit,
)

@Composable
private fun RowScope.SettingsContent(model: SettingsContentModel) {
    Crossfade(
        model.target,
        Modifier.weight(1f).fillMaxHeight(),
        animationSpec = tween(if (LocalReducedMotion.current) 0 else 150),
        label = "settingsSection",
    ) { index ->
        SettingsIndexedContent(index, model)
    }
}

@Composable
private fun SettingsIndexedContent(
    index: Int,
    model: SettingsContentModel,
) {
    when (index) {
        0 -> SettingsCardsPage(model.cards.game, model.resetDefaults)
        1 -> SettingsCardsPage(model.cards.java, model.resetDefaults)
        2 -> SettingsCardsPage(model.cards.general, model.resetDefaults)
        // 外观与日志由子页面提供，内部使用 weight / LazyColumn，必须放在无滚动容器中。
        3 ->
            Column(Modifier.fillMaxSize()) {
                AppearanceSection(
                    model.state,
                    model.actions,
                    model.resetDefaults,
                    subSection = model.appearanceSub,
                )
            }
        4 -> SettingsCardsPage(model.cards.download, model.resetDefaults)
        5 -> Column(Modifier.fillMaxSize()) { DownloadsPage(model.state, model.actions) }
        SEARCH_RESULTS -> SettingsSearchPage(model.cards.search, model.query, model.onClearSearch)
        else -> SettingsCardsPage(model.cards.about, model.resetDefaults)
    }
}

@Composable
private fun SettingsCardsPage(
    cards: List<SettingCard>,
    resetDefaults: () -> Unit,
) {
    ScrollColumn(Modifier.fillMaxSize()) {
        SettingCardFlow(cards, "")
        SettingsResetFooter(resetDefaults)
    }
}

@Composable
private fun SettingsSearchPage(
    cards: List<SettingCard>,
    query: String,
    onClearSearch: () -> Unit,
) {
    ScrollColumn(Modifier.fillMaxSize()) {
        SettingCardFlow(cards, query, onClearSearch = onClearSearch)
    }
}
