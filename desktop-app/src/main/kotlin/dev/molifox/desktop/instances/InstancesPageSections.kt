// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 实例列表页的页头、筛选栏、工具面板、内容区与对话框，由 InstancesPage 组装。

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.GameInstance
import dev.molifox.core.LauncherState
import dev.molifox.core.i18n.Strings
import dev.molifox.desktop.CompactSearchField
import dev.molifox.desktop.CompactSelect
import dev.molifox.desktop.CopyInstanceDialog
import dev.molifox.desktop.GameDirectoryControls
import dev.molifox.desktop.LocalAnimationsEnabled
import dev.molifox.desktop.LocalStrings
import dev.molifox.desktop.Motion
import dev.molifox.desktop.Page
import dev.molifox.desktop.Panel
import dev.molifox.desktop.PrimaryButton
import dev.molifox.desktop.SecondaryButton
import dev.molifox.desktop.SmallIconButton
import dev.molifox.desktop.UiActions
import dev.molifox.desktop.tr

/** 子页页头：返回箭头 + 18sp 标题，右侧是「命中 / 总数」计数。 */
@Composable
internal fun InstancesHeader(
    versions: List<GameInstance>,
    all: List<GameInstance>,
    onBack: () -> Unit,
) {
    val strings = LocalStrings.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SmallIconButton(Icons.Outlined.ArrowBack, strings.t("pages.instances.back"), onClick = onBack)
        Spacer(Modifier.width(8.dp))
        Text(
            strings.t("pages.instances.title"),
            Modifier.weight(1f),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Text(
            if (versions.size != all.size) {
                strings.t(
                    "pages.instances.countFiltered",
                    versions.size,
                    all.size,
                    versions.count { it.installed },
                )
            } else {
                strings.t("pages.instances.count", all.size, all.count { it.installed })
            },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 单行筛选栏：搜索占剩余宽度，三个下拉与收藏 / 视图按钮固定宽度。 */
@Composable
internal fun InstancesFilterBar(
    query: String,
    onQueryChange: (String) -> Unit,
    versionFilter: String,
    versionOptions: List<Pair<String, String>>,
    onVersionChange: (String) -> Unit,
    loaderFilter: String,
    loaderOptions: List<Pair<String, String>>,
    onLoaderChange: (String) -> Unit,
    sort: String,
    onSortChange: (String) -> Unit,
    favorites: Boolean,
    onToggleFavorites: () -> Unit,
    grid: Boolean,
    onShowList: () -> Unit,
    onShowGrid: () -> Unit,
) {
    val strings = LocalStrings.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CompactSearchField(
            query,
            onQueryChange,
            Modifier.weight(1f),
            placeholder = strings.t("pages.instances.search.placeholder"),
        )
        CompactSelect(versionFilter, versionOptions, onVersionChange, Modifier.width(132.dp))
        CompactSelect(loaderFilter, loaderOptions, onLoaderChange, Modifier.width(168.dp))
        CompactSelect(
            sort,
            instanceSortOptions(strings),
            onSortChange,
            Modifier.width(144.dp),
        )
        CompactToggleButton(
            selected = favorites,
            icon = if (favorites) Icons.Outlined.Star else Icons.Outlined.StarOutline,
            label = strings.t("pages.instances.favorite"),
            onClick = onToggleFavorites,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CompactToggleButton(
                selected = !grid,
                icon = Icons.Outlined.ViewList,
                label = null,
                description = strings.t("pages.instances.view.list"),
                onClick = onShowList,
            )
            CompactToggleButton(
                selected = grid,
                icon = Icons.Outlined.GridView,
                label = null,
                description = strings.t("pages.instances.view.grid"),
                onClick = onShowGrid,
            )
        }
    }
}

/** 排序下拉项；取值是持久化的排序 key，显示名随语言变化。 */
private fun instanceSortOptions(strings: Strings): List<Pair<String, String>> =
    listOf(
        "recent" to strings.t("pages.instances.sort.recent"),
        "name" to strings.t("pages.instances.sort.name"),
        "created" to strings.t("pages.instances.sort.created"),
    )

/** 目录工具面板与「新建实例 / 导入整合包」按钮。 */
@Composable
internal fun InstancesToolbar(
    state: LauncherState,
    actions: UiActions,
    busy: Boolean,
    onNew: () -> Unit,
) {
    val strings = LocalStrings.current
    Panel(Modifier.fillMaxWidth(), padding = 12) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.weight(1f)) { GameDirectoryControls(state, actions) }
            PrimaryButton(
                strings.t("pages.instances.new"),
                Icons.Outlined.Add,
                enabled = !busy,
                onClick = onNew,
            )
            SecondaryButton(
                { actions.importPack() },
                enabled = !busy,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            ) {
                Text(strings.t("pages.instances.import"), fontSize = 13.sp)
            }
        }
    }
}

/** 内容区按目录可读性、读取中、空列表与正常列表分派。 */
@Composable
internal fun ColumnScope.InstancesContent(
    probe: String?,
    versions: List<GameInstance>,
    all: List<GameInstance>,
    pendingRead: Boolean,
    busy: Boolean,
    grid: Boolean,
    actions: UiActions,
    state: LauncherState,
    showNotes: Boolean,
    sizeRevision: Int,
    onNew: () -> Unit,
    onClearFilter: () -> Unit,
    onRefresh: () -> Unit,
    onManage: (String) -> Unit,
    onDelete: (GameInstance) -> Unit,
    onRename: (GameInstance) -> Unit,
    onCopy: (GameInstance) -> Unit,
    onExport: (GameInstance) -> Unit,
    navigate: (Page) -> Unit,
) {
    val issue = probe
    when {
        // 目录可读时 probeGameDirectory 返回空串，不能当成错误态，否则实例列表永远不会显示。
        issue != null && issue.isNotBlank() ->
            InstanceErrorState(Modifier.weight(1f).fillMaxWidth(), issue, onRefresh)
        versions.isEmpty() && pendingRead ->
            InstanceLoadingState(Modifier.weight(1f).fillMaxWidth())
        versions.isEmpty() ->
            InstanceEmptyState(
                Modifier.weight(1f).fillMaxWidth(),
                noInstance = all.isEmpty(),
                busy = busy,
                onNew = onNew,
                onClearFilter = onClearFilter,
            )
        else ->
            Box(Modifier.weight(1f).fillMaxWidth()) {
                InstancesListOrGrid(
                    versions = versions,
                    actions = actions,
                    state = state,
                    busy = busy,
                    showNotes = showNotes,
                    sizeRevision = sizeRevision,
                    grid = grid,
                    onManage = onManage,
                    onDelete = onDelete,
                    onRename = onRename,
                    onCopy = onCopy,
                    onExport = onExport,
                    navigate = navigate,
                )
            }
    }
}

/** 列表与网格共用同一组回调，交叉淡入或直接切换。 */
@Composable
private fun InstancesListOrGrid(
    versions: List<GameInstance>,
    actions: UiActions,
    state: LauncherState,
    busy: Boolean,
    showNotes: Boolean,
    sizeRevision: Int,
    grid: Boolean,
    onManage: (String) -> Unit,
    onDelete: (GameInstance) -> Unit,
    onRename: (GameInstance) -> Unit,
    onCopy: (GameInstance) -> Unit,
    onExport: (GameInstance) -> Unit,
    navigate: (Page) -> Unit,
) {
    val selectedInstanceId = state.data.selectedInstanceId
    val list: @Composable () -> Unit = {
        InstanceListBody(
            versions,
            actions,
            selectedInstanceId,
            busy,
            showNotes,
            sizeRevision,
            // 开始页的「版本选择」把这里当作挑选页：选中后立刻回开始页，
            // 「当前游戏」卡与按钮文字跟着切到新实例。
            onSelect = {
                actions.run { actions.controller.selectInstance(it.id) }
                navigate(Page.Home)
            },
            onManage = { onManage(it) },
            onLaunch = { launchInstance(it, actions, state, navigate) },
            onDelete = { onDelete(it) },
            onFavorite = {
                actions.run { actions.controller.toggleFavorite(it) }
            },
            onRename = { onRename(it) },
            onCopy = { onCopy(it) },
            onExport = { onExport(it) },
        )
    }
    val cards: @Composable () -> Unit = {
        InstanceGridBody(
            versions,
            actions,
            selectedInstanceId,
            busy,
            // 网格视图与列表同一套挑选语义：选中后回到开始页。
            onSelect = {
                actions.run { actions.controller.selectInstance(it.id) }
                navigate(Page.Home)
            },
            onManage = { onManage(it) },
            onLaunch = { launchInstance(it, actions, state, navigate) },
            onDelete = { onDelete(it) },
            onFavorite = {
                actions.run { actions.controller.toggleFavorite(it) }
            },
            onRename = { onRename(it) },
            onCopy = { onCopy(it) },
            onExport = { onExport(it) },
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

/** 行内操作打开的重命名/复制对话框；导出改为根级独立页面。 */
@Composable
internal fun InstancePageDialogs(
    renaming: GameInstance?,
    copying: GameInstance?,
    actions: UiActions,
    onRenameDismiss: () -> Unit,
    onCopyDismiss: () -> Unit,
) {
    renaming?.let { instance ->
        RenameInstanceDialog(instance, actions) { onRenameDismiss() }
    }
    copying?.let { instance -> CopyInstanceDialog(instance, actions) { onCopyDismiss() } }
}

/** 启动入口：无法识别先提示，未安装先修复，没有账户先要求添加。 */
private fun launchInstance(
    instance: GameInstance,
    actions: UiActions,
    state: LauncherState,
    navigate: (Page) -> Unit,
) {
    if (instance.broken) {
        actions.controller.store.notify(
            tr("pages.instances.notify.broken", instance.name, instance.brokenReason),
        )
        return
    }
    if (!instance.installed) {
        actions.run { actions.controller.repair(instance.id) }
        actions.taskQueued()
    } else if (state.data.activeAccountId == null) {
        actions.controller.store.notify(tr("pages.instances.notify.noAccount"))
        navigate(Page.Home)
    } else {
        actions.run {
            actions.controller.selectInstance(instance.id)
            actions.controller.launch(instance.id)
        }
        actions.taskQueued()
    }
}
