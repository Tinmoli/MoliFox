// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责内容搜索、资源文件管理和日志任务页面。

package dev.molifox.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.ContentCategoryOption
import dev.molifox.core.ContentFilter
import dev.molifox.core.ContentHit
import dev.molifox.core.ContentSearch
import dev.molifox.core.GameInstance
import dev.molifox.core.LauncherState
import dev.molifox.core.LoaderType
import dev.molifox.core.minecraftVersionTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 结果排序：只在当前页内重排（后端分页接口只按相关度返回，不改业务参数）。 */
private val contentSortOptions = listOf("downloads" to "下载量", "relevance" to "相关度", "name" to "名称")

private fun contentKindLabel(type: String): String =
    when (type) {
        "modpack" -> "整合包"
        "resourcepack" -> "资源包"
        "shader" -> "光影"
        "datapack" -> "数据包"
        else -> "模组"
    }

/**
 * Modrinth 分类（slug + 中文名），按项目类型分开维护：slug 全部取自 Modrinth 标签接口 `/v2/tag/category` 中该 `project_type`
 * 的条目，顺序沿用官方标签页的分组 （模组；资源包：分辨率 → 分类 → 特性；光影：分类 → 特性 → 性能；等等）。
 */
private val modCategories =
    listOf(
        "technology" to "科技",
        "magic" to "魔法",
        "adventure" to "冒险",
        "decoration" to "装饰",
        "worldgen" to "世界生成",
        "mobs" to "生物",
        "equipment" to "装备",
        "food" to "食物",
        "optimization" to "优化",
        "storage" to "存储",
        "utility" to "实用",
        "library" to "库",
        "transportation" to "交通",
        "game-mechanics" to "游戏机制",
        "economy" to "经济",
        "management" to "管理",
        "social" to "社交",
        "minigame" to "小游戏",
        "cursed" to "诅咒",
    )

/** 数据包在 Modrinth 上复用模组分类（其项目类型以 datapack 加载器标记），因此单独列一份便于以后分化。 */
private val datapackCategories = modCategories

private val modpackCategories =
    listOf(
        "adventure" to "冒险",
        "challenging" to "挑战",
        "combat" to "战斗",
        "kitchen-sink" to "大杂烩",
        "lightweight" to "轻量",
        "magic" to "魔法",
        "multiplayer" to "多人",
        "optimization" to "优化",
        "quests" to "任务",
        "technology" to "科技",
    )

private val resourcePackCategories =
    listOf(
        "8x-" to "8x 及以下",
        "16x" to "16x",
        "32x" to "32x",
        "48x" to "48x",
        "64x" to "64x",
        "128x" to "128x",
        "256x" to "256x",
        "combat" to "战斗",
        "cursed" to "诅咒",
        "decoration" to "装饰",
        "modded" to "模组兼容",
        "realistic" to "写实",
        "simplistic" to "简洁",
        "themed" to "主题",
        "tweaks" to "微调",
        "utility" to "实用",
        "vanilla-like" to "原版风格",
        "audio" to "音效",
        "blocks" to "方块",
        "entities" to "实体",
        "environment" to "环境",
        "equipment" to "装备",
        "fonts" to "字体",
        "gui" to "界面",
        "items" to "物品",
        "locale" to "语言",
        "models" to "模型",
        "core-shaders" to "核心光影",
    )

private val shaderCategories =
    listOf(
        "cartoon" to "卡通",
        "cursed" to "诅咒",
        "fantasy" to "奇幻",
        "realistic" to "写实",
        "semi-realistic" to "半写实",
        "vanilla-like" to "原版风格",
        "atmosphere" to "氛围",
        "bloom" to "泛光",
        "colored-lighting" to "彩色光照",
        "foliage" to "植被",
        "path-tracing" to "路径追踪",
        "pbr" to "PBR 材质",
        "reflections" to "反射",
        "shadows" to "阴影",
        "screenshot" to "适合截图",
        "low" to "低配",
        "medium" to "中配",
        "high" to "高配",
        "potato" to "极低配",
    )

/** 当前类型的分类列表；类型已由左侧导航决定，分类只在该类型真实存在的标签里选。 */
private fun categoriesFor(kind: String): List<Pair<String, String>> =
    when (kind) {
        "resourcepack" -> resourcePackCategories
        "shader" -> shaderCategories
        "datapack" -> datapackCategories
        "modpack" -> modpackCategories
        else -> modCategories
    }

private fun formatDownloads(value: Long): String =
    when {
        value >= 1_000_000 -> "%.1fM".format(value / 1_000_000f)
        value >= 1_000 -> "%.1fk".format(value / 1_000f)
        else -> value.toString()
    }

/**
 * 内容源显示名：只把真实的配置值/项目 ID 映射为官方名称，其余回退中性文案「内容来源」。 与 core 的 `ContentDetails.providerName`
 * 判定一致（CurseForge 项目带 `curseforge:` 前缀）。
 */
internal fun contentSourceName(value: String?): String =
    when {
        value.isNullOrBlank() -> "内容来源"
        value.equals("curseforge", true) || value.startsWith("curseforge:", true) -> "CurseForge"
        value.equals("modrinth", true) || value.startsWith("modrinth:", true) -> "Modrinth"
        else -> "内容来源"
    }

/** 内容源主页；只为真实存在的内容源提供入口，未知来源返回 null（不伪造地址）。 */
internal fun contentSourceHome(value: String?): String? =
    when (contentSourceName(value)) {
        "Modrinth" -> "https://modrinth.com"
        "CurseForge" -> "https://www.curseforge.com/minecraft"
        else -> null
    }

/**
 * 分类多选控件：按钮显示「分类」/「分类 · N」，弹出复选列表并可一键清除。 单选下拉是 [SimpleDropdown]，无法表达集合，所以这里用 DropdownMenu 自己维护选中集合。
 */
@Composable
private fun ContentCategoryPicker(
    selected: Set<String>,
    options: List<Pair<String, String>>,
    onChange: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val pickerShape = RoundedCornerShape(Metrics.innerRadius)
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(Metrics.controlHeight)
                .clip(pickerShape)
                .background(controlBackground(), pickerShape)
                .border(controlBorder(), pickerShape)
                .hoverHighlight(pickerShape)
                .clickable(role = Role.Button) { expanded = true }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (selected.isEmpty()) "分类" else "分类 · ${selected.size}",
                Modifier.weight(1f),
                fontSize = 13.sp,
                color =
                    if (selected.isEmpty()) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                Icons.Outlined.ExpandMore,
                null,
                Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(
            expanded,
            { expanded = false },
            modifier = Modifier.heightIn(max = 360.dp).widthIn(min = 200.dp, max = 260.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "分类",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onChange(emptySet()) }, enabled = selected.isNotEmpty()) {
                    Text("清除", fontSize = 12.sp)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
            for ((slug, label) in options) {
                val checked = slug in selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 38.dp)
                        .background(if (checked) selectedRowBackground() else Color.Transparent)
                        .rowHoverHighlight(enabled = !checked)
                        .clickable {
                            onChange(if (checked) selected - slug else selected + slug)
                        }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked, null)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        label,
                        fontSize = 13.sp,
                        color =
                            if (checked) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColumnScope.ContentBrowser(
    state: LauncherState,
    actions: UiActions,
    kind: String,
) {
    val preferences = state.data.settings.pages.downloads
    var query by remember { mutableStateOf("") }
    var result by remember { mutableStateOf(ContentSearch(emptyList(), 0)) }
    var offset by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var sort by remember { mutableStateOf("downloads") }
    var listView by remember { mutableStateOf(true) }
    // 首次检索完成前显示骨架，之后刷新只保留旧结果 + 顶部细进度，避免列表整块闪烁。
    var loadedOnce by remember(kind) { mutableStateOf(false) }
    val selected = state.data.instances.find { it.id == state.data.selectedInstanceId }
    val source = state.data.settings.contentDownloadSource
    // 实例的版本目录名可能是整合包标识（TwilightMC-26.3v1.0），只把能识别的真实版本用于筛选。
    val instanceVersion =
        minecraftVersionTag(selected?.gameVersion.orEmpty())?.takeIf { it.isNotBlank() }
    var projectHit by remember(kind) { mutableStateOf<ContentHit?>(null) }
    // 默认筛选值直接取自当前实例，换实例时重新初始化；不存在单独的实例开关。
    var gameVersion by
        remember(kind, selected?.id) {
            mutableStateOf(instanceVersion.orEmpty())
        }
    var loader by
        remember(kind, selected?.id) {
            mutableStateOf(selected?.loader.takeIf { kind == "mod" })
        }
    // 分类：Modrinth 用内置 slug，CurseForge 用镜像自己的数字编号，换来源/类型都重新取。
    var categories by remember(kind, source) { mutableStateOf(emptySet<String>()) }
    var sourceCategories by remember(kind, source) {
        mutableStateOf(emptyList<ContentCategoryOption>())
    }
    LaunchedEffect(kind, source) {
        if (source != "curseforge") {
            sourceCategories = emptyList()
            return@LaunchedEffect
        }
        sourceCategories =
            try {
                withContext(Dispatchers.IO) {
                    actions.controller.content.contentCategories(kind)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
    }
    val categoryOptions =
        if (source == "curseforge") {
            sourceCategories.map { it.id to it.name }
        } else {
            categoriesFor(kind)
        }
    var gameVersions by remember { mutableStateOf(emptyList<String>()) }
    var versionListError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            gameVersions =
                withContext(Dispatchers.IO) {
                    actions.controller.installer
                        .versions()
                        .filter { it.type == "release" }
                        .map { it.id }
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            versionListError = failure.message
        }
    }
    // 筛选只用这两个值：默认等于当前实例，改选就是显式筛选。
    val filter =
        ContentFilter(
            gameVersion,
            loader.takeIf { kind == "mod" && it != LoaderType.Vanilla },
            categories,
        )
    projectHit?.let { hit ->
        ContentProjectDialog(
            hit,
            state,
            actions,
            filter,
            { projectHit = null },
            {
                projectHit = null
                actions.taskQueued()
            },
        )
    }
    LaunchedEffect(
        kind,
        query,
        selected?.id,
        selected?.gameVersion,
        selected?.loader,
        state.data.settings.contentDownloadSource,
        filter,
    ) {
        offset = 0
    }
    LaunchedEffect(
        kind,
        query,
        selected?.id,
        selected?.gameVersion,
        selected?.loader,
        state.data.settings.contentDownloadSource,
        filter,
        offset,
        refresh,
    ) {
        loading = true
        error = null
        delay(350)
        try {
            result =
                withContext(Dispatchers.IO) {
                    actions.controller.content.search(
                        query,
                        kind,
                        if (kind == "modpack") null else state.data.selectedInstanceId,
                        offset,
                        filter,
                    )
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            error = exception.message
        } finally {
            if (isActive) {
                loading = false
                loadedOnce = true
            }
        }
    }
    // ---- 搜索表单卡片：一行搜索、一行筛选（44dp 行）、一行来源说明 ----
    Panel(Modifier.fillMaxWidth(), padding = 12) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompactSearchField(
                    query,
                    { query = it },
                    Modifier.weight(1f),
                    placeholder = "搜索资源名称，例如 Sodium、Fabric API…",
                )
                PrimaryButton("搜索", Icons.Outlined.Search, onClick = { refresh++ })
                val sourceHome = contentSourceHome(state.data.settings.contentDownloadSource)
                if (sourceHome != null) {
                    SmallIconButton(
                        Icons.Outlined.OpenInNew,
                        "打开 " +
                            contentSourceName(state.data.settings.contentDownloadSource) +
                            " 主页",
                        { actions.run { actions.controller.platform.openUrl(sourceHome) } },
                    )
                }
            }
            FlowRow(
                Modifier.fillMaxWidth().heightIn(min = 44.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 分类：类型已由左侧导航决定，只列出该类型在 Modrinth 上真实存在的分类。
                ContentCategoryPicker(
                    categories,
                    categoryOptions,
                    { categories = it },
                    Modifier.width(110.dp),
                )
                // 游戏版本：默认就是当前实例的真实版本（没有实例时为「全部游戏版本」）。
                SimpleDropdown(
                    gameVersion,
                    buildList<Pair<String, String>> {
                        instanceVersion?.let { add(it to it) }
                        add("" to "全部游戏版本")
                        gameVersions.filter { it != instanceVersion }.forEach { add(it to it) }
                        // 实例版本可能不在官方正式版列表里（例如快照），兜底补一项。
                        if (
                            gameVersion.isNotBlank() &&
                            gameVersion != instanceVersion &&
                            gameVersion !in gameVersions
                        ) {
                            add(gameVersion to gameVersion)
                        }
                    },
                    { picked -> gameVersion = picked },
                    Modifier.width(140.dp),
                )
                if (kind == "mod") {
                    val instanceLoader = selected?.loader
                    val loaderOptions =
                        buildList<Pair<String, String>> {
                            instanceLoader?.let { add(it.name to it.title) }
                            LoaderType.entries
                                .filter { it != LoaderType.Vanilla && it != instanceLoader }
                                .forEach { add(it.name to it.title) }
                            add("" to "全部加载器")
                        }
                    SimpleDropdown(
                        loader?.name.orEmpty(),
                        loaderOptions,
                        { picked ->
                            loader = LoaderType.entries.find { entry -> entry.name == picked }
                        },
                        Modifier.width(125.dp),
                    )
                }
                SimpleDropdown(sort, contentSortOptions, { sort = it }, Modifier.width(105.dp))
                // 下载源：点一下就能换。
                SimpleDropdown(
                    state.data.settings.contentDownloadSource,
                    listOf(
                        "modrinth" to contentSourceName("modrinth"),
                        "curseforge" to contentSourceName("curseforge"),
                    ),
                    { picked ->
                        actions.run { actions.controller.setContentDownloadSource(picked) }
                    },
                    Modifier.width(150.dp),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
            if (kind == "modpack") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "整合包也可以直接导入本地文件。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    SecondaryButton({ actions.importPack() }, enabled = !actions.controller.busy) {
                        Icon(Icons.Outlined.Unarchive, null, Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("导入整合包", fontSize = 12.sp)
                    }
                }
            }
            val followsInstance =
                selected != null &&
                    gameVersion == instanceVersion.orEmpty() &&
                    (kind != "mod" || loader == selected.loader)
            val hint =
                when {
                    versionListError != null -> "游戏版本列表暂不可用；项目详情仍可选择作者提供的版本。"
                    followsInstance -> contentHint(kind, selected)
                    else -> "按所选游戏版本 / 加载器筛选，不会更换安装实例。"
                }
            if (hint.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Info,
                        null,
                        Modifier.size(13.dp),
                        MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        hint,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
    // ---- 结果区 ----
    val hits =
        result.hits.let { list ->
            when (sort) {
                "downloads" -> list.sortedByDescending { it.downloads }
                "name" -> list.sortedBy { it.title.lowercase() }
                else -> list
            }
        }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val scrolled =
        if (listView) {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        } else {
            gridState.firstVisibleItemIndex > 0
        }
    val goTop: () -> Unit = {
        scope.launch {
            if (listView) listState.animateScrollToItem(0) else gridState.animateScrollToItem(0)
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (loading && !loadedOnce) "正在检索资源…" else "共 ${result.total} 个结果",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (query.isNotBlank()) {
            Text(
                " · 「" + query + "」",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            "第 ${offset / 12 + 1} 页",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        ViewToggle(listView) { listView = it }
    }
    if (loading && loadedOnce) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
    Box(Modifier.weight(1f).fillMaxWidth()) {
        when {
            loading && !loadedOnce -> SkeletonResults()
            error != null -> {
                val failure = error.orEmpty()
                Panel(Modifier.fillMaxWidth()) {
                    // 映射失败不是网络问题：标题要区分，正文保留具体阶段与响应片段。
                    EmptyState(
                        if (failure.startsWith("CurseForge")) {
                            "资源库返回的数据无法识别"
                        } else {
                            "暂时无法连接资源库"
                        },
                        failure,
                        Icons.Outlined.CloudOff,
                    ) {
                        TextButton({ refresh++ }) { Text("重试") }
                    }
                }
            }
            result.hits.isEmpty() ->
                Box(
                    Modifier.fillMaxWidth().fillMaxHeight(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    EmptyState(
                        "没有找到合适的资源",
                        "试试英文名称，或切换游戏版本与加载器。",
                        Icons.Outlined.SearchOff,
                    ) {
                        if (query.isNotBlank()) TextButton({ query = "" }) { Text("清除搜索条件") }
                    }
                }
            hits.isEmpty() ->
                Box(
                    Modifier.fillMaxWidth().fillMaxHeight(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    EmptyState(
                        "没有符合筛选条件的资源",
                        "换个关键词或清除筛选条件。",
                        Icons.Outlined.FilterAlt,
                    )
                }
            listView ->
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    items(hits, key = { it.id }) { hit ->
                        ContentResultRow(
                            hit,
                            actions,
                            modifier = Modifier.animateItem(),
                            canInstall = !actions.controller.busy,
                            onInstall = {
                                projectHit = hit
                            },
                            onOpen = {
                                projectHit = hit
                            },
                        )
                    }
                }
            else ->
                LazyVerticalGrid(
                    GridCells.Adaptive(250.dp),
                    Modifier.fillMaxSize(),
                    state = gridState,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    items(hits, key = { it.id }) { hit ->
                        ContentResultCard(
                            hit,
                            actions,
                            modifier = Modifier.animateItem(),
                            canInstall = !actions.controller.busy,
                            showAuthors = preferences.showAuthors,
                            showDescriptions = preferences.showDescriptions,
                            onInstall = {
                                projectHit = hit
                            },
                            onOpen = {
                                projectHit = hit
                            },
                        )
                    }
                }
        }
        if (LocalAnimationsEnabled.current) {
            // 显式写全名：外层是 ColumnScope，否则会解析到 ColumnScope.AnimatedVisibility 扩展。
            androidx.compose.animation.AnimatedVisibility(
                visible = scrolled,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                enter = fadeIn(tween(120)) + slideInVertically(tween(150)) { it / 2 },
                exit = fadeOut(tween(90)) + slideOutVertically(tween(120)) { it / 2 },
            ) {
                GoTopButton(goTop)
            }
        } else if (scrolled) {
            Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) { GoTopButton(goTop) }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        MutedText("每页 12 条 · 共 ${result.total} 个资源", Modifier)
        Spacer(Modifier.weight(1f))
        TextButton({ offset = maxOf(0, offset - 12) }, enabled = offset > 0) {
            Text("上一页", fontSize = 12.sp)
        }
        TextButton({ offset += 12 }, enabled = offset + 12 < result.total && offset < 9996) {
            Text("下一页", fontSize = 12.sp)
        }
    }
}

/** 检索条件说明：告诉用户当前按什么筛选、以及为什么安装按钮是灰的。 */
private fun contentHint(
    kind: String,
    target: GameInstance?,
): String =
    when {
        kind == "modpack" -> ""
        target == null -> "先安装并选择游戏实例，即可下载兼容的资源。"
        kind == "mod" && target.loader == LoaderType.Vanilla -> "当前是原版实例，安装模组需要 Fabric、Forge 等加载器。"
        kind == "shader" -> "光影会安装至 shaderpacks；请先在实例中准备兼容的 Iris 或 OptiFine。"
        else ->
            "已按 Minecraft " +
                target.gameVersion +
                (if (kind == "mod") " / " + target.loader.title else "") +
                " 筛选，下载时会补全必需的前置资源。"
    }

@Composable
private fun ViewToggle(
    listView: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val shape = RoundedCornerShape(4.dp)
    Row(
        Modifier
            .height(26.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        listOf(false to Icons.Outlined.GridView, true to Icons.Outlined.ViewList)
            .forEach { (isList, icon) ->
                val active = listView == isList
                Box(
                    Modifier
                        .width(30.dp)
                        .fillMaxHeight()
                        .background(if (active) selectedRowBackground() else Color.Transparent)
                        .clickable { onChange(isList) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon,
                        if (isList) "列表视图" else "卡片视图",
                        Modifier.size(14.dp),
                        if (active) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
    }
}

@Composable
private fun GoTopButton(onClick: () -> Unit) {
    Surface(
        onClick,
        Modifier.size(36.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        shadowElevation = 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Outlined.KeyboardArrowUp,
                "回到顶部",
                Modifier.size(20.dp),
                MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

/** 骨架：28dp 环 + 3 行灰条，稳定占位，避免整页在加载与结果之间跳动。 */
@Composable
private fun SkeletonResults() {
    val alpha =
        if (LocalAnimationsEnabled.current) {
            val transition = rememberInfiniteTransition(label = "skeleton")
            val value by
                transition.animateFloat(
                    0.45f,
                    0.9f,
                    infiniteRepeatable(tween(760), RepeatMode.Reverse),
                    label = "skeleton-alpha",
                )
            value
        } else {
            0.6f
        }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                "正在检索资源…",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        repeat(3) { SkeletonRow(alpha) }
    }
}

@Composable
private fun SkeletonRow(alpha: Float) {
    val bar = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .18f * alpha)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(bar))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(
                Modifier
                    .fillMaxWidth(.45f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(bar),
            )
            Box(
                Modifier
                    .fillMaxWidth(.8f)
                    .height(9.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(bar),
            )
        }
    }
}

/** 列表结果行：图标 40/44 + 两行文字 + 标签 + 安装，整行 56dp，悬停淡入、按压缩放。 */
@Composable
private fun ContentResultRow(
    hit: ContentHit,
    actions: UiActions,
    modifier: Modifier = Modifier,
    canInstall: Boolean,
    onInstall: () -> Unit,
    onOpen: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Metrics.innerRadius)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .hoverHighlight(shape, alpha = .05f)
            // 整行点击打开项目页；按下时轻微缩放（pressScale 不消费指针事件，不影响内部按钮）。
            .pressScale()
            .clickable(onClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ResourceThumbnail(hit, actions)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    hit.title,
                    Modifier.weight(1f, fill = false),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Tag(contentKindLabel(hit.type))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    hit.slug,
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(" · ", fontSize = 11.sp, color = colors.outline)
                Text(
                    hit.description,
                    Modifier.weight(1f),
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(10.dp))
                Icon(Icons.Outlined.Download, null, Modifier.size(12.dp), colors.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Text(
                    formatDownloads(hit.downloads),
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    hit.author,
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onInstall, enabled = canInstall) {
            Icon(Icons.Outlined.Add, null, Modifier.size(14.dp))
            Spacer(Modifier.width(3.dp))
            Text("选择版本", fontSize = 12.sp)
        }
        SmallIconButton(Icons.Outlined.Info, "查看 " + hit.title + " 的详情与版本", onOpen)
    }
}

/** 卡片结果：最小高 168、白底 1dp 细边、悬停边框转主题色。 */
@Composable
private fun ContentResultCard(
    hit: ContentHit,
    actions: UiActions,
    modifier: Modifier = Modifier,
    canInstall: Boolean,
    showAuthors: Boolean,
    showDescriptions: Boolean,
    onInstall: () -> Unit,
    onOpen: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Metrics.cardRadius)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val borderColor by
        animateColorAsState(
            if (hovered) {
                colors.primary.copy(alpha = .6f)
            } else {
                colors.outlineVariant.copy(alpha = .55f)
            },
            tween(120),
            label = "card-border",
        )
    Surface(
        modifier
            .fillMaxWidth()
            .heightIn(min = 168.dp)
            .pressScale()
            .hoverable(interaction),
        shape = shape,
        color = colors.surface,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ResourceThumbnail(hit, actions)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        hit.title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (showAuthors) {
                        Text(
                            "by " + hit.author,
                            fontSize = 11.sp,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                SmallIconButton(Icons.Outlined.Info, "查看 " + hit.title + " 的详情与版本", onOpen)
            }
            if (showDescriptions) {
                Spacer(Modifier.height(10.dp))
                Text(
                    hit.description,
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            HorizontalDivider(color = colors.outlineVariant.copy(alpha = .7f))
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Download, null, Modifier.size(13.dp), colors.onSurfaceVariant)
                Spacer(Modifier.width(5.dp))
                Text(
                    formatDownloads(hit.downloads),
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
                Tag(contentKindLabel(hit.type))
                Spacer(Modifier.weight(1f))
                TextButton(onInstall, enabled = canInstall) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("选择版本", fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * 日志页实现已移到 LogsPage.kt（操作行、级别筛选、搜索与列表）；这里保留原入口，
 * 主界面与设置页的调用点不变。
 */
@Composable
fun ColumnScope.DownloadsPage(
    state: LauncherState,
    actions: UiActions,
) {
    LogsPage(state, actions)
}
