// 负责启动、下载、Java 和通用设置表单。

package dev.molifox.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

/**
 * 还原默认配置的取值：设置页负责的选项全部回到出厂值。 游戏目录、游戏目录语义、正版登录身份与 CurseForge Key 不属于本页，保持原样；
 * 已导入的字体、背景图片属于文件资产，只重置它们的选项，不删除文件本身。
 */
private fun defaultSettings(current: LauncherSettings, maximumMemoryMb: Int): LauncherSettings {
    val defaults = LauncherSettings()
    val memory = minOf(defaults.memoryMb, maximumMemoryMb)
    return defaults.copy(
        gameRoot = current.gameRoot,
        gameRootSemantics = current.gameRootSemantics,
        microsoftClientId = current.microsoftClientId,
        curseForgeApiKey = current.curseForgeApiKey,
        customFonts = current.customFonts,
        backgroundPath = current.backgroundPath,
        backgroundRevision = current.backgroundRevision,
        memoryMb = memory,
        initialMemoryMb = minOf(defaults.initialMemoryMb, memory),
    )
}

@Composable
fun ColumnScope.SettingsPage(state: LauncherState, actions: UiActions, navigate: (Page) -> Unit) {
    val settings = state.data.settings
    var section by remember { mutableIntStateOf(0) }
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
            { refresh++ },
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
                        AppearanceSection(state, actions, resetDefaults)
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
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
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
                            Modifier.weight(1f),
                            valueRange = 512f..maximum.toFloat(),
                            enabled = !settings.autoMemory,
                        )
                        Tag("%.1f GB".format(memory.floatValue / 1024f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        LiveNumberField(
                            settings.memoryMb,
                            "最大内存（MB）",
                            512..maximum,
                            Modifier.weight(1f),
                            enabled = !settings.autoMemory,
                        ) { value ->
                            applySetting(actions) {
                                it.copy(
                                    memoryMb = value,
                                    initialMemoryMb = minOf(it.initialMemoryMb, value),
                                )
                            }
                        }
                        LiveNumberField(
                            settings.initialMemoryMb,
                            "初始内存（MB）",
                            128..settings.memoryMb,
                            Modifier.weight(1f),
                            enabled = !settings.autoMemory,
                        ) { value ->
                            applySetting(actions) { it.copy(initialMemoryMb = value) }
                        }
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
                        settings.advancedLaunch.launcherVisibility.ifBlank {
                            if (settings.minimizeOnLaunch) "minimize" else "keep"
                        } == "minimize",
                        { value ->
                            writer.commit {
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
                                legacyMinimize = settings.minimizeOnLaunch,
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
                settingRow("默认启用版本隔离", "分别保存各个版本的模组、存档与配置") {
                    CompactSwitch(
                        settings.isolateVersions,
                        { value -> writer.commit { it.copy(isolateVersions = value) } },
                    )
                },
                settingNote("单个版本可覆盖此选项。切换隔离规则会改变使用目录，现有文件仍在原位置。"),
            ),
        )
    )
    return cards
}

/** 内存按 256 MB 步进并限制在有效范围内：滑块与数字输入共用。 */
private fun steppedMemory(value: Float, maximum: Int): Int =
    (value.toInt() / 256 * 256).coerceIn(512, maximum)

/** Java 管理：检测结果、Java 路径与运行环境准备。 */
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
): List<SettingCard> =
    listOf(
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
                        settings.javaPath,
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
                        { value -> writer.commit { it.copy(javaPath = value) } },
                    )
                    LiveTextField(
                        settings.javaPath,
                        { value -> applySetting(actions) { it.copy(javaPath = value) } },
                        placeholder = "留空自动选择",
                    ) {
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
                                            applySetting(actions) {
                                                it.copy(javaPath = chosen.toString())
                                            }
                                        }
                                }
                            },
                        )
                    }
                },
            ),
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
                                    actions.taskQueued()
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
            "从茉狐官方 GitHub Releases 检查正式版本。",
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
                settingNote("更多语言翻译将在后续版本提供，字段已随设置保存。"),
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
                settingField("游戏文件下载源", "影响本体、资源与依赖的下载地址。") {
                    SimpleDropdown(
                        settings.downloadSource,
                        listOf(
                            "auto" to "自动（官方优先，失败切换）",
                            "official" to "官方源",
                            "bmclapi" to "BMCLAPI 镜像源",
                        ),
                        { value -> writer.commit { it.copy(downloadSource = value) } },
                    )
                },
                settingField("版本列表源", "只影响版本清单的获取地址。") {
                    SimpleDropdown(
                        settings.versionListSource,
                        listOf(
                            "auto" to "自动（官方优先，失败切换）",
                            "official" to "官方版本列表",
                            "bmclapi" to "BMCLAPI 镜像版本列表",
                        ),
                        { value -> writer.commit { it.copy(versionListSource = value) } },
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
                        { writer.commit { it.copy(contentDownloadSource = "modrinth") } },
                        enabled = false,
                    )
                },
                settingRow("并行下载数", "同时进行的文件下载数量") {
                    SimpleDropdown(
                        settings.maxConcurrent.toString(),
                        listOf(2, 4, 8, 16, 32).map {
                            it.toString() to (it.toString() + " 个文件")
                        },
                        { value -> writer.commit { it.copy(maxConcurrent = value.toInt()) } },
                        Modifier.width(132.dp),
                    )
                },
                settingNote("加载器资源使用各自官方服务。下载任务可从右下角进入任务中心取消。"),
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
            "关于茉狐",
            blocks = listOf(settingControl("MoliFox") { LauncherAboutPanel(actions) }),
        )
    )
