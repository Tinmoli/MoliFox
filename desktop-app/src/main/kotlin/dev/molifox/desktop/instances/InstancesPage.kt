// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责实例列表页：页头计数、筛选栏、目录工具面板与列表 / 网格切换。

package dev.molifox.desktop.instances

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.molifox.core.DownloadJob
import dev.molifox.core.GameInstance
import dev.molifox.core.JobStatus
import dev.molifox.core.LauncherState
import dev.molifox.desktop.Page
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
    val filtered = filteredInstances(all, favorites, query, versionFilter, loaderFilter)
    val versions = sortedInstances(filtered, sort, preferences.favoritesFirst)
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
    val pendingRead = pendingInstallOrRepair(state.jobs)

    // 子页页头与账户列表一致：左上返回箭头 + 18sp 标题；实例计数留在同一行右侧，
    // 有筛选时按「命中 / 总数」显示，例如「3 / 8 个实例」。
    InstancesHeader(versions, all, onBack = { navigate(Page.Home) })
    Spacer(Modifier.height(12.dp))
    // 单行筛选栏：搜索占剩余宽度，三个下拉与收藏 / 视图按钮固定宽度；1000dp 窗口下仍是一行。
    InstancesFilterBar(
        query = query,
        onQueryChange = { query = it },
        versionFilter = versionFilter,
        versionOptions = versionOptions,
        onVersionChange = { versionFilter = it },
        loaderFilter = loaderFilter,
        loaderOptions = loaderOptions,
        onLoaderChange = { loaderFilter = it },
        sort = sort,
        onSortChange = { sort = it },
        favorites = favorites,
        onToggleFavorites = { favorites = !favorites },
        grid = grid,
        onShowList = { grid = false },
        onShowGrid = { grid = true },
    )
    Spacer(Modifier.height(12.dp))
    InstancesToolbar(state, actions, busy, onNew)
    Spacer(Modifier.height(12.dp))
    InstancesContent(
        probe = probe,
        versions = versions,
        all = all,
        pendingRead = pendingRead,
        busy = busy,
        grid = grid,
        actions = actions,
        state = state,
        showNotes = preferences.showNotes,
        sizeRevision = sizeRevision,
        onNew = onNew,
        onClearFilter = clearFilters,
        onRefresh = {
            probeToken++
            sizeRevision++
            actions.run { actions.controller.refreshGameDirectory() }
        },
        onManage = onManage,
        onDelete = onDelete,
        onRename = { renaming = it },
        onCopy = { copying = it },
        onExport = { actions.navigation.openExport(it.id) },
        navigate = navigate,
    )
    InstancePageDialogs(
        renaming = renaming,
        copying = copying,
        actions = actions,
        onRenameDismiss = { renaming = null },
        onCopyDismiss = { copying = null },
    )
}

/** 收藏、搜索、版本与加载器筛选同时生效，判定顺序与原内联布尔链一致。 */
private fun filteredInstances(
    all: List<GameInstance>,
    favorites: Boolean,
    query: String,
    versionFilter: String,
    loaderFilter: String,
): List<GameInstance> =
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

/** 排序：名称、创建时间与其他（收藏优先 + 最近游玩）。 */
private fun sortedInstances(
    filtered: List<GameInstance>,
    sort: String,
    favoritesFirst: Boolean,
): List<GameInstance> =
    when (sort) {
        "name" -> filtered.sortedBy { it.name.lowercase() }
        "created" -> filtered.sortedByDescending { it.createdAt }
        else ->
            filtered.sortedWith(
                compareByDescending<GameInstance> { favoritesFirst && it.favorite }
                    .thenByDescending { it.lastPlayed ?: it.createdAt },
            )
    }

/** 安装 / 修复任务仍在运行时，空列表应显示为读取中而不是「没有实例」。 */
private fun pendingInstallOrRepair(jobs: List<DownloadJob>): Boolean =
    jobs.any {
        it.status == JobStatus.Running &&
            (it.title.startsWith("安装") || it.title.startsWith("修复"))
    }

/** 加载器筛选的固定显示次序（不含始终在前的「全部」与末尾的「无法识别」分组）。 */
private val LoaderFilterOrder = listOf("原版", "Forge", "NeoForge", "Fabric", "Quilt")
