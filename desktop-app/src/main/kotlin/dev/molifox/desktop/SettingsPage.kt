package dev.molifox.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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

/** 设置卡片里的一行或一组控件：标题与说明参与搜索匹配。 */
private class SettingBlock(
    val title: String,
    val detail: String,
    val render: @Composable () -> Unit,
)

/** 一张设置卡片：标题行右侧在草稿与已保存值不同时出现「重置本区」。 */
private class SettingCard(
    val title: String,
    val detail: String = "",
    val blocks: List<SettingBlock>,
    val dirty: Boolean = false,
    val onReset: (() -> Unit)? = null,
)

/** 数字类输入框的字符串草稿：切分区不重建，保存时才写回设置。 */
private class NumberDraft(
    val width: String,
    val onWidth: (String) -> Unit,
    val height: String,
    val onHeight: (String) -> Unit,
    val memory: String,
    val onMemory: (String) -> Unit,
    val initial: String,
    val onInitial: (String) -> Unit,
    val proxyPort: String,
    val onProxyPort: (String) -> Unit,
)

/** 设置页顶层草稿：draft 在页面层持有，切分区只换内容，未保存输入不会丢失。 */
private class SettingsEditor(
    val draft: LauncherSettings,
    val saved: LauncherSettings,
    val numbers: NumberDraft,
    val update: ((LauncherSettings) -> LauncherSettings) -> Unit,
)

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

@Composable
fun ColumnScope.SettingsPage(state: LauncherState, actions: UiActions, navigate: (Page) -> Unit) {
    val settings = state.data.settings
    // draft 不再以 settings 为键：外观分区的即时保存不会重置这里未保存的输入（C-14）。
    var draft by remember { mutableStateOf(settings) }
    // 与草稿最近一次对齐过的设置快照，用来判断「用户是否还有未保存输入」。
    var synced by remember { mutableStateOf(settings) }
    var width by remember { mutableStateOf(settings.width.toString()) }
    var height by remember { mutableStateOf(settings.height.toString()) }
    var memory by remember { mutableStateOf(settings.memoryMb.toString()) }
    var initial by remember { mutableStateOf(settings.initialMemoryMb.toString()) }
    var proxyPort by remember { mutableStateOf(settings.proxyPort.toString()) }
    var section by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var runtimes by remember { mutableStateOf<List<JavaRuntime>>(emptyList()) }
    var detecting by remember { mutableStateOf(false) }
    var detectionError by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var advancedValid by remember { mutableStateOf(true) }
    var advancedRevision by remember { mutableIntStateOf(0) }
    val maximum = maxOf(512, actions.controller.platform.totalMemoryMb - 512)
    val valid =
        advancedValid &&
            (width.toIntOrNull() ?: 0) in 640..7680 &&
            (height.toIntOrNull() ?: 0) in 480..4320 &&
            (memory.toIntOrNull() ?: 0) in 512..maximum &&
            (initial.toIntOrNull() ?: 0) in 128..(memory.toIntOrNull() ?: 0)
    // 有未保存修改：草稿或数字文本与已保存值不一致。保存按钮只在「有改动且合法」时可点。
    val dirty =
        draft != settings ||
            width != settings.width.toString() ||
            height != settings.height.toString() ||
            memory != settings.memoryMb.toString() ||
            initial != settings.initialMemoryMb.toString() ||
            proxyPort != settings.proxyPort.toString()
    val duration = if (LocalReducedMotion.current) 0 else Motion.fast
    val restore: () -> Unit = {
        draft = settings
        synced = settings
        width = settings.width.toString()
        height = settings.height.toString()
        memory = settings.memoryMb.toString()
        initial = settings.initialMemoryMb.toString()
        proxyPort = settings.proxyPort.toString()
        advancedRevision++
    }
    // 外观等分区即时保存时，以最近同步的快照合并：保留未保存输入，接收未编辑字段的新值。
    LaunchedEffect(settings) {
        val untouched =
            draft == synced &&
                width == synced.width.toString() &&
                height == synced.height.toString() &&
                memory == synced.memoryMb.toString() &&
                initial == synced.initialMemoryMb.toString() &&
                proxyPort == synced.proxyPort.toString()
        draft = mergeSettingsDraft(synced, draft, settings)
        if (width == synced.width.toString()) width = settings.width.toString()
        if (height == synced.height.toString()) height = settings.height.toString()
        if (memory == synced.memoryMb.toString()) memory = settings.memoryMb.toString()
        if (initial == synced.initialMemoryMb.toString())
            initial = settings.initialMemoryMb.toString()
        if (proxyPort == synced.proxyPort.toString()) proxyPort = settings.proxyPort.toString()
        if (untouched) {
            advancedRevision++
        }
        synced = settings
    }
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
    val numbers =
        NumberDraft(
            width = width,
            onWidth = { width = it },
            height = height,
            onHeight = { height = it },
            memory = memory,
            onMemory = { memory = it },
            initial = initial,
            onInitial = { initial = it },
            proxyPort = proxyPort,
            onProxyPort = { proxyPort = it },
        )
    val editor =
        SettingsEditor(
            draft = draft,
            saved = settings,
            numbers = numbers,
            update = { change -> draft = change(draft) },
        )
    val javaCards =
        javaSettingCards(
            actions,
            editor,
            runtimes,
            detecting,
            detectionError,
            { refresh++ },
            navigate,
        )
    val gameCards =
        gameSettingCards(state, actions, editor, maximum, { advancedValid = it }, advancedRevision)
    val generalCards = generalSettingCards(actions, editor)
    val downloadCards = downloadSettingCards(actions, editor)
    val aboutCards = aboutSettingCards()
    val searchCards = gameCards + javaCards + generalCards + downloadCards + aboutCards
    SectionTitle("设置", "全局设置作为各个版本的默认值，改动后点右下角「保存设置」生效。") {
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
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        // 左侧二级列表：项高 34、圆角 6、图标 16、文字 13，选中主题色容器底 + 主题色文字。
        Column(
            Modifier.width(168.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            SettingsGroupTitle("设置", topPadding = 2.dp)
            settingsIndex.forEachIndexed { index, (title, icon) ->
                NavigationEntry(
                    title,
                    icon,
                    section == index && query.isBlank(),
                    onClick = { section = index },
                )
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
                0 -> ScrollColumn(Modifier.fillMaxSize()) { SettingCardFlow(gameCards, "") }
                1 -> ScrollColumn(Modifier.fillMaxSize()) { SettingCardFlow(javaCards, "") }
                2 -> ScrollColumn(Modifier.fillMaxSize()) { SettingCardFlow(generalCards, "") }
                // 外观与日志由子页面提供，内部使用 weight / LazyColumn，必须放在无滚动容器中。
                3 -> Column(Modifier.fillMaxSize()) { AppearanceSection(state, actions) }
                4 -> ScrollColumn(Modifier.fillMaxSize()) { SettingCardFlow(downloadCards, "") }
                5 -> Column(Modifier.fillMaxSize()) { DownloadsPage(state, actions) }
                SEARCH_RESULTS ->
                    ScrollColumn(Modifier.fillMaxSize()) {
                        SettingCardFlow(searchCards, query, onClearSearch = { query = "" })
                    }
                else -> ScrollColumn(Modifier.fillMaxSize()) { SettingCardFlow(aboutCards, "") }
            }
        }
    }
    HorizontalDivider(color = hairline())
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!valid)
            Text(
                "请检查窗口尺寸和内存数值。",
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
            )
        Spacer(Modifier.weight(1f))
        AnimatedVisibility(
            dirty,
            enter = fadeIn(tween(duration)) + expandHorizontally(tween(duration)),
            exit = fadeOut(tween(duration)) + shrinkHorizontally(tween(duration)),
        ) {
            Text("有未保存修改", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
        }
        SecondaryButton(onClick = restore, enabled = dirty) { Text("还原修改") }
        PrimaryButton("保存设置", Icons.Outlined.Check, valid && dirty) {
            // 数字输入单独存放，保存时合并进草稿；先让草稿与将要落盘的值一致，
            // 否则保存后「有未保存修改」会一直亮着。
            val next =
                mergeSettingsDraft(
                    synced,
                    draft.copy(
                        width = width.toInt(),
                        height = height.toInt(),
                        memoryMb = memory.toInt(),
                        initialMemoryMb = initial.toInt(),
                        proxyPort = proxyPort.toIntOrNull() ?: 0,
                    ),
                    actions.controller.store.data.settings,
                )
            draft = next
            width = next.width.toString()
            height = next.height.toString()
            memory = next.memoryMb.toString()
            initial = next.initialMemoryMb.toString()
            proxyPort = next.proxyPort.toString()
            actions.run { actions.controller.saveSettings(next) }
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
            action = {
                SectionResetAction(card.onReset != null && card.dirty) { card.onReset?.invoke() }
            },
            // 行间改用 1dp 细分隔线：首行之前、末行之后都不画，卡片内边距与 44/52 行高不变。
            contentGap = 0.dp,
        ) {
            blocks.forEachIndexed { index, block ->
                if (index > 0) SettingDivider()
                block.render()
            }
        }
    }
}

/** 卡片标题行右侧的「重置本区」文字按钮：仅草稿与本区已保存值不同时淡入。 */
@Composable
private fun SectionResetAction(visible: Boolean, onReset: () -> Unit) {
    val duration = if (LocalReducedMotion.current) 0 else Motion.fast
    AnimatedVisibility(visible, enter = fadeIn(tween(duration)), exit = fadeOut(tween(duration))) {
        Text(
            "重置本区",
            Modifier.pressScale()
                .clip(RoundedCornerShape(Metrics.innerRadius))
                .clickable(role = Role.Button, onClick = onReset)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            color = MaterialTheme.colorScheme.primary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 多行输入框：用于附加 JVM 参数，圆角 4、1dp 边，聚焦时边框变主题色。 */
@Composable
private fun SettingTextArea(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    hint: String? = null,
) {
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
                .heightIn(min = 72.dp)
                .clip(RoundedCornerShape(Metrics.innerRadius))
                .background(fill)
                .border(1.dp, border, RoundedCornerShape(Metrics.innerRadius))
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            BasicTextField(
                value,
                onChange,
                Modifier.fillMaxWidth(),
                textStyle =
                    MaterialTheme.typography.bodyMedium.copy(
                        color = colors.onSurface,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    ),
                cursorBrush = SolidColor(colors.primary),
                interactionSource = interaction,
            )
        }
        if (hint != null) Text(hint, color = colors.onSurfaceVariant, fontSize = 11.sp)
    }
}

/** 全局游戏设置：内存、游戏窗口、高级参数、游戏目录、版本隔离与启动器文件。 */
@Composable
private fun gameSettingCards(
    state: LauncherState,
    actions: UiActions,
    editor: SettingsEditor,
    maximum: Int,
    onAdvancedValid: (Boolean) -> Unit,
    advancedRevision: Int,
): List<SettingCard> {
    val draft = editor.draft
    val saved = editor.saved
    val numbers = editor.numbers
    val cards = mutableListOf<SettingCard>()
    cards.add(
        SettingCard(
            "内存分配",
            "每个实例都可以覆盖这里的设置。",
            listOf(
                settingRow("自动分配内存", "启动时根据剩余物理内存重新计算，保留系统与游戏本地内存空间。") {
                    CompactSwitch(
                        draft.autoMemory,
                        { enabled -> editor.update { it.copy(autoMemory = enabled) } },
                        Modifier.semantics { contentDescription = "自动分配内存开关" },
                    )
                },
                settingField(
                    "最大内存",
                    "物理内存总量约 " +
                        actions.controller.platform.totalMemoryMb / 1024 +
                        " GB；拖动滑块按 256 MB 步进",
                ) {
                    if (draft.autoMemory) {
                        val estimate =
                            automaticMemoryMb(
                                actions.controller.platform.totalMemoryMb,
                                actions.controller.platform.availableMemoryMb,
                            )
                        MutedText("当前预计分配 $estimate MB；实际值在启动游戏时计算，初始内存会限制在最大值以内。")
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CompactSlider(
                            (numbers.memory.toFloatOrNull() ?: 512f).coerceIn(
                                512f,
                                maximum.toFloat(),
                            ),
                            {
                                numbers.onMemory(
                                    (it.toInt() / 256 * 256).coerceIn(512, maximum).toString()
                                )
                            },
                            Modifier.weight(1f),
                            valueRange = 512f..maximum.toFloat(),
                            enabled = !draft.autoMemory,
                        )
                        Tag("%.1f GB".format((numbers.memory.toIntOrNull() ?: 0) / 1024f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        NumberField(
                            numbers.memory,
                            "最大内存（MB）",
                            numbers.onMemory,
                            Modifier.weight(1f),
                            enabled = !draft.autoMemory,
                        )
                        NumberField(
                            numbers.initial,
                            "初始内存（MB）",
                            numbers.onInitial,
                            Modifier.weight(1f),
                            enabled = !draft.autoMemory,
                        )
                    }
                    MutedText("原版通常从 2–4 GB 开始；大型整合包按需要调整。初始内存不能超过最大内存。")
                },
            ),
            dirty =
                draft.autoMemory != saved.autoMemory ||
                    numbers.memory != saved.memoryMb.toString() ||
                    numbers.initial != saved.initialMemoryMb.toString(),
            onReset = {
                editor.update { it.copy(autoMemory = saved.autoMemory) }
                numbers.onMemory(saved.memoryMb.toString())
                numbers.onInitial(saved.initialMemoryMb.toString())
            },
        )
    )
    cards.add(
        SettingCard(
            "游戏窗口",
            "「外观 → 默认游戏窗口」选择自定义分辨率时生效。",
            listOf(
                settingRow("全屏启动", "关闭时使用下方的窗口分辨率") {
                    CompactSwitch(
                        draft.fullscreen,
                        { value -> editor.update { it.copy(fullscreen = value) } },
                    )
                },
                settingField(
                    "窗口分辨率",
                    if (draft.fullscreen) "全屏启动时忽略这里的宽高。" else "范围 640–7680 × 480–4320 像素。",
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        CompactTextField(
                            numbers.width,
                            { numbers.onWidth(it.filter(Char::isDigit).take(5)) },
                            Modifier.weight(1f),
                            label = "窗口宽度（px）",
                            enabled = !draft.fullscreen,
                        )
                        CompactTextField(
                            numbers.height,
                            { numbers.onHeight(it.filter(Char::isDigit).take(5)) },
                            Modifier.weight(1f),
                            label = "窗口高度（px）",
                            enabled = !draft.fullscreen,
                        )
                    }
                },
                settingRow("启动后最小化", "游戏退出后恢复启动器窗口") {
                    CompactSwitch(
                        draft.advancedLaunch.launcherVisibility.ifBlank {
                            if (draft.minimizeOnLaunch) "minimize" else "keep"
                        } == "minimize",
                        { value ->
                            editor.update {
                                it.copy(
                                    minimizeOnLaunch = value,
                                    advancedLaunch =
                                        it.advancedLaunch.copy(
                                            launcherVisibility = if (value) "minimize" else "keep"
                                        ),
                                )
                            }
                        },
                    )
                },
            ),
            dirty =
                draft.fullscreen != saved.fullscreen ||
                    numbers.width != saved.width.toString() ||
                    numbers.height != saved.height.toString() ||
                    draft.minimizeOnLaunch != saved.minimizeOnLaunch ||
                    draft.advancedLaunch.launcherVisibility !=
                        saved.advancedLaunch.launcherVisibility,
            onReset = {
                numbers.onWidth(saved.width.toString())
                numbers.onHeight(saved.height.toString())
                editor.update {
                    it.copy(
                        fullscreen = saved.fullscreen,
                        minimizeOnLaunch = saved.minimizeOnLaunch,
                        advancedLaunch =
                            it.advancedLaunch.copy(
                                launcherVisibility = saved.advancedLaunch.launcherVisibility
                            ),
                    )
                }
            },
        )
    )
    // 显隐读草稿而不是已保存值：勾选后立即出现，不必先保存（C-15）。
    if (draft.pages.showAdvancedLaunchOptions)
        cards.add(
            SettingCard(
                "高级参数",
                "通常留空即可，内存请使用上方专用设置。",
                listOf(
                    settingControl("附加 JVM 参数") {
                        SettingTextArea(
                            draft.extraJvmArgs,
                            { value -> editor.update { it.copy(extraJvmArgs = value) } },
                            "附加 JVM 参数",
                            "每行一个参数，留空使用启动器默认值。",
                        )
                    },
                    settingControl("启动参数编辑器") {
                        key(saved, advancedRevision) {
                            AdvancedLaunchEditor(
                                draft.advancedLaunch,
                                actions.controller.platform.osName,
                                { value -> editor.update { it.copy(advancedLaunch = value) } },
                                onAdvancedValid,
                                legacyMinimize = draft.minimizeOnLaunch,
                            )
                        }
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
                dirty =
                    draft.extraJvmArgs != saved.extraJvmArgs ||
                        draft.advancedLaunch != saved.advancedLaunch,
                onReset = {
                    editor.update {
                        it.copy(
                            extraJvmArgs = saved.extraJvmArgs,
                            advancedLaunch = saved.advancedLaunch,
                        )
                    }
                },
            )
        )
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
                settingRow("默认启用版本隔离", "分别保存各个版本的模组、存档与配置") {
                    CompactSwitch(
                        draft.isolateVersions,
                        { value -> editor.update { it.copy(isolateVersions = value) } },
                    )
                },
                settingNote("单个版本可覆盖此选项。切换隔离规则会改变使用目录，现有文件仍在原位置。"),
            ),
            dirty = draft.isolateVersions != saved.isolateVersions,
            onReset = { editor.update { it.copy(isolateVersions = saved.isolateVersions) } },
        )
    )
    cards.add(
        SettingCard(
            "启动器文件",
            "启动器运行数据、日志与缓存都保存在这个目录。",
            listOf(
                settingField("数据目录") {
                    MutedText(actions.controller.store.dataDirectory.toString())
                    SecondaryButton({
                        actions.run {
                            actions.controller.platform.openFolder(
                                actions.controller.store.dataDirectory
                            )
                        }
                    }) {
                        Text("打开数据目录")
                    }
                }
            ),
        )
    )
    return cards
}

/** Java 管理：检测结果、Java 路径与运行环境准备。 */
@Composable
private fun javaSettingCards(
    actions: UiActions,
    editor: SettingsEditor,
    runtimes: List<JavaRuntime>,
    detecting: Boolean,
    detectionError: String?,
    onRefresh: () -> Unit,
    navigate: (Page) -> Unit,
): List<SettingCard> {
    val draft = editor.draft
    val saved = editor.saved
    return listOf(
        SettingCard(
            "Java 运行环境",
            "自动模式会按游戏要求选择合适版本，缺少时准备对应运行环境。",
            listOf(
                settingField(
                    "检测结果",
                    if (detectionError != null) "检测失败：" + detectionError
                    else "只检测本机已安装的 Java；不会修改系统环境变量。",
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Tag(
                            when {
                                detecting -> "正在检测"
                                detectionError != null -> "检测失败"
                                runtimes.isEmpty() -> "尚未检测"
                                else -> runtimes.size.toString() + " 个运行环境"
                            }
                        )
                        Spacer(Modifier.weight(1f))
                        SecondaryButton({ onRefresh() }, enabled = !detecting) {
                            Text(if (detectionError != null) "重试检测" else "检测本机 Java")
                        }
                    }
                    if (detecting) LinearProgressIndicator(Modifier.fillMaxWidth())
                },
                settingField(
                    "Java 可执行文件路径",
                    "留空时自动选择匹配的 Java；也可以从检测结果里挑一个。",
                ) {
                    SimpleDropdown(
                        draft.javaPath,
                        listOf("" to "自动选择匹配的 Java") +
                            runtimes.map {
                                it.path to
                                    ("Java " +
                                        it.majorVersion +
                                        " · " +
                                        it.vendor +
                                        " · " +
                                        it.path)
                            },
                        { value -> editor.update { it.copy(javaPath = value) } },
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CompactTextField(
                            draft.javaPath,
                            { value -> editor.update { it.copy(javaPath = value) } },
                            Modifier.weight(1f),
                            placeholder = "留空自动选择",
                        )
                        SmallIconButton(
                            Icons.Outlined.FolderOpen,
                            "选择 Java",
                            {
                                actions.run {
                                    val chosen =
                                        actions
                                            .choose(
                                                "选择 Java 可执行文件",
                                                extensions =
                                                    if (
                                                        actions.controller.platform.osName ==
                                                            "windows"
                                                    )
                                                        listOf("exe")
                                                    else emptyList(),
                                            )
                                            .firstOrNull()
                                    if (chosen != null)
                                        withContext(Dispatchers.Swing) {
                                            editor.update { it.copy(javaPath = chosen.toString()) }
                                        }
                                }
                            },
                        )
                    }
                },
            ),
            dirty = draft.javaPath != saved.javaPath,
            onReset = { editor.update { it.copy(javaPath = saved.javaPath) } },
        ),
        SettingCard(
            "准备运行环境",
            "使用 Zulu / Temurin 官方分发，不改变系统现有 Java。",
            listOf(
                settingControl("安装 Java 运行时") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf(8, 17, 21, 25).forEach { version ->
                            SecondaryButton(
                                {
                                    actions.run { actions.controller.installJava(version) }
                                    navigate(Page.Downloads)
                                },
                                enabled = !actions.controller.busy,
                            ) {
                                Text("Java " + version)
                            }
                        }
                    }
                }
            ),
        ),
    )
}

/** 通用：更新检查与语言。 */
@Composable
private fun generalSettingCards(
    actions: UiActions,
    editor: SettingsEditor,
): List<SettingCard> {
    val draft = editor.draft
    val saved = editor.saved
    return listOf(
        SettingCard(
            "更新",
            "检查启动器新版本；更新包需手动下载安装。",
            listOf(
                settingRow("当前版本", LAUNCHER_VERSION) {
                    SecondaryButton(
                        { actions.controller.checkForUpdates(true) },
                        enabled = !actions.controller.busy,
                    ) {
                        Text("检查更新")
                    }
                },
                settingRow("启动时自动检查更新", "需要先配置下方更新清单地址") {
                    CompactSwitch(
                        draft.autoCheckUpdates,
                        { value -> editor.update { it.copy(autoCheckUpdates = value) } },
                    )
                },
                settingField(
                    "更新清单地址",
                    "HTTPS JSON；留空表示未配置更新源，检查更新会如实提示。",
                ) {
                    CompactTextField(
                        draft.updateManifestUrl,
                        { value -> editor.update { it.copy(updateManifestUrl = value) } },
                        placeholder = "https://example.com/molifox.json",
                    )
                    MutedText("清单格式：{\"version\":\"x.y.z\",\"notes\":\"更新说明\"}。")
                },
            ),
            dirty =
                draft.autoCheckUpdates != saved.autoCheckUpdates ||
                    draft.updateManifestUrl != saved.updateManifestUrl,
            onReset = {
                editor.update {
                    it.copy(
                        autoCheckUpdates = saved.autoCheckUpdates,
                        updateManifestUrl = saved.updateManifestUrl,
                    )
                }
            },
        ),
        SettingCard(
            "语言",
            "当前仅提供简体中文；多语言在后续版本提供。",
            listOf(
                settingRow("界面语言", "切换语言需要重启启动器") { Tag("简体中文") },
                settingNote("更多语言翻译将在后续版本提供，字段已随设置保存。"),
            ),
        ),
    )
}

/** 下载：下载源、并发数、缓存文件夹与代理。 */
@Composable
private fun downloadSettingCards(
    actions: UiActions,
    editor: SettingsEditor,
): List<SettingCard> {
    val draft = editor.draft
    val saved = editor.saved
    val numbers = editor.numbers
    val cards = mutableListOf<SettingCard>()
    cards.add(
        SettingCard(
            "下载设置",
            "镜像用于 Minecraft 元数据、本体、资源及依赖。",
            listOf(
                settingField("游戏文件下载源", "影响本体、资源与依赖的下载地址。") {
                    SimpleDropdown(
                        draft.downloadSource,
                        listOf(
                            "auto" to "自动（官方优先，失败切换）",
                            "official" to "官方源",
                            "bmclapi" to "BMCLAPI 镜像源",
                        ),
                        { value -> editor.update { it.copy(downloadSource = value) } },
                    )
                },
                settingField("版本列表源", "只影响版本清单的获取地址。") {
                    SimpleDropdown(
                        draft.versionListSource,
                        listOf(
                            "auto" to "自动（官方优先，失败切换）",
                            "official" to "官方版本列表",
                            "bmclapi" to "BMCLAPI 镜像版本列表",
                        ),
                        { value -> editor.update { it.copy(versionListSource = value) } },
                    )
                },
                settingField(
                    "附加内容下载源",
                    "检索接口当前只接收 Modrinth；CurseForge 需要 API Key，开通前不可选。",
                ) {
                    // 只保留真正生效的选项：检索接口当前不接收来源参数，
                    // 让用户选 CurseForge 会得到一个改了也不生效的开关。
                    SimpleDropdown(
                        "modrinth",
                        listOf("modrinth" to "Modrinth（默认）"),
                        { editor.update { it.copy(contentDownloadSource = "modrinth") } },
                        enabled = false,
                    )
                },
                settingRow("并行下载数", "同时进行的文件下载数量") {
                    SimpleDropdown(
                        draft.maxConcurrent.toString(),
                        listOf(2, 4, 8, 16, 32).map {
                            it.toString() to (it.toString() + " 个文件")
                        },
                        { value -> editor.update { it.copy(maxConcurrent = value.toInt()) } },
                        Modifier.width(132.dp),
                    )
                },
                settingNote("加载器资源使用各自官方服务。下载任务可在「日志」取消。"),
            ),
            dirty =
                draft.downloadSource != saved.downloadSource ||
                    draft.versionListSource != saved.versionListSource ||
                    draft.contentDownloadSource != saved.contentDownloadSource ||
                    draft.maxConcurrent != saved.maxConcurrent,
            onReset = {
                editor.update {
                    it.copy(
                        downloadSource = saved.downloadSource,
                        versionListSource = saved.versionListSource,
                        contentDownloadSource = saved.contentDownloadSource,
                        maxConcurrent = saved.maxConcurrent,
                    )
                }
            },
        )
    )
    cards.add(
        SettingCard(
            "缓存文件夹",
            "版本列表与安装包的缓存位置，留空使用 MoliFox/cache。",
            listOf(
                settingField("缓存文件夹", "绝对路径；目录不存在时会自动创建。") {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CompactTextField(
                            draft.downloadCachePath,
                            { value -> editor.update { it.copy(downloadCachePath = value) } },
                            Modifier.weight(1f),
                            placeholder = "留空使用默认缓存目录",
                        )
                        SmallIconButton(
                            Icons.Outlined.FolderOpen,
                            "选择缓存文件夹",
                            {
                                actions.run {
                                    val chosen =
                                        actions.choose("选择下载缓存文件夹", directory = true).firstOrNull()
                                    if (chosen != null)
                                        withContext(Dispatchers.Swing) {
                                            editor.update {
                                                it.copy(downloadCachePath = chosen.toString())
                                            }
                                        }
                                }
                            },
                        )
                    }
                }
            ),
            dirty = draft.downloadCachePath != saved.downloadCachePath,
            onReset = { editor.update { it.copy(downloadCachePath = saved.downloadCachePath) } },
        )
    )
    cards.add(
        SettingCard(
            "代理",
            "保存后立即作用于启动器下载、资源搜索和登录。",
            listOf(
                settingRow("连接方式") {
                    SimpleDropdown(
                        draft.effectiveProxyMode(),
                        listOf(
                            "system" to "跟随系统",
                            "direct" to "直接连接",
                            "manual" to "手动代理",
                        ),
                        { value ->
                            editor.update {
                                it.copy(proxyMode = value, proxyEnabled = value == "manual")
                            }
                        },
                        Modifier.width(150.dp),
                    )
                },
                settingField("代理类型", "仅手动代理时可用。") {
                    SimpleDropdown(
                        draft.proxyType,
                        listOf("http" to "HTTP", "socks" to "SOCKS"),
                        { value -> editor.update { it.copy(proxyType = value) } },
                        enabled = draft.effectiveProxyMode() == "manual",
                    )
                },
                settingField("代理地址", "主机与端口；仅手动代理时生效。") {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        CompactTextField(
                            draft.proxyHost,
                            { value -> editor.update { it.copy(proxyHost = value) } },
                            Modifier.weight(1f),
                            placeholder = "127.0.0.1",
                            enabled = draft.effectiveProxyMode() == "manual",
                        )
                        CompactTextField(
                            numbers.proxyPort,
                            { numbers.onProxyPort(it.filter(Char::isDigit).take(5)) },
                            Modifier.width(120.dp),
                            placeholder = "端口",
                            enabled = draft.effectiveProxyMode() == "manual",
                        )
                    }
                },
            ),
            dirty =
                draft.effectiveProxyMode() != saved.effectiveProxyMode() ||
                    draft.proxyType != saved.proxyType ||
                    draft.proxyHost != saved.proxyHost ||
                    numbers.proxyPort != saved.proxyPort.toString(),
            onReset = {
                numbers.onProxyPort(saved.proxyPort.toString())
                editor.update {
                    it.copy(
                        proxyMode = saved.proxyMode,
                        proxyEnabled = saved.proxyEnabled,
                        proxyType = saved.proxyType,
                        proxyHost = saved.proxyHost,
                    )
                }
            },
        )
    )
    return cards
}

/** 关于：品牌信息与正版登录说明。 */
@Composable
private fun aboutSettingCards(): List<SettingCard> =
    listOf(
        SettingCard(
            "MoliFox " + LAUNCHER_VERSION,
            "",
            listOf(
                settingControl("版本与平台") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FoxMark(Modifier.size(42.dp))
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(
                                "Minecraft Java Edition Launcher",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            MutedText("Kotlin · Compose Multiplatform · Windows / Linux")
                        }
                    }
                },
                settingNote("独立实现的桌面启动器。参考成熟启动器的操作习惯与 Material 3 设计体系。"),
                settingNote("Minecraft 是 Mojang 的商标。本项目与 Mojang、Microsoft 没有关联。"),
            ),
        ),
        SettingCard(
            "正版登录",
            "",
            listOf(settingNote("Microsoft Client ID 已内置。Mojang 应用访问申请已获批，正版登录全流程仍待实际验证。")),
        ),
    )
