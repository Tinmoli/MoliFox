// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责实例列表页：页头计数、筛选栏、目录工具面板与列表 / 网格切换。

package dev.molifox.desktop.instances

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.GameInstance
import dev.molifox.core.JobStatus
import dev.molifox.core.LauncherState
import dev.molifox.desktop.CompactSearchField
import dev.molifox.desktop.CompactSelect
import dev.molifox.desktop.CopyInstanceDialog
import dev.molifox.desktop.ExportMrpackDialog
import dev.molifox.desktop.GameDirectoryControls
import dev.molifox.desktop.LocalAnimationsEnabled
import dev.molifox.desktop.Motion
import dev.molifox.desktop.Page
import dev.molifox.desktop.Panel
import dev.molifox.desktop.PrimaryButton
import dev.molifox.desktop.SecondaryButton
import dev.molifox.desktop.SmallIconButton
import dev.molifox.desktop.UiActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 加载器筛选里「无法识别」实例单独成组：它不冒充任何加载器，也不参与按加载器的筛选。 */
private const val BROKEN_LOADER_FILTER = "无法识别"

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
    var sizeRevision by remember { mutableIntStateOf(0) }
    // 进入/回到实例列表时做一次廉价的目录签名检查；新增、删除或 JSON 改坏都会在这里出现。
    LaunchedEffect(Unit) {
        withContext(
            Dispatchers.IO,
        ) { runCatching { actions.controller.refreshInstancesIfChanged() } }
        // 重新扫描后可见行重新统计，各行结果随组件离开释放。
        sizeRevision++
    }
    // 安装 / 修复 / 删除等任务结束后目录内容变化；任务从运行回到空闲时再作废一次，
    // 避免行里长期显示任务开始前的占用。
    val runningTasks = state.jobs.count { it.status == JobStatus.Running }
    var previousRunningTasks by remember { mutableIntStateOf(runningTasks) }
    LaunchedEffect(runningTasks) {
        if (runningTasks == 0 && previousRunningTasks > 0) sizeRevision++
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
                all
                    .map { it.gameVersion }
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
                if (all.any { it.broken }) add(BROKEN_LOADER_FILTER to "加载器：无法识别")
            }
        }
    // 实例被删除或目录切换后，当前筛选值可能已经不存在：自动回到「全部」，避免下拉显示内部取值。
    LaunchedEffect(versionOptions) {
        if (versionOptions.none { it.first == versionFilter }) versionFilter = ""
    }
    LaunchedEffect(loaderOptions) {
        if (loaderOptions.none { it.first == loaderFilter }) loaderFilter = ""
    }
    val filtered =
        all.filter { instance ->
            (!favorites || instance.favorite) &&
                // 搜索只看名称，大小写不敏感且即时生效。
                (query.isBlank() || instance.name.contains(query.trim(), ignoreCase = true)) &&
                (versionFilter.isBlank() || instance.gameVersion == versionFilter) &&
                (
                    loaderFilter.isBlank() ||
                        if (loaderFilter == BROKEN_LOADER_FILTER) {
                            instance.broken
                        } else {
                            !instance.broken && instance.loader.title == loaderFilter
                        }
                )
        }
    val versions =
        when (sort) {
            "name" -> filtered.sortedBy { it.name.lowercase() }
            "created" -> filtered.sortedByDescending { it.createdAt }
            else ->
                filtered.sortedWith(
                    compareByDescending<GameInstance> { preferences.favoritesFirst && it.favorite }
                        .thenByDescending { it.lastPlayed ?: it.createdAt },
                )
        }

    fun launch(instance: GameInstance) {
        if (instance.broken) {
            actions.controller.store.notify(
                "「" + instance.name + "」无法识别：" + instance.brokenReason,
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
            if (directory != null) {
                value = withContext(Dispatchers.IO) { probeGameDirectory(directory) }
            }
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
            if (versions.size != all.size) {
                versions.size.toString() + " / " + all.size + " 个实例 · " +
                    versions.count { it.installed } + " 个已安装"
            } else {
                all.size.toString() + " 个实例 · " + all.count { it.installed } + " 个已安装"
            },
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
                sizeRevision++
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
                        sizeRevision,
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
                if (LocalAnimationsEnabled.current) {
                    Crossfade(
                        grid,
                        Modifier.fillMaxSize(),
                        animationSpec = tween(Motion.NORMAL),
                        label = "versions-view",
                    ) { showGrid ->
                        if (showGrid) cards() else list()
                    }
                } else if (grid) {
                    cards()
                } else {
                    list()
                }
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

/** 加载器筛选的固定显示次序（不含始终在前的「全部」与末尾的「无法识别」分组）。 */
private val LoaderFilterOrder = listOf("原版", "Forge", "NeoForge", "Fabric", "Quilt")
