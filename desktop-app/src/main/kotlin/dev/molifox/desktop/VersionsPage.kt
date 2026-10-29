// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责实例列表、筛选、排序和维护操作。

package dev.molifox.desktop

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 实例行高：64dp，容纳两行文字与 12sp 文字操作。 */
private val InstanceRowHeight = 64.dp

/** 加载器筛选里「无法识别」实例单独成组：它不冒充任何加载器，也不参与按加载器的筛选。 */
private const val BrokenLoaderFilter = "无法识别"

/**
 * 实例页：顶部是页头（返回 / 标题 / 计数），其下依次是单行筛选栏（搜索 + 版本 + 加载器 + 排序）、
 * 目录与「新建实例 / 导入整合包」工具面板、实例列表。
 *
 * 列表行显示图标、名称与一行紧凑信息（版本 · 加载器 · 最后游玩 · 占用大小）；占用大小只在行可见时于
 * IO 线程按需统计并缓存，刷新后整表作废。行的右键与行尾「更多操作」打开同一份上下文菜单：
 * 启动 / 重命名 / 复制实例 / 打开游戏目录 / 导出整合包 / 删除，重命名、复制与导出复用现有对话框和控制器，
 * 删除沿用 Main.kt 传入的二次确认入口。
 */
@Composable
fun ColumnScope.InstancesPage(
    state: LauncherState,
    actions: UiActions,
    onNew: () -> Unit,
    onManage: (String) -> Unit,
    onDelete: (GameInstance) -> Unit,
    navigate: (Page) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // 版本 / 加载器筛选与搜索都只活在本次页面组合里，不写入偏好；排序沿用既有做法：
    // 初始值来自设置页的「默认排序」，本次会话内切换不落盘。
    var versionFilter by remember { mutableStateOf("") }
    var loaderFilter by remember { mutableStateOf("") }
    val preferences = state.data.settings.pages.versions
    var grid by remember(preferences.view) { mutableStateOf(preferences.view == "grid") }
    var favorites by remember { mutableStateOf(false) }
    var sort by remember(preferences.sort) { mutableStateOf(preferences.sort) }
    // 行内「更多操作」打开的对话框：重命名 / 复制 / 导出都复用现有实现，本页只保留选择结果。
    var renaming by remember { mutableStateOf<GameInstance?>(null) }
    var copying by remember { mutableStateOf<GameInstance?>(null) }
    var exporting by remember { mutableStateOf<GameInstance?>(null) }
    val sizes = remember { InstanceSizeCache() }
    // 进入/回到实例列表时做一次廉价的目录签名检查；新增、删除或 JSON 改坏都会在这里出现。
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { actions.controller.refreshInstancesIfChanged() } }
        // 重新扫描后目录内容可能已经变化，占用缓存整表作废，可见行会各自重新统计。
        sizes.invalidate()
    }
    // 安装 / 修复 / 删除等任务结束后目录内容变化；任务从运行回到空闲时再作废一次，
    // 避免行里长期显示任务开始前的占用。
    val runningTasks = state.jobs.count { it.status == JobStatus.Running }
    var previousRunningTasks by remember { mutableIntStateOf(runningTasks) }
    LaunchedEffect(runningTasks) {
        if (runningTasks == 0 && previousRunningTasks > 0) sizes.invalidate()
        previousRunningTasks = runningTasks
    }
    val busy = actions.controller.busy
    // 「有没有游戏」以 instances 为准：没有生效目录（自动发现尚未选中、目录刚被移除）
    // 时列出全部实例，避免已经识别到版本却显示成空列表。
    val activeDirectoryId = state.data.activeGameDirectoryId
    val all =
        state.data.instances.filter {
            it.gameDirectoryId == activeDirectoryId
        }
    // 版本筛选只列真实解析出的游戏版本；无法识别（版本为空）的实例单独落在「无法识别」加载器分组里。
    val versionOptions =
        remember(all) {
            val versions =
                all.map { it.gameVersion }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sortedWith(instanceVersionOrder)
            buildList {
                add("" to "版本：全部")
                versions.forEach { add(it to "版本：" + it) }
            }
        }
    // 加载器筛选从当前实例的 loader 派生，按原版 / Forge / NeoForge / Fabric / Quilt 固定次序显示；
    // 存在无法识别实例时再追加一组，选择它只列出损坏实例。
    val loaderOptions =
        remember(all) {
            val present = all.filter { !it.broken }.map { it.loader.title }.toSet()
            buildList {
                add("" to "加载器：全部")
                LoaderFilterOrder.filter { it in present }.forEach { add(it to "加载器：" + it) }
                if (all.any { it.broken }) add(BrokenLoaderFilter to "加载器：无法识别")
            }
        }
    // 实例被删除或目录切换后，当前筛选值可能已经不存在：自动回到「全部」，避免下拉显示内部取值。
    LaunchedEffect(versionOptions) {
        if (versionOptions.none { it.first == versionFilter }) versionFilter = ""
    }
    LaunchedEffect(loaderOptions) {
        if (loaderOptions.none { it.first == loaderFilter }) loaderFilter = ""
    }
    val filtered = all.filter { instance ->
        (!favorites || instance.favorite) &&
            // 搜索只看名称，大小写不敏感且即时生效。
            (query.isBlank() || instance.name.contains(query.trim(), ignoreCase = true)) &&
            (versionFilter.isBlank() || instance.gameVersion == versionFilter) &&
            (loaderFilter.isBlank() ||
                if (loaderFilter == BrokenLoaderFilter) instance.broken
                else !instance.broken && instance.loader.title == loaderFilter)
    }
    val versions =
        when (sort) {
            "name" -> filtered.sortedBy { it.name.lowercase() }
            "created" -> filtered.sortedByDescending { it.createdAt }
            else ->
                filtered.sortedWith(
                    compareByDescending<GameInstance> { preferences.favoritesFirst && it.favorite }
                        .thenByDescending { it.lastPlayed ?: it.createdAt }
                )
        }
    fun launch(instance: GameInstance) {
        if (instance.broken) {
            actions.controller.store.notify(
                "「" + instance.name + "」无法识别：" + instance.brokenReason
            )
            return
        }
        if (!instance.installed) {
            actions.run { actions.controller.repair(instance.id) }
            actions.taskQueued()
        } else if (state.data.activeAccountId == null) {
            actions.controller.store.notify("请先添加一个游戏账户")
            navigate(Page.Home)
        } else {
            actions.run {
                actions.controller.selectInstance(instance.id)
                actions.controller.launch(instance.id)
            }
            actions.taskQueued()
        }
    }
    val clearFilters: () -> Unit = {
        query = ""
        favorites = false
        versionFilter = ""
        loaderFilter = ""
    }
    // 目录可读性只在 IO 线程探测：失败与「没有实例」必须能区分开，否则用户无从判断是空目录还是读不到。
    val directory = state.data.gameDirectories.find { it.id == state.data.activeGameDirectoryId }
    var probeToken by remember { mutableStateOf(0) }
    val probe by
        produceState<String?>(null, directory?.path, probeToken) {
            value = null
            if (directory != null)
                value = withContext(Dispatchers.IO) { probeGameDirectory(directory) }
        }
    // 安装 / 修复任务运行中且列表为空时视为读取中，不额外造一个假的加载状态。
    val pendingRead =
        state.jobs.any {
            it.status == JobStatus.Running &&
                (it.title.startsWith("安装") || it.title.startsWith("修复"))
        }
    // 子页页头与账户列表一致：左上返回箭头 + 18sp 标题；实例计数留在同一行右侧，
    // 有筛选时按「命中 / 总数」显示，例如「3 / 8 个实例」。
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SmallIconButton(Icons.Outlined.ArrowBack, "返回开始页", onClick = { navigate(Page.Home) })
        Spacer(Modifier.width(8.dp))
        Text(
            "实例管理",
            Modifier.weight(1f),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Text(
            if (versions.size != all.size)
                versions.size.toString() + " / " + all.size + " 个实例 · " +
                    versions.count { it.installed } + " 个已安装"
            else all.size.toString() + " 个实例 · " + all.count { it.installed } + " 个已安装",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(12.dp))
    // 单行筛选栏：搜索占剩余宽度，三个下拉与收藏 / 视图按钮固定宽度；1000dp 窗口下仍是一行。
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CompactSearchField(
            query,
            { query = it },
            Modifier.weight(1f),
            placeholder = "搜索实例名称",
        )
        CompactSelect(
            versionFilter,
            versionOptions,
            { versionFilter = it },
            Modifier.width(132.dp),
        )
        CompactSelect(
            loaderFilter,
            loaderOptions,
            { loaderFilter = it },
            Modifier.width(168.dp),
        )
        CompactSelect(
            sort,
            listOf(
                "recent" to "排序：最近游玩",
                "name" to "排序：名称",
                "created" to "排序：创建时间",
            ),
            { sort = it },
            Modifier.width(144.dp),
        )
        CompactToggleButton(
            selected = favorites,
            icon = if (favorites) Icons.Outlined.Star else Icons.Outlined.StarOutline,
            label = "收藏",
            onClick = { favorites = !favorites },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CompactToggleButton(
                selected = !grid,
                icon = Icons.Outlined.ViewList,
                label = null,
                description = "列表视图",
                onClick = { grid = false },
            )
            CompactToggleButton(
                selected = grid,
                icon = Icons.Outlined.GridView,
                label = null,
                description = "网格视图",
                onClick = { grid = true },
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Panel(Modifier.fillMaxWidth(), padding = 12) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.weight(1f)) { GameDirectoryControls(state, actions) }
            PrimaryButton(
                "新建实例",
                Icons.Outlined.Add,
                enabled = !busy,
                onClick = onNew,
            )
            SecondaryButton(
                { actions.importPack() },
                enabled = !busy,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            ) {
                Text("导入整合包", fontSize = 13.sp)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    val issue = probe
    when {
        // 目录可读时 probeGameDirectory 返回空串，不能当成错误态，否则实例列表永远不会显示。
        issue != null && issue.isNotBlank() ->
            InstanceErrorState(Modifier.weight(1f).fillMaxWidth(), issue) {
                probeToken++
                sizes.invalidate()
                actions.run { actions.controller.refreshGameDirectory() }
            }
        versions.isEmpty() && pendingRead ->
            InstanceLoadingState(Modifier.weight(1f).fillMaxWidth())
        versions.isEmpty() ->
            InstanceEmptyState(
                Modifier.weight(1f).fillMaxWidth(),
                noInstance = all.isEmpty(),
                busy = busy,
                onNew = onNew,
                onClearFilter = clearFilters,
            )
        else ->
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val list: @Composable () -> Unit = {
                    InstanceListBody(
                        versions,
                        actions,
                        state.data.selectedInstanceId,
                        busy,
                        preferences.showNotes,
                        sizes,
                        // 开始页的「版本选择」把这里当作挑选页：选中后立刻回开始页，
                        // 「当前游戏」卡与按钮文字跟着切到新实例。
                        onSelect = {
                            actions.run { actions.controller.selectInstance(it.id) }
                            navigate(Page.Home)
                        },
                        onManage = { onManage(it) },
                        onLaunch = { launch(it) },
                        onDelete = { onDelete(it) },
                        onFavorite = {
                            actions.run { actions.controller.toggleFavorite(it) }
                        },
                        onRename = { renaming = it },
                        onCopy = { copying = it },
                        onExport = { exporting = it },
                    )
                }
                val cards: @Composable () -> Unit = {
                    InstanceGridBody(
                        versions,
                        actions,
                        state.data.selectedInstanceId,
                        busy,
                        // 网格视图与列表同一套挑选语义：选中后回到开始页。
                        onSelect = {
                            actions.run { actions.controller.selectInstance(it.id) }
                            navigate(Page.Home)
                        },
                        onManage = { onManage(it) },
                        onLaunch = { launch(it) },
                        onDelete = { onDelete(it) },
                        onFavorite = {
                            actions.run { actions.controller.toggleFavorite(it) }
                        },
                        onRename = { renaming = it },
                        onCopy = { copying = it },
                        onExport = { exporting = it },
                    )
                }
                // 列表 / 网格切换 180ms 交叉淡入；关闭动画时直接切换
                if (LocalAnimationsEnabled.current)
                    Crossfade(
                        grid,
                        Modifier.fillMaxSize(),
                        animationSpec = tween(Motion.normal),
                        label = "versions-view",
                    ) { showGrid ->
                        if (showGrid) cards() else list()
                    }
                else if (grid) cards() else list()
            }
    }
    renaming?.let { instance ->
        RenameInstanceDialog(instance, actions) { renaming = null }
    }
    copying?.let { instance -> CopyInstanceDialog(instance, actions) { copying = null } }
    exporting?.let { instance ->
        ExportMrpackDialog(
            instance,
            actions,
            onDismiss = { exporting = null },
            onStarted = { actions.taskQueued() },
        )
    }
}

/** 收藏 / 视图切换用的小切换按钮：34dp、圆角 4，选中态用主题色。 */
@Composable
private fun CompactToggleButton(
    selected: Boolean,
    icon: ImageVector,
    label: String?,
    description: String? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Metrics.innerRadius)
    val background by
        animateColorAsState(
            if (selected) selectedRowBackground() else Color.Transparent,
            tween(Motion.fast),
            label = "toggle-bg",
        )
    val tint =
        if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.height(Metrics.controlHeight)
            .then(if (label == null) Modifier.width(Metrics.controlHeight) else Modifier)
            .clip(shape)
            // 只有图标的切换按钮必须自带无障碍名字，否则读屏与 GUI 测试只能靠位置猜。
            .then(
                if (description == null) Modifier
                else Modifier.semantics { contentDescription = description }
            )
            .hoverHighlight(shape)
            .clickable(onClick = onClick)
            .background(background, shape)
            .padding(horizontal = if (label == null) 8.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, label, Modifier.size(16.dp), tint)
        if (label != null) Text(label, fontSize = 13.sp, color = tint, maxLines = 1)
    }
}

/** 行内文字按钮：12sp、触区高 28dp，悬停淡入。 */
@Composable
private fun InstanceTextAction(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val shape = RoundedCornerShape(Metrics.innerRadius)
    Box(
        Modifier.height(28.dp)
            .clip(shape)
            .hoverHighlight(shape, enabled = enabled)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = if (enabled) tint else tint.copy(alpha = .38f),
            maxLines = 1,
        )
    }
}

/**
 * 实例占用缓存：按实例 id 存一次遍历结果，只在行可见时由 [InstanceListBody] 里的行请求计算。
 * [invalidate] 清空整表并推进 [revision]，可见行的 LaunchedEffect 会跟着重新统计；滚动本身不触发任何目录遍历。
 */
private class InstanceSizeCache {
    private val entries = mutableStateMapOf<String, Long?>()

    /** 每次作废递增；行把它作为 LaunchedEffect 的键。 */
    var revision by mutableIntStateOf(0)
        private set

    /** 已缓存的结果；null 表示尚未统计，-1 表示统计过但目录不可读。 */
    fun cached(id: String): Long? = entries[id]

    /** 是否已经统计过（结果可能是 null）。 */
    fun knows(id: String): Boolean = entries.containsKey(id)

    fun store(id: String, bytes: Long?) {
        entries[id] = bytes
    }

    fun invalidate() {
        entries.clear()
        revision++
    }
}

/** 列表视图：行高 64dp、圆角 6、悬停淡入；右键或行尾「更多操作」打开上下文菜单。 */
@Composable
private fun InstanceListBody(
    versions: List<GameInstance>,
    actions: UiActions,
    selectedId: String?,
    busy: Boolean,
    showNotes: Boolean,
    sizes: InstanceSizeCache,
    onSelect: (GameInstance) -> Unit,
    onManage: (String) -> Unit,
    onLaunch: (GameInstance) -> Unit,
    onDelete: (GameInstance) -> Unit,
    onFavorite: (String) -> Unit,
    onRename: (GameInstance) -> Unit,
    onCopy: (GameInstance) -> Unit,
    onExport: (GameInstance) -> Unit,
) {
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().padding(end = 12.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 6.dp),
        ) {
            items(versions, key = { it.id }) { instance ->
                InstanceListRow(
                    instance = instance,
                    actions = actions,
                    selected = instance.id == selectedId,
                    busy = busy || actions.controller.isInstanceBusy(instance.id),
                    showNotes = showNotes,
                    sizes = sizes,
                    onSelect = { onSelect(instance) },
                    onManage = { onManage(instance.id) },
                    onLaunch = { onLaunch(instance) },
                    onDelete = { onDelete(instance) },
                    onFavorite = { onFavorite(instance.id) },
                    onRename = { onRename(instance) },
                    onCopy = { onCopy(instance) },
                    onExport = { onExport(instance) },
                )
            }
        }
        VerticalScrollbar(
            rememberScrollbarAdapter(listState),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

@Composable
private fun InstanceListRow(
    instance: GameInstance,
    actions: UiActions,
    selected: Boolean,
    busy: Boolean,
    showNotes: Boolean,
    sizes: InstanceSizeCache,
    onSelect: () -> Unit,
    onManage: () -> Unit,
    onLaunch: () -> Unit,
    onDelete: () -> Unit,
    onFavorite: () -> Unit,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onExport: () -> Unit,
) {
    val shape = RoundedCornerShape(Metrics.cardRadius)
    var menuOpen by remember(instance.id) { mutableStateOf(false) }
    var menuOffset by remember(instance.id) { mutableStateOf(DpOffset.Zero) }
    // 右键菜单贴在指针位置，⋯ 菜单贴在按钮自身：两者用同一个 DropdownMenu 实现，
    // 但锚点必须落在各自的实际坐标空间里，否则弹窗会跑到页面左侧或窗口外。
    var menuAnchor by remember(instance.id) { mutableStateOf(InstanceMenuAnchor.Pointer) }
    val density = LocalDensity.current
    // 占用只在行可见时按需统计：LazyColumn 只组合可见行，滚动到新行时才发起一次 IO 遍历，
    // 结果按实例 id 缓存；页面刷新时整表作废。
    val cachedSize = sizes.cached(instance.id)
    LaunchedEffect(instance.id, sizes.revision) {
        if (sizes.knows(instance.id)) return@LaunchedEffect
        sizes.store(
            instance.id,
            withContext(Dispatchers.IO) {
                // -1 是「统计过但目录不可读」的缓存标记，避免每一帧都重试遍历。
                instanceDirectoryBytes(actions.controller.store.versionGamePath(instance)) ?: -1L
            },
        )
    }
    Box(Modifier.fillMaxWidth()) {
        Surface(
            Modifier.fillMaxWidth()
                .height(InstanceRowHeight)
                // 右键：在指针位置打开同一份上下文菜单；已打开的菜单不重复弹。
                .pointerInput(instance.id) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (
                                event.type == PointerEventType.Press &&
                                    event.buttons.isSecondaryPressed
                            ) {
                                event.changes.firstOrNull()?.let { change ->
                                    menuOffset =
                                        with(density) {
                                            DpOffset(
                                                change.position.x.toDp(),
                                                change.position.y.toDp(),
                                            )
                                        }
                                }
                                menuAnchor = InstanceMenuAnchor.Pointer
                                menuOpen = true
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
                .clickable(onClick = onSelect),
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
            border =
                BorderStroke(
                    1.dp,
                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .6f)
                    else hairline(),
                ),
        ) {
            Row(
                Modifier.fillMaxSize().hoverHighlight(shape).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InstanceArtwork(instance, actions, Modifier.size(28.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            instance.name,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (selected) Tag("当前版本")
                        if (!instance.broken && !instance.installed)
                            Tag("待修复", MaterialTheme.colorScheme.error)
                    }
                    // 紧凑信息行：版本 · 加载器 · 最后游玩 · 占用大小；无法识别时整行用错误色。
                    Text(
                        instanceMetaLine(instance, cachedSize, showNotes),
                        fontSize = 12.sp,
                        color =
                            if (instance.broken) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                InstanceTextAction(
                    "开始游戏",
                    onLaunch,
                    enabled = !busy && !instance.broken,
                    tint = MaterialTheme.colorScheme.primary,
                )
                InstanceTextAction(
                    if (instance.favorite) "已收藏" else "收藏",
                    onFavorite,
                    tint =
                        if (instance.favorite) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                InstanceTextAction("设置", onManage)
                InstanceTextAction("删除", onDelete, tint = MaterialTheme.colorScheme.error)
                // 触屏/键盘的等价入口：与右键菜单是同一份内容。菜单锚在这个按钮自身的 Box 上，
                // 右边缘对齐按钮，位置由 M3 弹窗自己限制在窗口内。
                Box {
                    SmallIconButton(
                        Icons.Outlined.MoreHoriz,
                        "更多操作 " + instance.name,
                        onClick = {
                            menuAnchor = InstanceMenuAnchor.Button
                            menuOpen = true
                        },
                        size = 28.dp,
                    )
                    if (menuAnchor == InstanceMenuAnchor.Button)
                        InstanceContextMenu(
                            expanded = menuOpen,
                            offset = ButtonMenuOffset,
                            instance = instance,
                            busy = busy,
                            onDismiss = { menuOpen = false },
                            onLaunch = onLaunch,
                            onRename = onRename,
                            onCopy = onCopy,
                            onOpenFolder = { actions.run { actions.controller.openFolder(instance.id) } },
                            onExport = onExport,
                            onDelete = onDelete,
                        )
                }
            }
        }
        if (menuAnchor == InstanceMenuAnchor.Pointer)
            InstanceContextMenu(
                expanded = menuOpen,
                offset = menuOffset,
                instance = instance,
                busy = busy,
                onDismiss = { menuOpen = false },
                onLaunch = onLaunch,
                onRename = onRename,
                onCopy = onCopy,
                onOpenFolder = { actions.run { actions.controller.openFolder(instance.id) } },
                onExport = onExport,
                onDelete = onDelete,
            )
    }
}

private enum class InstanceMenuAnchor {
    /** 右键：菜单出现在指针位置（行内坐标）。 */
    Pointer,
    /** ⋯ 按钮：菜单贴着按钮打开，右边缘与按钮对齐。 */
    Button,
}

// ⋯ 菜单：锚点就是按钮自身的 Box，+4dp 让菜单从按钮下沿开始；横向越界交给 M3 弹窗夹回窗口内
// （实测 1000×640 下自动右对齐按钮右缘 x=820–996，1180×720 下 x=1000–1176）。
private val ButtonMenuOffset = DpOffset(0.dp, 4.dp)

// 紧凑菜单项：整份六项菜单约 241dp，1000×640 下贴着按钮完整展开（不会因上下都放不下被居中到按钮中间）。
private val MenuItemModifier = Modifier.height(40.dp)
private val MenuItemPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)

/**
 * 实例行的上下文菜单：启动 / 重命名 / 复制实例 / 打开游戏目录 / 导出整合包 / 删除。
 * 使用 Material3 DropdownMenu（可聚焦弹窗）：Esc 与点击外部都会关闭，位置由弹窗自己限制在窗口内，
 * 因此在 1000×640 下不会溢出；菜单高度上限 320dp，六项内容都不需要滚动。
 */
@Composable
private fun InstanceContextMenu(
    expanded: Boolean,
    offset: DpOffset,
    instance: GameInstance,
    busy: Boolean,
    onDismiss: () -> Unit,
    onLaunch: () -> Unit,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onOpenFolder: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.heightIn(max = 320.dp).widthIn(min = 176.dp),
        offset = offset,
    ) {
        DropdownMenuItem(
            { Text("启动", fontSize = 13.sp) },
            {
                onDismiss()
                onLaunch()
            },
            modifier = MenuItemModifier,
            contentPadding = MenuItemPadding,
            enabled = !instance.broken && !busy,
        )
        DropdownMenuItem(
            { Text("重命名…", fontSize = 13.sp) },
            {
                onDismiss()
                onRename()
            },
            modifier = MenuItemModifier,
            contentPadding = MenuItemPadding,
            enabled = !busy,
        )
        DropdownMenuItem(
            { Text("复制实例…", fontSize = 13.sp) },
            {
                onDismiss()
                onCopy()
            },
            modifier = MenuItemModifier,
            contentPadding = MenuItemPadding,
            enabled = !busy,
        )
        DropdownMenuItem(
            { Text("打开游戏目录", fontSize = 13.sp) },
            {
                onDismiss()
                onOpenFolder()
            },
            modifier = MenuItemModifier,
            contentPadding = MenuItemPadding,
        )
        HorizontalDivider(color = hairline())
        DropdownMenuItem(
            { Text("导出整合包…", fontSize = 13.sp) },
            {
                onDismiss()
                onExport()
            },
            modifier = MenuItemModifier,
            contentPadding = MenuItemPadding,
            enabled = !busy,
        )
        DropdownMenuItem(
            { Text("删除…", fontSize = 13.sp, color = MaterialTheme.colorScheme.error) },
            {
                onDismiss()
                onDelete()
            },
            modifier = MenuItemModifier,
            contentPadding = MenuItemPadding,
        )
    }
}

/** 重命名对话框：复用控制器的 renameInstance（移动 versions 目录并同步引用），本页只负责输入与错误展示。 */
@Composable
private fun RenameInstanceDialog(
    instance: GameInstance,
    actions: UiActions,
    onDismiss: () -> Unit,
) {
    var name by remember(instance.id) { mutableStateOf(instance.name) }
    var error by remember(instance.id) { mutableStateOf("") }
    var running by remember(instance.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val trimmed = name.trim()
    val valid = trimmed.isNotEmpty() && trimmed.length <= 80 && trimmed != instance.name
    Modal(
        "重命名实例",
        "会重命名 versions/" + instance.instanceFolderName() + "，并同步同名 JSON/JAR 与其他版本的引用。",
        onDismiss = onDismiss,
    ) {
        CompactTextField(
            name,
            {
                name = it.take(80)
                error = ""
            },
            Modifier.fillMaxWidth(),
            label = "实例名称",
            placeholder = instance.name,
        )
        if (error.isNotBlank())
            Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        DialogActions(
            confirmText = "保存",
            onConfirm = {
                running = true
                scope.launch {
                    val failure =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                    actions.controller.renameInstance(instance.id, trimmed)
                                }
                                .exceptionOrNull()
                        }
                    running = false
                    if (failure == null) onDismiss()
                    else error = failure.message ?: "重命名失败，请稍后重试"
                }
            },
            onCancel = onDismiss,
            enabled = valid,
            busy = running,
            hint = if (valid) "" else "名称需要 1–80 个字符，且不能与当前名称相同",
        )
    }
}

/** 网格卡固定高度：图标行 + 单行名称 + 单行副标题 + 底部操作行，最长内容也不裁切（内容约 138dp，余量留给弹性间隔）。 */
private val InstanceCardHeight = 176.dp

/** 网格视图：卡片固定高 176dp、悬停 1dp 主题色边 + 轻微底色；操作行贴底，所有卡片按钮对齐。 */
@Composable
private fun InstanceGridBody(
    versions: List<GameInstance>,
    actions: UiActions,
    selectedId: String?,
    busy: Boolean,
    onSelect: (GameInstance) -> Unit,
    onManage: (String) -> Unit,
    onLaunch: (GameInstance) -> Unit,
    onDelete: (GameInstance) -> Unit,
    onFavorite: (String) -> Unit,
    onRename: (GameInstance) -> Unit,
    onCopy: (GameInstance) -> Unit,
    onExport: (GameInstance) -> Unit,
) {
    LazyVerticalGrid(
        // 列宽下限 248dp：卡片内 220dp 才放得下「开始游戏 / 收藏 / 设置 / 删除」四个 12sp 文字按钮。
        GridCells.Adaptive(248.dp),
        Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 6.dp),
    ) {
        items(versions, key = { it.id }) { instance ->
            InstanceGridCard(
                instance = instance,
                actions = actions,
                selected = instance.id == selectedId,
                busy = busy || actions.controller.isInstanceBusy(instance.id),
                onSelect = { onSelect(instance) },
                onManage = { onManage(instance.id) },
                onLaunch = { onLaunch(instance) },
                onDelete = { onDelete(instance) },
                onFavorite = { onFavorite(instance.id) },
                onRename = { onRename(instance) },
                onCopy = { onCopy(instance) },
                onExport = { onExport(instance) },
            )
        }
    }
}

@Composable
private fun InstanceGridCard(
    instance: GameInstance,
    actions: UiActions,
    selected: Boolean,
    busy: Boolean,
    onSelect: () -> Unit,
    onManage: () -> Unit,
    onLaunch: () -> Unit,
    onDelete: () -> Unit,
    onFavorite: () -> Unit,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onExport: () -> Unit,
) {
    val shape = RoundedCornerShape(Metrics.cardRadius)
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()
    var menuOpen by remember(instance.id) { mutableStateOf(false) }
    var menuOffset by remember(instance.id) { mutableStateOf(DpOffset.Zero) }
    // 右键菜单贴在指针位置，⋯ 菜单贴在按钮自身：两者用同一个 DropdownMenu 实现，
    // 但锚点必须落在各自的实际坐标空间里，否则弹窗会跑到页面左侧或窗口外。
    var menuAnchor by remember(instance.id) { mutableStateOf(InstanceMenuAnchor.Pointer) }
    val density = LocalDensity.current
    Box(Modifier.fillMaxWidth()) {
        Surface(
            Modifier.fillMaxWidth()
                .height(InstanceCardHeight)
                .pointerInput(instance.id) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (
                                event.type == PointerEventType.Press &&
                                    event.buttons.isSecondaryPressed
                            ) {
                                event.changes.firstOrNull()?.let { change ->
                                    menuOffset =
                                        with(density) {
                                            DpOffset(
                                                change.position.x.toDp(),
                                                change.position.y.toDp(),
                                            )
                                        }
                                }
                                menuAnchor = InstanceMenuAnchor.Pointer
                                menuOpen = true
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
                .hoverable(hoverSource)
                .clickable(onClick = onSelect),
            shape = shape,
            color =
                if (hovered)
                    lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.primary, .04f)
                else MaterialTheme.colorScheme.surface,
            border =
                BorderStroke(
                    1.dp,
                    when {
                        selected -> MaterialTheme.colorScheme.primary.copy(alpha = .7f)
                        hovered -> MaterialTheme.colorScheme.primary.copy(alpha = .45f)
                        else -> hairline()
                    },
                ),
        ) {
            Column(Modifier.fillMaxSize().padding(14.dp)) {
                InstanceArtwork(instance, actions, Modifier.size(32.dp))
                Spacer(Modifier.height(10.dp))
                Text(
                    instance.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (instance.broken) "无法识别 · " + instance.brokenReason
                    else instance.gameVersion + " · " + instance.loader.title,
                    fontSize = 12.sp,
                    color =
                        if (instance.broken) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 弹性间隔吃掉剩余高度：无论名称长短，操作行都贴在卡片底部，跨卡片对齐。
                Spacer(Modifier.weight(1f))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    InstanceTextAction(
                        "开始游戏",
                        onLaunch,
                        enabled = !busy && !instance.broken,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    InstanceTextAction(
                        if (instance.favorite) "已收藏" else "收藏",
                        onFavorite,
                        tint =
                            if (instance.favorite) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    InstanceTextAction("设置", onManage)
                    InstanceTextAction("删除", onDelete, tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        // 网格卡没有空间再放一个图标按钮：右键菜单与列表行是同一份内容。
        InstanceContextMenu(
            expanded = menuOpen,
            offset = menuOffset,
            instance = instance,
            busy = busy,
            onDismiss = { menuOpen = false },
            onLaunch = onLaunch,
            onRename = onRename,
            onCopy = onCopy,
            onOpenFolder = { actions.run { actions.controller.openFolder(instance.id) } },
            onExport = onExport,
            onDelete = onDelete,
        )
    }
}

@Composable
private fun InstanceLoadingState(modifier: Modifier) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            Modifier.size(28.dp),
            strokeWidth = 2.5.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "正在读取实例…",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InstanceErrorState(modifier: Modifier, message: String, onRetry: () -> Unit) {
    Column(
        modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.ErrorOutline,
            null,
            Modifier.size(32.dp),
            MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(10.dp))
        Text("无法读取游戏目录", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            message,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(12.dp))
        SecondaryButton(onRetry) { Text("重试", fontSize = 13.sp) }
    }
}

@Composable
private fun InstanceEmptyState(
    modifier: Modifier,
    noInstance: Boolean,
    busy: Boolean,
    onNew: () -> Unit,
    onClearFilter: () -> Unit,
) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        EmptyState(
            if (noInstance) "还没有游戏实例" else "没有匹配的实例",
            if (noInstance) "安装一个新版本，从游戏目录导入，或直接新建实例。"
            else "调整关键词、版本、加载器或收藏筛选后再试。",
            Icons.Outlined.Widgets,
        )
        if (noInstance)
            PrimaryButton(
                "安装第一个版本",
                Icons.Outlined.Add,
                enabled = !busy,
                onClick = onNew,
            )
        else SecondaryButton(onClearFilter) { Text("清除筛选", fontSize = 13.sp) }
    }
}

/** 列表行信息：版本 · 加载器 · 最后游玩 · 占用大小；选中显示备注时把备注接在末尾（先被省略）。 */
private fun instanceMetaLine(
    instance: GameInstance,
    sizeBytes: Long?,
    showNotes: Boolean,
): String {
    val note =
        if (showNotes && instance.notes.isNotBlank())
            " · " + instance.notes.replace('\n', ' ')
        else ""
    return instance.gameVersion.ifBlank { "版本未知" } +
        " · " +
        (if (instance.broken) "无法识别" else instance.loader.title) +
        " · " +
        lastPlayedText(instance.lastPlayed) +
        " · " +
        instanceSizeText(sizeBytes) +
        note
}

/** 最后游玩：相对时间，例如「3 天前」；从未游玩（或时间无法解析）显示「未游玩」。 */
private fun lastPlayedText(lastPlayed: String?): String {
    val played = lastPlayed?.takeIf { it.isNotBlank() } ?: return "未游玩"
    val instant = runCatching { Instant.parse(played) }.getOrNull() ?: return "未游玩"
    val seconds = Duration.between(instant, Instant.now()).seconds.coerceAtLeast(0)
    return when {
        seconds < 60 -> "刚刚"
        seconds < 3600 -> "${seconds / 60} 分钟前"
        seconds < 86400 -> "${seconds / 3600} 小时前"
        else -> "${seconds / 86400} 天前"
    }
}

/** 占用文案：尚未统计完成时显示「统计中…」，目录读不到时如实显示「占用未知」。 */
private fun instanceSizeText(bytes: Long?): String =
    when {
        bytes == null -> "统计中…"
        bytes < 0 -> "占用未知"
        bytes >= 1073741824L -> "%.1f GB".format(bytes / 1073741824f)
        bytes >= 1048576L -> "%.1f MB".format(bytes / 1048576f)
        bytes >= 1024L -> "%.0f KB".format(bytes / 1024f)
        else -> bytes.toString() + " B"
    }

/** 实例目录占用：只在 IO 线程调用；不可读的文件按 0 计，目录不存在返回 null。 */
private fun instanceDirectoryBytes(path: Path): Long? =
    runCatching {
            if (!Files.isDirectory(path)) null
            else
                Files.walk(path).use { files ->
                    files
                        .filter { Files.isRegularFile(it) }
                        .mapToLong { file -> runCatching { Files.size(file) }.getOrDefault(0L) }
                        .sum()
                }
        }
        .getOrNull()

/** 加载器筛选的固定显示次序（不含始终在前的「全部」与末尾的「无法识别」分组）。 */
private val LoaderFilterOrder = listOf("原版", "Forge", "NeoForge", "Fabric", "Quilt")

/** 版本筛选按数字段从新到旧排列：26.3 在 1.21.8 之前，无法解析的段按 0 处理。 */
private val instanceVersionOrder =
    Comparator<String> { left, right ->
        val first = versionSegments(left)
        val second = versionSegments(right)
        var result = 0
        for (index in 0 until maxOf(first.size, second.size)) {
            val diff = first.getOrElse(index) { 0 }.compareTo(second.getOrElse(index) { 0 })
            if (diff != 0) {
                result = -diff
                break
            }
        }
        if (result != 0) result else left.compareTo(right)
    }

private fun versionSegments(version: String): List<Int> =
    version
        .split('.', '-', '_', ' ')
        .map { it.takeWhile { char -> char.isDigit() }.toIntOrNull() ?: 0 }

/** 只读地检查目录是否还存在、是否为文件夹、是否可读；在 IO 线程调用。 */
private fun probeGameDirectory(directory: GameDirectory): String = runCatching {
    val path = Path.of(directory.path)
    when {
        !Files.exists(path) -> "找不到游戏目录：" + directory.path
        !Files.isDirectory(path) -> "这不是文件夹：" + directory.path
        !Files.isReadable(path) -> "没有读取权限：" + directory.path
        else -> ""
    }
}
    .getOrElse { "无法读取游戏目录：" + (it.message ?: directory.path) }