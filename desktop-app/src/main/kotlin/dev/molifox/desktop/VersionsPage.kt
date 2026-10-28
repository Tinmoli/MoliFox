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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 实例页：顶部工具行（目录 + 新建 / 导入）、搜索行（搜索 / 收藏 / 排序 / 列表-网格）， 列表行 52dp 为默认视图，网格卡仅在切换到网格时出现。删除的二次确认仍在
 * Main.kt。
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
    val preferences = state.data.settings.pages.versions
    var grid by remember(preferences.view) { mutableStateOf(preferences.view == "grid") }
    var favorites by remember { mutableStateOf(false) }
    var sort by remember(preferences.sort) { mutableStateOf(preferences.sort) }
    val busy = actions.controller.busy
    // 「有没有游戏」以 instances 为准：没有生效目录（自动发现尚未选中、目录刚被移除）
    // 时列出全部实例，避免已经识别到版本却显示成空列表。
    val activeDirectoryId = state.data.activeGameDirectoryId
    val all =
        state.data.instances.filter {
            activeDirectoryId == null || it.gameDirectoryId == activeDirectoryId
        }
    val filtered = all.filter {
        (!favorites || it.favorite) &&
            (it.name + " " + it.gameVersion + " " + it.loader.title + " " + it.notes).contains(
                query,
                true,
            )
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
    // 右上角显示实例计数和列表操作。
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            all.size.toString() + " 个实例 · " + all.count { it.installed } + " 个已安装",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CompactSearchField(
            query,
            { query = it },
            Modifier.weight(1f),
            placeholder = "搜索名称、版本或备注",
        )
        CompactToggleButton(
            selected = favorites,
            icon = if (favorites) Icons.Outlined.Star else Icons.Outlined.StarOutline,
            label = "收藏",
            onClick = { favorites = !favorites },
        )
        CompactSelect(
            sort,
            listOf("recent" to "最近游玩", "name" to "按名称", "created" to "最近添加"),
            { sort = it },
            Modifier.width(148.dp),
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
    val issue = probe
    when {
        // 目录可读时 probeGameDirectory 返回空串，不能当成错误态，否则实例列表永远不会显示。
        issue != null && issue.isNotBlank() ->
            InstanceErrorState(Modifier.weight(1f).fillMaxWidth(), issue) {
                probeToken++
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
                onClearFilter = {
                    query = ""
                    favorites = false
                },
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
                        onSelect = { actions.run { actions.controller.selectInstance(it.id) } },
                        onManage = { onManage(it) },
                        onLaunch = { launch(it) },
                        onDelete = { onDelete(it) },
                        onFavorite = {
                            actions.run { actions.controller.toggleFavorite(it) }
                        },
                    )
                }
                val cards: @Composable () -> Unit = {
                    InstanceGridBody(
                        versions,
                        actions,
                        state.data.selectedInstanceId,
                        busy,
                        onSelect = { actions.run { actions.controller.selectInstance(it.id) } },
                        onManage = { onManage(it) },
                        onLaunch = { launch(it) },
                        onFavorite = {
                            actions.run { actions.controller.toggleFavorite(it) }
                        },
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

/** 图标按钮：30dp 触区、16dp 图标，悬停淡入，可带一层主题色底（启动按钮用）。 */
@Composable
private fun CompactIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    container: Color? = null,
) {
    val shape = RoundedCornerShape(Metrics.innerRadius)
    Box(
        Modifier.size(Metrics.iconButton)
            .clip(shape)
            .background(container ?: Color.Transparent, shape)
            .hoverHighlight(shape, enabled = enabled && container == null)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            label,
            Modifier.size(16.dp),
            tint = if (enabled) tint else tint.copy(alpha = .38f),
        )
    }
}

/** 列表视图：行高 52dp、圆角 6、悬停淡入，行尾只有启动 / 收藏 / 更多三个入口。 */
@Composable
private fun InstanceListBody(
    versions: List<GameInstance>,
    actions: UiActions,
    selectedId: String?,
    busy: Boolean,
    showNotes: Boolean,
    onSelect: (GameInstance) -> Unit,
    onManage: (String) -> Unit,
    onLaunch: (GameInstance) -> Unit,
    onDelete: (GameInstance) -> Unit,
    onFavorite: (String) -> Unit,
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
                    onSelect = { onSelect(instance) },
                    onManage = { onManage(instance.id) },
                    onLaunch = { onLaunch(instance) },
                    onDelete = { onDelete(instance) },
                    onFavorite = { onFavorite(instance.id) },
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
    onSelect: () -> Unit,
    onManage: () -> Unit,
    onLaunch: () -> Unit,
    onDelete: () -> Unit,
    onFavorite: () -> Unit,
) {
    val shape = RoundedCornerShape(Metrics.cardRadius)
    var menu by remember { mutableStateOf(false) }
    Surface(
        Modifier.fillMaxWidth().height(Metrics.rowHeightWithDetail).clickable(onClick = onSelect),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border =
            BorderStroke(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .6f) else hairline(),
            ),
    ) {
        Row(
            Modifier.fillMaxSize().hoverHighlight(shape).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InstanceArtwork(instance, actions, Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
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
                    if (!instance.installed) Tag("待修复", MaterialTheme.colorScheme.error)
                }
                Text(
                    instanceSubtitle(instance, showNotes),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            CompactIconButton(
                Icons.Outlined.PlayArrow,
                "启动 " + instance.name,
                onLaunch,
                enabled = !busy,
                tint = MaterialTheme.colorScheme.primary,
                container = MaterialTheme.colorScheme.primaryContainer,
            )
            CompactIconButton(
                if (instance.favorite) Icons.Outlined.Star else Icons.Outlined.StarOutline,
                "收藏 " + instance.name,
                onFavorite,
                tint =
                    if (instance.favorite) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box {
                CompactIconButton(Icons.Outlined.MoreHoriz, "更多操作", { menu = true })
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(
                        { Text("管理实例", fontSize = 13.sp) },
                        {
                            menu = false
                            onManage()
                        },
                    )
                    DropdownMenuItem(
                        { Text("删除实例", fontSize = 13.sp) },
                        {
                            menu = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

/** 网格视图：最小高 168，悬停 1dp 主题色边 + 轻微底色。 */
@Composable
private fun InstanceGridBody(
    versions: List<GameInstance>,
    actions: UiActions,
    selectedId: String?,
    busy: Boolean,
    onSelect: (GameInstance) -> Unit,
    onManage: (String) -> Unit,
    onLaunch: (GameInstance) -> Unit,
    onFavorite: (String) -> Unit,
) {
    LazyVerticalGrid(
        GridCells.Adaptive(220.dp),
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
                onFavorite = { onFavorite(instance.id) },
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
    onFavorite: () -> Unit,
) {
    val shape = RoundedCornerShape(Metrics.cardRadius)
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()
    Surface(
        Modifier.fillMaxWidth()
            .heightIn(min = 168.dp)
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
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InstanceArtwork(instance, actions, Modifier.size(32.dp))
                Spacer(Modifier.weight(1f))
                CompactIconButton(
                    if (instance.favorite) Icons.Outlined.Star else Icons.Outlined.StarOutline,
                    "收藏 " + instance.name,
                    onFavorite,
                    tint =
                        if (instance.favorite) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                instance.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                instance.gameVersion + " · " + instance.loader.title,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onManage, contentPadding = PaddingValues(horizontal = 2.dp)) {
                    Text("管理", fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                CompactIconButton(
                    Icons.Outlined.PlayArrow,
                    "启动 " + instance.name,
                    onLaunch,
                    enabled = !busy,
                    tint = MaterialTheme.colorScheme.primary,
                    container = MaterialTheme.colorScheme.primaryContainer,
                )
            }
        }
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
            if (noInstance) "还没有游戏实例" else "没有符合条件的版本",
            if (noInstance) "安装一个新版本，从游戏目录导入，或直接新建实例。" else "试试其他关键词，或关闭收藏筛选。",
            Icons.Outlined.Widgets,
        )
        if (noInstance)
            PrimaryButton(
                "安装第一个版本",
                Icons.Outlined.Add,
                enabled = !busy,
                onClick = onNew,
            )
        else SecondaryButton(onClearFilter) { Text("清除搜索与筛选", fontSize = 13.sp) }
    }
}

private fun instanceSubtitle(instance: GameInstance, showNotes: Boolean): String =
    instance.gameVersion +
        " · " +
        instance.loader.title +
        if (showNotes && instance.notes.isNotBlank()) "  /  " + instance.notes.replace('\n', ' ')
        else ""

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
