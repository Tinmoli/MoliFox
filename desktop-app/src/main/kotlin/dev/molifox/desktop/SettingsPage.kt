// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责启动、下载、Java 和通用设置表单。

package dev.molifox.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing

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

/** 设置卡片里的一行或一组控件：标题与说明参与搜索匹配。 */
private class SettingBlock(
    val title: String,
    val detail: String,
    val render: @Composable () -> Unit,
)

/** 一张设置卡片：改动即保存，卡片本身不持有「未保存」状态，因此没有重置本区。 */
private class SettingCard(
    val title: String,
    val detail: String = "",
    val blocks: List<SettingBlock>,
)

/** 卡片侧写入口：把写盘失败的原因交回页面，在底部提示行如实显示。 */
private class SettingsWriter(val actions: UiActions, private val onFailure: (String?) -> Unit) {

    fun commit(change: (LauncherSettings) -> LauncherSettings) {
        onFailure(applySetting(actions, change))
    }
}

/** 行内控件：标题与说明在左，控件在右（行高 44 / 带说明 52）。 */
private fun settingRow(title: String, detail: String = "", control: @Composable () -> Unit) =
    SettingBlock(title, detail) { SettingRow(title, detail, control) }

/** 竖向控件：标题与说明在上，34dp 控件在下方占满整行。 */
private fun settingField(
    title: String,
    detail: String = "",
    content: @Composable ColumnScope.() -> Unit,
) =
    SettingBlock(title, detail) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.fillMaxWidth()) {
                Text(title, fontSize = 14.sp)
                if (detail.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        detail,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }
            content()
        }
    }

/** 只作为可搜索条目的控件块：不额外画标题（控件自带说明）。 */
private fun settingControl(title: String, content: @Composable () -> Unit) =
    SettingBlock(title, "") { content() }

/** 只有一行说明的条目：仍可被搜索命中。 */
private fun settingNote(text: String) = SettingBlock(text, "") { MutedText(text) }

/** 下拉选项：标准值之后补上旧配置里的当前值，避免旧值显示成空白或被悄悄改掉。 */
private fun legacyOption(
    value: Int,
    standard: List<Int>,
    label: (Int) -> String,
): List<Pair<String, String>> =
    standard.map { it.toString() to label(it) } +
        (if (value in standard) emptyList() else listOf(value.toString() to label(value)))

/**
 * 还原默认配置的取值：设置页负责的选项全部回到出厂值。 游戏目录、游戏目录语义与正版登录身份不属于本页，保持原样；
 * 已导入的字体、背景图片与左上角图标属于文件资产，只重置它们的选项，不删除文件本身。
 */
private fun defaultSettings(current: LauncherSettings, maximumMemoryMb: Int): LauncherSettings {
    val defaults = LauncherSettings()
    val memory = minOf(defaults.memoryMb, maximumMemoryMb)
    return defaults.copy(
        gameRoot = current.gameRoot,
        microsoftClientId = current.microsoftClientId,
        customFonts = current.customFonts,
        backgroundPath = current.backgroundPath,
        backgroundRevision = current.backgroundRevision,
        brandIconPath = current.brandIconPath,
        brandIconRevision = current.brandIconRevision,
        memoryMb = memory,
        initialMemoryMb = minOf(defaults.initialMemoryMb, memory),
    )
}

@Composable
fun ColumnScope.SettingsPage(state: LauncherState, actions: UiActions, navigate: (Page) -> Unit) {
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
    val duration = if (LocalReducedMotion.current) 0 else Motion.fast
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
            { actions.controller.java.invalidate(); refresh++ },
            navigate,
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

/** 卡片流：按搜索词过滤行（标题 / 说明 / 卡片标题），整卡无命中时隐藏；全部无命中显示空态。 只做本地过滤，不改任何设置值。 */
@Composable
private fun SettingCardFlow(
    cards: List<SettingCard>,
    query: String,
    onClearSearch: () -> Unit = {},
) {
    val matched = cards.mapNotNull { card ->
        val cardHit = card.title.contains(query, true) || card.detail.contains(query, true)
        val blocks =
            if (query.isBlank() || cardHit) card.blocks
            else
                card.blocks.filter {
                    it.title.contains(query, true) || it.detail.contains(query, true)
                }
        if (blocks.isEmpty()) null else card to blocks
    }
    if (query.isNotBlank() && matched.isNotEmpty()) {
        MutedText(
            "搜索「" +
                query +
                "」：命中 " +
                matched.sumOf { it.second.size } +
                " 个设置项。外观与日志分区由子页面提供，不参与搜索。"
        )
    }
    if (matched.isEmpty()) {
        EmptyState("没有匹配的设置项", "换个关键词，或清空搜索框。", Icons.Outlined.SearchOff) {
            SecondaryButton(onClearSearch) { Text("清空搜索") }
        }
        return
    }
    matched.forEach { (card, blocks) ->
        SettingsSection(
            card.title,
            card.detail,
            // 行间使用 1dp 细分隔线：首行之前、末行之后都不画，卡片保留内边距与 44/52 行高。
            contentGap = 0.dp,
        ) {
            blocks.forEachIndexed { index, block ->
                if (index > 0) SettingDivider()
                block.render()
            }
        }
    }
}

/** 多行输入框：用于附加 JVM 参数；输入停手 300ms 后写入设置，非法参数就地提示原因。 */
@Composable
private fun SettingTextArea(
    value: String,
    onCommit: (String) -> String?,
    label: String,
    hint: String? = null,
) {
    val live = rememberLiveTextDraft(value, onCommit)
    val textFocus = remember { FocusRequester() }
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val border by
        animateColorAsState(
            controlBorderColor(focused),
            if (LocalReducedMotion.current) androidx.compose.animation.core.snap()
            else tween(Motion.fast),
            label = "areaBorder",
        )
    val fill by
        animateColorAsState(
            controlBackground(focused),
            if (LocalReducedMotion.current) androidx.compose.animation.core.snap()
            else tween(Motion.fast),
            label = "areaFill",
        )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label.isNotBlank()) Text(label, color = colors.onSurfaceVariant, fontSize = 11.sp)
        Box(
            Modifier.fillMaxWidth()
                .heightIn(min = 42.dp)
                .clip(RoundedCornerShape(Metrics.innerRadius))
                .background(fill)
                .border(1.dp, border, RoundedCornerShape(Metrics.innerRadius))
                .clickable(interactionSource = interaction, indication = null) {
                    textFocus.requestFocus()
                }
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            BasicTextField(
                live.text,
                live.onChange,
                Modifier.fillMaxWidth()
                    .heightIn(min = 26.dp, max = 150.dp)
                    .focusRequester(textFocus)
                    .semantics { contentDescription = label },
                textStyle =
                    MaterialTheme.typography.bodyMedium.copy(
                        color = colors.onSurface,
                        fontFamily = LocalLauncherFont.current,
                    ),
                cursorBrush = SolidColor(colors.primary),
                interactionSource = interaction,
                minLines = 1,
                maxLines = 6,
            )
        }
        val message = live.error ?: hint
        if (message != null)
            Text(
                message,
                color = if (live.error != null) colors.error else colors.onSurfaceVariant,
                fontSize = 11.sp,
            )
    }
}

/** 全局游戏设置：内存、游戏窗口、高级参数、游戏目录与版本隔离。 */
@Composable
private fun gameSettingCards(
    state: LauncherState,
    actions: UiActions,
    writer: SettingsWriter,
    settings: LauncherSettings,
    maximum: Int,
    advancedRevision: Int,
    advancedValid: Boolean,
    onAdvancedValid: (Boolean) -> Unit,
): List<SettingCard> {
    val cards = mutableListOf<SettingCard>()
    // 高级参数草稿：编辑器每敲一个字符就回调一次，停手 300ms 再写设置；
    // 还原默认（advancedRevision 变化）时按新的已保存值重建，编辑器内部文本一并重建。
    var advancedDraft by remember(advancedRevision) { mutableStateOf(settings.advancedLaunch) }
    val pendingAdvanced by rememberUpdatedState(advancedDraft)
    val savedAdvanced by rememberUpdatedState(settings.advancedLaunch)
    LaunchedEffect(advancedDraft) {
        val pending = advancedDraft
        if (pending == settings.advancedLaunch) return@LaunchedEffect
        delay(LIVE_WRITE_DELAY)
        writer.commit { it.copy(advancedLaunch = pending) }
    }
    // 「启动后最小化」也会写同一个字段：已保存值被其它入口改动时把草稿同步过来，
    // 否则旧草稿会在下一次编辑时把它覆盖回去。
    LaunchedEffect(settings.advancedLaunch) {
        if (advancedDraft != settings.advancedLaunch) advancedDraft = settings.advancedLaunch
    }
    // 离开设置页时补写还没到 300ms 的编辑，最后敲的几个字符不会丢。
    DisposableEffect(Unit) {
        onDispose {
            if (pendingAdvanced != savedAdvanced)
                runCatching { applySetting(actions) { it.copy(advancedLaunch = pendingAdvanced) } }
        }
    }
    cards.add(
        SettingCard(
            "内存分配",
            "每个实例都可以覆盖这里的设置。",
            listOf(
                settingRow("自动分配内存", "启动时根据剩余物理内存重新计算，保留系统与游戏本地内存空间。") {
                    CompactSwitch(
                        settings.autoMemory,
                        { enabled -> writer.commit { it.copy(autoMemory = enabled) } },
                        Modifier.semantics { contentDescription = "自动分配内存开关" },
                    )
                },
                settingField(
                    "最大内存",
                    "物理内存总量约 " +
                        actions.controller.platform.totalMemoryMb / 1024 +
                        " GB；拖动滑块按 256 MB 步进",
                ) {
                    if (settings.autoMemory) {
                        val estimate =
                            automaticMemoryMb(
                                actions.controller.platform.totalMemoryMb,
                                actions.controller.platform.availableMemoryMb,
                            )
                        MutedText("当前预计分配 $estimate MB；实际值在启动游戏时计算，初始内存会限制在最大值以内。")
                    }
                    // 拖动只改本地值，停手 300ms 才写设置；初始内存跟着最大值一起收窄。
                    val memory =
                        rememberSliderDraft(settings.memoryMb.toFloat()) { dragged ->
                            val value = steppedMemory(dragged, maximum)
                            writer.commit {
                                it.copy(
                                    memoryMb = value,
                                    initialMemoryMb = minOf(it.initialMemoryMb, value),
                                )
                            }
                        }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CompactSlider(
                            memory.floatValue,
                            { memory.floatValue = steppedMemory(it, maximum).toFloat() },
                            Modifier.weight(1f).semantics { contentDescription = "全局内存滑块" },
                            valueRange = 512f..maximum.toFloat(),
                            enabled = !settings.autoMemory,
                        )
                        Tag("%.1f GB".format(memory.floatValue / 1024f))
                    }
                    MutedText("原版通常从 2–4 GB 开始；大型整合包按需要调整。初始内存不能超过最大内存。")
                },
            ),
        )
    )
    cards.add(
        SettingCard(
            "游戏窗口",
            "「外观 → 默认游戏窗口」选择自定义分辨率时生效。",
            listOf(
                settingRow("全屏启动", "关闭时使用下方的窗口分辨率") {
                    CompactSwitch(
                        settings.fullscreen,
                        { value -> writer.commit { it.copy(fullscreen = value) } },
                    )
                },
                settingField(
                    "窗口分辨率",
                    if (settings.fullscreen) "全屏启动时忽略这里的宽高。" else "范围 640–7680 × 480–4320 像素。",
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        LiveNumberField(
                            settings.width,
                            "窗口宽度（px）",
                            640..7680,
                            Modifier.weight(1f),
                            enabled = !settings.fullscreen,
                        ) { value ->
                            applySetting(actions) { it.copy(width = value) }
                        }
                        LiveNumberField(
                            settings.height,
                            "窗口高度（px）",
                            480..4320,
                            Modifier.weight(1f),
                            enabled = !settings.fullscreen,
                        ) { value ->
                            applySetting(actions) { it.copy(height = value) }
                        }
                    }
                },
                settingRow("启动后最小化", "游戏退出后恢复启动器窗口") {
                    CompactSwitch(
                        settings.advancedLaunch.launcherVisibility == "minimize",
                        { value ->
                            writer.commit {
                                it.copy(
                                    advancedLaunch =
                                        it.advancedLaunch.copy(
                                            launcherVisibility = if (value) "minimize" else "keep"
                                        )
                                )
                            }
                        },
                    )
                },
                settingRow(
                    "游戏进程优先级",
                    "仅 Windows 生效；优先级越高越不容易被系统降速，超高可能影响其他程序响应。",
                ) {
                    Box(Modifier.width(120.dp)) {
                        SimpleDropdown(
                            settings.processPriority.name,
                            ProcessPriority.entries.map { it.name to it.title },
                            { value ->
                                ProcessPriority.entries.find { it.name == value }?.let { priority ->
                                    writer.commit { it.copy(processPriority = priority) }
                                }
                            },
                            enabled = actions.controller.platform.osName == "windows",
                        )
                    }
                },
            ),
        )
    )
    // 显隐读已保存设置：勾选「显示高级参数」后立即出现（该开关本身也是即时保存）。
    if (settings.pages.showAdvancedLaunchOptions) {
        cards.add(
            SettingCard(
                "高级参数",
                "通常留空即可，内存请使用上方专用设置。",
                listOf(
                    settingControl("附加 JVM 参数") {
                        SettingTextArea(
                            settings.extraJvmArgs,
                            { value -> applySetting(actions) { it.copy(extraJvmArgs = value) } },
                            "附加 JVM 参数",
                            "每行一个参数，留空使用启动器默认值。",
                        )
                    },
                    settingControl("启动参数编辑器") {
                        key(advancedRevision) {
                            AdvancedLaunchEditor(
                                advancedDraft,
                                actions.controller.platform.osName,
                                { value -> advancedDraft = value },
                                onAdvancedValid,
                            )
                        }
                        if (!advancedValid)
                            Text(
                                "启动参数里有尚未保存的无效输入，修正后会自动保存。",
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 11.sp,
                            )
                    },
                    settingControl("启动参数预览") {
                        SecondaryButton(
                            {
                                state.data.selectedInstanceId?.let {
                                    actions.previewLaunch(it)
                                }
                            },
                            enabled =
                                state.data.selectedInstanceId != null && !actions.controller.busy,
                        ) {
                            Text("查看已保存启动参数")
                        }
                    },
                ),
            )
        )
    }
    cards.add(
        SettingCard(
            "游戏目录",
            "直接使用已有文件夹，也可以选择空目录作为安装目标。",
            listOf(settingControl("当前游戏目录") { GameDirectoryControls(state, actions) }),
        )
    )
    cards.add(
        SettingCard(
            "版本隔离",
            "",
            listOf(
                settingRow("启用版本隔离", "默认值：开启时使用游戏目录下 versions/实例名；关闭时共用游戏根目录。") {
                    CompactSwitch(
                        settings.isolateVersions,
                        { value -> writer.commit { it.copy(isolateVersions = value) } },
                    )
                },
                settingNote("这里是全局默认值；实例设置里的「版本隔离」可选继承全局、强制开启或强制共用。切换后不删除或合并原目录里的存档、模组与配置。"),
            ),
        )
    )
    return cards
}

/** 内存按 256 MB 步进并限制在有效范围内：滑块与数字输入共用。 */
internal fun steppedMemory(value: Float, maximum: Int): Int =
    (value.toInt() / 256 * 256).coerceIn(512, maximum)

/** Java 与运行环境：当前使用项、扁平候选列表、添加入口与安装控件。 */
@Composable
private fun javaSettingCards(
    actions: UiActions,
    writer: SettingsWriter,
    settings: LauncherSettings,
    runtimes: List<JavaRuntime>,
    detecting: Boolean,
    detectionError: String?,
    onRefresh: () -> Unit,
    navigate: (Page) -> Unit,
): List<SettingCard> {
    val state by actions.controller.state.collectAsState()
    var installVersion by remember { mutableStateOf("auto") }
    var installType by remember { mutableStateOf("jre") }
    var installJob by remember { mutableStateOf<String?>(null) }
    val job = installJob?.let { id -> state.jobs.find { it.id == id } }
    val active = job?.status in setOf(JobStatus.Queued, JobStatus.Running)
    return listOf(
        SettingCard(
            "Java 与运行环境",
            "",
            listOf(
                settingField("游戏 Java") {
                    JavaPicker(
                        actions,
                        settings.javaPath,
                        runtimes,
                        { value -> writer.commit { it.copy(javaPath = value) } },
                        knownCustomPaths = settings.customJavaPaths,
                        onAddCustom = { path ->
                            writer.commit { current ->
                                current.copy(
                                    customJavaPaths = (current.customJavaPaths + path).distinct()
                                )
                            }
                        },
                        onRefresh = onRefresh,
                        detecting = detecting,
                        detectionError = detectionError,
                    )
                },
                settingField(
                    "Java 下载源",
                    "自动会先测速，再从 Azul 与 Adoptium Temurin 官方源中选择。",
                ) {
                    SimpleDropdown(
                        settings.effectiveJavaDownloadSource,
                        listOf(
                            "auto" to "自动（测速选最快官方源）",
                            "official" to "官方源",
                        ),
                        { value -> writer.commit { it.copy(javaDownloadSource = value) } },
                    )
                },
                settingField("安装 Java") {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SimpleDropdown(
                            installVersion,
                            listOf(
                                "auto" to "自动（按游戏版本）",
                                "8" to "Java 8",
                                "17" to "Java 17",
                                "21" to "Java 21",
                                "25" to "Java 25",
                            ),
                            { installVersion = it },
                            Modifier.weight(1f),
                            enabled = !active,
                        )
                        SimpleDropdown(
                            installType,
                            listOf("jre" to "JRE", "jdk" to "JDK"),
                            { installType = it },
                            Modifier.width(110.dp),
                            enabled = !active,
                        )
                        PrimaryButton("下载并安装", enabled = !active) {
                            val type = if (installType == "jdk") "jdk" else "jre"
                            installJob =
                                if (installVersion == "auto")
                                    actions.controller.installAutoJava(type)
                                else actions.controller.installJava(installVersion.toInt(), type)
                            actions.taskQueued()
                        }
                    }
                    if (job != null) {
                        LinearProgressIndicator(
                            progress = { job.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                when (job.status) {
                                    JobStatus.Completed -> "安装完成"
                                    JobStatus.Failed, JobStatus.Cancelled ->
                                        "安装未完成：" + (job.error ?: job.detail)
                                    else -> "${(job.progress * 100).toInt()}% · ${job.detail}"
                                },
                                Modifier.weight(1f),
                                fontSize = 11.sp,
                                color =
                                    if (job.status in setOf(JobStatus.Failed, JobStatus.Cancelled))
                                        MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (active)
                                SecondaryButton(
                                    { actions.controller.jobs.cancel(job.id) },
                                    contentPadding =
                                        PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                ) {
                                    Text("取消", fontSize = 12.sp)
                                }
                        }
                    }
                },
            ),
        ),
    )
}

/** 通用：更新检查与语言。 */
@Composable
private fun generalSettingCards(
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

/** 下载：下载源、并发数、缓存文件夹与代理。 */
@Composable
private fun downloadSettingCards(
    actions: UiActions,
    writer: SettingsWriter,
    settings: LauncherSettings,
): List<SettingCard> {
    val cards = mutableListOf<SettingCard>()
    cards.add(
        SettingCard(
            "下载设置",
            "镜像用于 Minecraft 元数据、本体、资源及依赖。",
            listOf(
                settingField("版本列表源", "只影响版本清单（manifest）的获取地址。") {
                    SimpleDropdown(
                        settings.effectiveVersionListSource,
                        listOf(
                            "auto" to "自动",
                            "official" to "官方",
                            "bmclapi" to "国内镜像（BMCLAPI）",
                        ),
                        { value -> writer.commit { it.copy(versionListSource = value) } },
                    )
                },
                settingField("文件下载源", "影响本体、依赖库、资源文件与 Java。") {
                    SimpleDropdown(
                        settings.effectiveDownloadSource,
                        listOf(
                            "auto" to "自动",
                            "official" to "官方",
                            "bmclapi" to "国内镜像（BMCLAPI）",
                        ),
                        { value -> writer.commit { it.copy(downloadSource = value) } },
                    )
                },
                // 下载并发：紧跟两个源设置，改动对下一个任务生效。
                settingRow("并发下载数", "默认 8；过高会更慢") {
                    SimpleDropdown(
                        settings.maxConcurrent.toString(),
                        legacyOption(settings.maxConcurrent, listOf(4, 8, 16)) { "$it 个文件" },
                        { value -> writer.commit { it.copy(maxConcurrent = value.toInt()) } },
                        Modifier.width(132.dp),
                    )
                },
                settingRow("镜像并发", "镜像站的同时请求数，默认 4") {
                    SimpleDropdown(
                        settings.mirrorConcurrency.toString(),
                        listOf(2, 4, 8).map { it.toString() to "$it 个文件" },
                        { value -> writer.commit { it.copy(mirrorConcurrency = value.toInt()) } },
                        Modifier.width(132.dp),
                    )
                },
                settingRow("大文件分段", "单个大文件的分段数，默认 4") {
                    SimpleDropdown(
                        settings.downloadSegments.toString(),
                        listOf("1" to "1（不分段）", "4" to "4（默认）", "8" to "8 段"),
                        { value -> writer.commit { it.copy(downloadSegments = value.toInt()) } },
                        Modifier.width(132.dp),
                    )
                },
                settingField("附加内容下载源", "选择 Modrinth 或 CurseForge。") {
                    SimpleDropdown(
                        settings.contentDownloadSource,
                        listOf("modrinth" to "Modrinth", "curseforge" to "CurseForge"),
                        { value -> writer.commit { it.copy(contentDownloadSource = value) } },
                    )
                },
                settingField("CurseForge 服务", "公共镜像或自建代理地址；留空使用默认服务。") {
                    LiveTextField(
                        settings.curseForgeBaseUrl,
                        { value ->
                            applySetting(actions) { it.copy(curseForgeBaseUrl = value.trim()) }
                        },
                        placeholder = DEFAULT_CURSE_FORGE_BASE_URL,
                    )
                },
                settingRow("失败重试次数", "包含首次请求；只对可恢复的网络错误重试。") {
                    SimpleDropdown(
                        settings.downloadAttempts.toString(),
                        listOf(1, 2, 3, 5, 8).map { it.toString() to "$it 次" },
                        { value -> writer.commit { it.copy(downloadAttempts = value.toInt()) } },
                        Modifier.width(132.dp),
                    )
                },
                settingRow("网络超时", "新请求的读取超时；代理和分段设置无需重启。") {
                    SimpleDropdown(
                        settings.networkTimeoutSeconds.toString(),
                        listOf(15, 30, 45, 60, 120, 180).map { it.toString() to "$it 秒" },
                        { value ->
                            writer.commit { it.copy(networkTimeoutSeconds = value.toInt()) }
                        },
                        Modifier.width(132.dp),
                    )
                },
                settingNote("加载器资源使用各自官方服务。可同时添加下载；取消任务会清理本次下载的文件与分段缓存。"),
            ),
        )
    )
    cards.add(
        SettingCard(
            "缓存文件夹",
            "版本列表与安装包的缓存位置，留空使用 MoliFox/cache。",
            listOf(
                settingField("缓存文件夹", "绝对路径；目录不存在时会自动创建。") {
                    LiveTextField(
                        settings.downloadCachePath,
                        { value -> applySetting(actions) { it.copy(downloadCachePath = value) } },
                        placeholder = "留空使用默认缓存目录",
                    ) {
                        SmallIconButton(
                            Icons.Outlined.FolderOpen,
                            "选择缓存文件夹",
                            {
                                actions.run {
                                    val chosen =
                                        actions.choose("选择下载缓存文件夹", directory = true).firstOrNull()
                                    if (chosen != null)
                                        withContext(Dispatchers.Swing) {
                                            applySetting(actions) {
                                                it.copy(downloadCachePath = chosen.toString())
                                            }
                                        }
                                }
                            },
                        )
                    }
                }
            ),
        )
    )
    cards.add(
        SettingCard(
            "代理",
            "改动立即作用于启动器下载、资源搜索和登录。",
            listOf(
                settingRow("连接方式") {
                    SimpleDropdown(
                        settings.effectiveProxyMode(),
                        listOf(
                            "system" to "跟随系统",
                            "direct" to "直接连接",
                            "manual" to "手动代理",
                        ),
                        { value ->
                            writer.commit {
                                if (value != "manual") it.copy(proxyMode = value)
                                else
                                    it.copy(
                                        proxyMode = "manual",
                                        // 手动代理必须有可用地址：未填写时先给一个可编辑的本地值，
                                        // 否则这次切换会被校验拒绝、下拉会跳回旧值。
                                        proxyHost = it.proxyHost.ifBlank { "127.0.0.1" },
                                        proxyPort =
                                            it.proxyPort.takeIf { port -> port in 1..65535 }
                                                ?: 1080,
                                    )
                            }
                        },
                        Modifier.width(150.dp),
                    )
                },
                settingField("代理类型", "仅手动代理时可用。") {
                    SimpleDropdown(
                        settings.proxyType,
                        listOf("http" to "HTTP", "socks" to "SOCKS"),
                        { value -> writer.commit { it.copy(proxyType = value) } },
                        enabled = settings.effectiveProxyMode() == "manual",
                    )
                },
                settingField("代理地址", "主机与端口；仅手动代理时生效。") {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        LiveTextField(
                            settings.proxyHost,
                            { value -> applySetting(actions) { it.copy(proxyHost = value) } },
                            Modifier.weight(1f),
                            placeholder = "127.0.0.1",
                            enabled = settings.effectiveProxyMode() == "manual",
                        )
                        LiveNumberField(
                            settings.proxyPort,
                            "",
                            0..65535,
                            Modifier.width(120.dp),
                            placeholder = "端口",
                            enabled = settings.effectiveProxyMode() == "manual",
                        ) { value ->
                            applySetting(actions) { it.copy(proxyPort = value) }
                        }
                    }
                },
            ),
        )
    )
    return cards
}

/** 关于页提供项目入口、运行信息与随程序分发的许可文本。 */
@Composable
private fun aboutSettingCards(actions: UiActions): List<SettingCard> =
    listOf(
        SettingCard(
            "关于 MoliFox",
            blocks = listOf(settingControl("MoliFox") { LauncherAboutPanel(actions) }),
        )
    )

/**
 * 左侧设置条目：与下载页分类条目 ([DownloadKindItem] 同规格) 完全同一套选中态—— 项高 32、圆角 6、图标 17、内边距 10、选中
 * `selectedRowBackground()` 实底 + `onPrimaryContainer` 文字 + SemiBold，悬停用 `rowHoverHighlight`、按下 `pressScale`。
 * 没有矩形描边，也没有向外扩张的焦点环：键盘焦点画在条目内侧 2dp 的 1dp 细描边里，因此不会越过磨砂面板的圆角。
 */
@Composable
private fun SettingsNavItem(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Metrics.cardRadius)
    val contentColor by
        animateColorAsState(
            if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
            tween(150),
            label = "settings-nav-color",
        )
    val background by
        animateColorAsState(
            if (selected) selectedRowBackground() else Color.Transparent,
            tween(150),
            label = "settings-nav-bg",
        )
    Row(
        Modifier.fillMaxWidth()
            .height(Metrics.navItemHeight)
            .clip(shape)
            .background(background)
            .rowHoverHighlight(shape, enabled = !selected, interactionSource = interaction)
            .insetFocusRing(
                focused,
                Metrics.cardRadius,
                if (selected) colors.onPrimaryContainer else colors.primary,
            )
            .pressScale()
            .selectable(
                selected,
                interaction,
                LocalIndication.current,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(17.dp), contentColor)
        Spacer(Modifier.width(9.dp))
        Text(
            title,
            Modifier.weight(1f),
            color = contentColor,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 外观子页条目：缩进在「外观」下面，高 28、圆角 6、文字 12；选中与父条目同一套实底与文字色。 */
@Composable
private fun SettingSubEntry(title: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Metrics.cardRadius)
    Row(
        Modifier.fillMaxWidth()
            .height(28.dp)
            .clip(shape)
            .background(if (selected) selectedRowBackground() else Color.Transparent)
            .rowHoverHighlight(shape, enabled = !selected, interactionSource = interaction)
            .insetFocusRing(
                focused,
                Metrics.cardRadius,
                if (selected) colors.onPrimaryContainer else colors.primary,
            )
            .selectable(
                selected,
                interaction,
                LocalIndication.current,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(start = 26.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 键盘焦点提示：在条目内侧 2dp 处描 1dp 主题色圆角边（不向外扩张、也不贴边），
 * 因此始终被条目的圆角包住，不会像 [focusRing] 那样探出父面板的圆角；无障碍焦点仍然可见。
 */
private fun Modifier.insetFocusRing(focused: Boolean, radius: Dp, color: Color): Modifier =
    if (!focused) this
    else
        drawWithContent {
            drawContent()
            val stroke = 1.dp.toPx()
            val inset = 2.dp.toPx()
            drawRoundRect(
                color = color.copy(alpha = .6f),
                topLeft = Offset(inset, inset),
                size =
                    Size(
                        (size.width - inset * 2).coerceAtLeast(0f),
                        (size.height - inset * 2).coerceAtLeast(0f),
                    ),
                cornerRadius = CornerRadius((radius.toPx() - inset).coerceAtLeast(0f)),
                style = Stroke(width = stroke),
            )
        }
