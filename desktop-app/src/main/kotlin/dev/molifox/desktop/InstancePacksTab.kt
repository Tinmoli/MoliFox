// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 展示实例资源包或光影包列表及其操作状态。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.DownloadJob
import dev.molifox.core.GameInstance
import dev.molifox.core.JobStatus
import dev.molifox.core.LocalPackEntry
import dev.molifox.core.PackFolder
import dev.molifox.core.PackUpdateItem
import dev.molifox.core.PackUpdateStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * 资源包 / 光影页签：搜索 + 刷新 + 导入 + 打开文件夹；更新检查由左栏「检查更新」统一触发，结果按页签显示。行列出目录里的 zip 与文件夹。
 *
 * 没有启用/禁用开关：资源包在游戏内选择，光影由 Iris / OptiFine 读取。支持把 zip 或文件夹直接拖进页面导入。
 */
@Composable
internal fun InstancePackTab(
    instance: GameInstance,
    actions: UiActions,
    updates: InstanceUpdateCenter,
    folder: PackFolder,
    modifier: Modifier,
) {
    val strings = LocalStrings.current
    var entries by remember(
        instance.id,
        folder,
    ) { mutableStateOf<List<LocalPackEntry>>(emptyList()) }
    var query by remember(instance.id, folder) { mutableStateOf("") }
    var refresh by remember(instance.id, folder) { mutableIntStateOf(0) }
    var loading by remember(instance.id, folder) { mutableStateOf(true) }
    var error by remember(instance.id, folder) { mutableStateOf<String?>(null) }
    var actionError by remember(instance.id, folder) { mutableStateOf<String?>(null) }
    var chip by remember(instance.id, folder) { mutableStateOf(ContentChip.All) }
    var deleting by remember { mutableStateOf<List<LocalPackEntry>>(emptyList()) }
    var renaming by remember { mutableStateOf<LocalPackEntry?>(null) }
    var detailed by remember { mutableStateOf<LocalPackEntry?>(null) }
    var watchingJob by remember(instance.id, folder) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val liveState by actions.controller.state.collectAsState()
    val busy = rememberInstanceBusy(actions, instance.id)
    val checking = updates.packsChecking
    val report = updates.packs
    val reportItems = report?.items(folder).orEmpty()
    val visibleItems = visiblePackItems(entries, reportItems)
    val itemsByFile = reportItems.associateBy { it.filename }
    val updatable = visibleItems.count { it.status == PackUpdateStatus.Updatable }
    val actionable = visibleItems.filter { it.batchApplicable }
    val uncertain = visibleItems.count { it.status == PackUpdateStatus.Unknown }
    val statusText = packStatusText(checking, report != null, updatable, uncertain)
    LaunchedEffect(instance.id, folder, refresh, liveState.jobs.map { it.id + ":" + it.status }) {
        loading = true
        error = null
        loadPackEntries(actions, instance.id, folder)
            .onSuccess { entries = it }
            .onFailure { failure ->
                entries = emptyList()
                error = failure.message
            }
        loading = false
    }
    // 导入与删除都是任务中心里的后台任务；结束后刷新列表，失败原因留在工具行下方。
    LaunchedEffect(watchingJob, liveState.jobs.map { it.id + ":" + it.status }) {
        watchPackJob(
            watchingJob,
            liveState.jobs,
            onFailed = { actionError = it },
            onCleared = { watchingJob = null },
            onFinished = { refresh++ },
        )
    }
    val startImport: (List<Path>) -> Unit = { chosen ->
        startPackImport(actions, instance.id, folder, chosen, { watchingJob = it }) { actionError = it }
    }
    PackDialogs(
        deleting = deleting,
        renaming = renaming,
        detailed = detailed,
        folder = folder,
        instance = instance,
        actions = actions,
        updates = updates,
        itemsByFile = itemsByFile,
        onDismissDelete = { deleting = emptyList() },
        onDismissRename = { renaming = null },
        onDismissDetail = { detailed = null },
        onError = { actionError = it },
        onRefresh = { refresh++ },
    )
    Column(
        modifier
            .fillMaxHeight()
            .contentDropTarget(
                enabled = !busy,
                hint = strings.t("deskC.drop.hint"),
                onError = { actionError = it },
            ) { dropped -> startImport(dropped) },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ContentToolbar(
            flexible = {
                ToolbarSearchField(
                    query,
                    { query = it },
                    strings.t("deskC.search.placeholder", folder.title),
                )
            },
            refresh =
                ToolbarAction(
                    strings.t("deskC.toolbar.refresh"),
                    icon = Icons.Outlined.Refresh,
                    enabled = !busy,
                ) { refresh++ },
            importAction =
                ToolbarAction(strings.t("deskC.toolbar.import"), icon = Icons.Outlined.FileDownload, enabled = !busy) {
                    actions.run {
                        val chosen =
                            actions.choose(
                                strings.t("deskC.toolbar.importDialogTitle", folder.title),
                                multiple = true,
                                extensions = listOf("zip"),
                            )
                        withContext(Dispatchers.Swing) { startImport(chosen) }
                    }
                },
            openFolder =
                ToolbarAction(
                    strings.t("deskC.toolbar.openFolder"),
                    icon = Icons.Outlined.FolderOpen,
                    enabled = !busy,
                ) {
                    actions.run { actions.controller.openFolder(instance.id, folder.directory) }
                },
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ContentChipRow(
                selected = chip,
                visible = listOf(ContentChip.All, ContentChip.Updatable, ContentChip.NoUpdate),
            ) { chip = it }
            PackStatusLabel(statusText, updatable)
            MoliTextButton(
                {
                    watchingJob = actions.controller.updatePacks(instance.id, actionable)
                },
                enabled = !busy && !checking && actionable.isNotEmpty(),
            ) {
                Text(strings.t("pages.updateBatch.oneClick.count", actionable.size), fontSize = 12.sp)
            }
        }
        if (checking) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
        PackUpdateErrorRow(updates, busy, checking, scope)
        actionError?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        PackContentArea(
            ui =
                PackListUi(
                    entries = entries,
                    filtered = filteredPackEntries(entries, query, chip, itemsByFile),
                    itemsByFile = itemsByFile,
                    folder = folder,
                    chip = chip,
                    query = query,
                    loading = loading,
                    error = error,
                    busy = busy,
                    iconRevision = refresh,
                ),
            actions = actions,
            instanceId = instance.id,
            onRetry = { refresh++ },
            onClearFilters = {
                query = ""
                chip = ContentChip.All
            },
            onDetail = { detailed = it },
            onRename = { renaming = it },
            onUpdate = { targets ->
                updateSelectedPacks(actions, instance.id, actionable, targets)?.let { watchingJob = it }
            },
            onOpenFolder = { actions.run { actions.controller.openFolder(instance.id, folder.directory) } },
            onDelete = { deleting = it },
        )
        Text(
            strings.t("deskC.footer.summary", entries.size, folder.directory),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 列表还没读出来时不按文件名过滤，避免先闪一下「全部为最新」。 */
private fun visiblePackItems(
    entries: List<LocalPackEntry>,
    reportItems: List<PackUpdateItem>,
): List<PackUpdateItem> =
    if (entries.isEmpty()) {
        reportItems
    } else {
        val installed = entries.mapTo(HashSet()) { it.filename }
        reportItems.filter { it.filename in installed }
    }

private fun packStatusText(
    checking: Boolean,
    hasReport: Boolean,
    updatable: Int,
    uncertain: Int,
): String =
    when {
        checking -> tr("deskC.status.checking")
        !hasReport -> ""
        updatable > 0 ->
            tr("deskC.status.updatable", updatable) +
                if (uncertain > 0) " · " + tr("deskC.status.uncertain", uncertain) else ""
        uncertain > 0 -> tr("deskC.status.uncertain", uncertain)
        else -> tr("deskC.status.allLatest")
    }

private fun filteredPackEntries(
    entries: List<LocalPackEntry>,
    query: String,
    chip: ContentChip,
    itemsByFile: Map<String, PackUpdateItem>,
): List<LocalPackEntry> =
    entries
        .filter { query.isBlank() || it.filename.contains(query, true) }
        .filter { entry ->
            val status = itemsByFile[entry.filename]?.status
            when (chip) {
                ContentChip.All -> true
                ContentChip.Updatable -> status == PackUpdateStatus.Updatable
                // 「未找到更新」只收「无法确认」的条目；已是最新的只在「全部」里出现。
                ContentChip.NoUpdate -> status == PackUpdateStatus.Unknown
                // 资源包与光影没有启用/禁用开关，这个芯片不会显示。
                ContentChip.Disabled -> true
            }
        }

/** 把后台导入/删除任务的状态变化翻译成界面动作；任务未结束时不处理。 */
internal fun watchPackJob(
    watchingJob: String?,
    jobs: List<DownloadJob>,
    onFailed: (String?) -> Unit,
    onCleared: () -> Unit,
    onFinished: () -> Unit,
) {
    val job = jobs.firstOrNull { it.id == watchingJob } ?: return
    when (job.status) {
        JobStatus.Failed -> {
            onFailed(job.error ?: tr("deskC.action.failed"))
            onCleared()
        }
        JobStatus.Completed,
        JobStatus.Cancelled,
        -> {
            onCleared()
            onFinished()
        }
        else -> Unit
    }
}

/** 删除资源包/光影并同步更新报告；失败原因写回 [onError]，成功则刷新列表。 */
private suspend fun deletePackEntry(
    actions: UiActions,
    updates: InstanceUpdateCenter,
    instanceId: String,
    folder: PackFolder,
    entries: List<LocalPackEntry>,
    onError: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    var reported = false
    val removed =
        runCatching {
            entries.forEach { entry ->
                check(actions.controller.content.deletePack(instanceId, folder, entry.filename)) {
                    tr("contentA1.local.deleteSourceMissing")
                }
            }
            true
        }.getOrElse { failure ->
            withContext(Dispatchers.Swing) { onError(failure.message ?: tr("deskC.delete.failed")) }
            reported = true
            false
        }
    withContext(Dispatchers.Swing) {
        if (!removed && !reported) {
            onError(
                tr(
                    "deskC.delete.notMoved",
                    entries.joinToString { it.filename },
                ),
            )
        }
        // 报告里的旧条目同步移除，避免「N 项可更新」把已删除的文件算进去。
        if (removed) {
            val filenames = entries.map { it.filename }.toSet()
            updates.packs?.let { current ->
                updates.packs =
                    current.copy(
                        items =
                            current.items.filterNot {
                                it.folder == folder && it.filename in filenames
                            },
                    )
            }
        }
        onRefresh()
    }
}

/** 行内的更新状态文案；没有可更新项时用弱化颜色。 */
@Composable
private fun RowScope.PackStatusLabel(
    statusText: String,
    updatable: Int,
) {
    if (statusText.isNotBlank()) {
        Text(
            statusText,
            Modifier.weight(1f),
            fontSize = 11.sp,
            color =
                if (updatable > 0) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    } else {
        Spacer(Modifier.weight(1f))
    }
}

/** 更新检查失败的提示行；重试按钮在有任务时不响应。 */
@Composable
private fun PackUpdateErrorRow(
    updates: InstanceUpdateCenter,
    busy: Boolean,
    checking: Boolean,
    scope: CoroutineScope,
) {
    val strings = LocalStrings.current
    updates.packsError?.let { failure ->
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                failure,
                Modifier.weight(1f),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            MoliTextButton(
                {
                    updates.packsError = null
                    updates.checkPacks(scope)
                },
                enabled = !busy && !checking,
            ) {
                Text(strings.t("deskC.errorRow.recheck"), fontSize = 12.sp)
            }
        }
    }
}

/** 删除确认对话框；确认后删除，取消只关闭弹窗。 */
@Composable
internal fun PackDeleteDialog(
    entries: List<LocalPackEntry>,
    folder: PackFolder,
    instanceId: String,
    actions: UiActions,
    updates: InstanceUpdateCenter,
    onError: (String?) -> Unit,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
) {
    val strings = LocalStrings.current
    AlertDialog(
        properties =
            androidx.compose.ui.window
                .DialogProperties(dismissOnClickOutside = false),
        onDismissRequest = onDismiss,
        title = { Text(strings.t("deskC.deleteDialog.title")) },
        text = {
            Text(
                strings.t(
                    if (entries.size == 1) "deskC.deleteDialog.message" else "deskC.deleteDialog.batchMessage",
                    if (entries.size == 1) entries.single().filename else entries.size,
                ),
            )
        },
        confirmButton = {
            MoliTextButton({
                onDismiss()
                onError(null)
                actions.run {
                    deletePackEntry(
                        actions,
                        updates,
                        instanceId,
                        folder,
                        entries,
                        { onError(it) },
                        onRefresh,
                    )
                }
            }) {
                Text(strings.t("deskC.deleteDialog.confirm"), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { MoliTextButton(onDismiss) { Text(strings.t("dialogs.common.cancel")) } },
    )
}

/** 列表区的展示快照，避免把一串列表状态逐个传进子组件。 */
private data class PackListUi(
    val entries: List<LocalPackEntry>,
    val filtered: List<LocalPackEntry>,
    val itemsByFile: Map<String, PackUpdateItem>,
    val folder: PackFolder,
    val chip: ContentChip,
    val query: String,
    val loading: Boolean,
    val error: String?,
    val busy: Boolean,
    val iconRevision: Int,
)

/** 列表区：加载骨架、错误、空目录、无匹配结果与实际行列表。 */
@Composable
private fun ColumnScope.PackContentArea(
    ui: PackListUi,
    actions: UiActions,
    instanceId: String,
    onRetry: () -> Unit,
    onClearFilters: () -> Unit,
    onDetail: (LocalPackEntry) -> Unit,
    onRename: (LocalPackEntry) -> Unit,
    onUpdate: (List<LocalPackEntry>) -> Unit,
    onOpenFolder: () -> Unit,
    onDelete: (List<LocalPackEntry>) -> Unit,
) {
    val strings = LocalStrings.current
    when {
        ui.loading && ui.entries.isEmpty() -> ListSkeleton()

        ui.error != null ->
            Panel(Modifier.fillMaxWidth()) {
                EmptyState(
                    strings.t("deskC.list.loadFailed", ui.folder.title),
                    ui.error.orEmpty(),
                    Icons.Outlined.CloudOff,
                ) {
                    MoliTextButton(onRetry) { Text(strings.t("deskC.list.retry")) }
                }
            }

        ui.entries.isEmpty() ->
            Panel(Modifier.fillMaxWidth()) {
                EmptyState(
                    strings.t("deskC.list.emptyTitle", ui.folder.title),
                    strings.t("deskC.list.emptyHint"),
                    if (ui.folder == PackFolder.ShaderPacks) {
                        Icons.Outlined.AutoAwesome
                    } else {
                        Icons.Outlined.Palette
                    },
                )
            }

        ui.filtered.isEmpty() ->
            Panel(Modifier.fillMaxWidth()) {
                EmptyState(
                    if (ui.chip != ContentChip.All && ui.query.isBlank()) {
                        strings.t("deskC.list.noChipMatch", ui.chip.title, ui.folder.title)
                    } else {
                        strings.t("deskC.list.noQueryMatch", ui.folder.title)
                    },
                    if (ui.chip != ContentChip.All && ui.query.isBlank()) {
                        strings.t("deskC.list.switchToAll")
                    } else {
                        strings.t("deskC.list.tryOtherKeyword")
                    },
                    Icons.Outlined.SearchOff,
                ) {
                    MoliTextButton(onClearFilters) {
                        Text(strings.t("deskC.list.clearFilter"))
                    }
                }
            }

        else -> {
            val selection = remember { ManagedSelection() }
            val listState = rememberLazyListState()
            val keys = ui.filtered.map { it.filename }
            LaunchedEffect(keys) { selection.retain(keys) }
            val selectedPacks = ui.filtered.filter { it.filename in selection.selected }
            SelectionActionBar(
                selection,
                keys,
                listOf(
                    ManagedAction(
                        strings.t("content.menu.update"),
                        Icons.Outlined.SystemUpdateAlt,
                        !ui.busy && selectedPacks.any { ui.itemsByFile[it.filename]?.batchApplicable == true },
                    ) { onUpdate(selectedPacks) },
                    ManagedAction(
                        strings.t("content.menu.delete"),
                        Icons.Outlined.DeleteOutline,
                        !ui.busy,
                    ) { onDelete(selectedPacks) },
                ),
            )
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().dragSelectRows(selection, keys),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(ui.filtered, key = { it.filename }) { entry ->
                    ManagedItemMenu(
                        key = entry.filename,
                        selection = selection,
                        actions = {
                            val selected =
                                ui.filtered.filter { it.filename in selection.selected }.ifEmpty {
                                    listOf(
                                        entry,
                                    )
                                }
                            packMenuActions(
                                selected,
                                ui.busy,
                                ui.itemsByFile,
                                onDetail,
                                onRename,
                                onUpdate,
                                onOpenFolder,
                                onDelete,
                            )
                        },
                    ) {
                        PackRow(
                            entry = entry,
                            folder = ui.folder,
                            update = ui.itemsByFile[entry.filename],
                            busy = ui.busy,
                            iconRevision = ui.iconRevision,
                            readIcon = { path -> actions.controller.content.packIcon(instanceId, path, ui.folder) },
                            onIconFailure = {
                                actions.controller.store.log(strings.t("deskC.icon.decodeFailed", it.message), true)
                            },
                            // 已有选区时单击行改为切换选中，避免误开详情页。
                            onDetail = { if (selection.active) selection.toggle(entry.filename) else onDetail(entry) },
                            onUpdate = { onUpdate(listOf(entry)) },
                            onDelete = { onDelete(listOf(entry)) },
                        )
                    }
                }
            }
        }
    }
}

private fun packMenuActions(
    selected: List<LocalPackEntry>,
    busy: Boolean,
    updates: Map<String, PackUpdateItem>,
    onDetail: (LocalPackEntry) -> Unit,
    onRename: (LocalPackEntry) -> Unit,
    onUpdate: (List<LocalPackEntry>) -> Unit,
    onOpenFolder: () -> Unit,
    onDelete: (List<LocalPackEntry>) -> Unit,
): List<ManagedAction> {
    val canUpdate =
        selected.any {
            updates[it.filename]?.batchApplicable == true
        }
    return listOf(
        ManagedAction(
            tr("content.menu.details"),
            Icons.Outlined.Info,
            selected.size == 1,
        ) { onDetail(selected.single()) },
        ManagedAction(
            tr("content.menu.update"),
            Icons.Outlined.SystemUpdateAlt,
            !busy && canUpdate,
        ) { onUpdate(selected) },
        ManagedAction(tr("content.menu.rename"), Icons.Outlined.DriveFileRenameOutline, !busy && selected.size == 1) {
            onRename(selected.single())
        },
        ManagedAction(tr("content.menu.openFolder"), Icons.Outlined.FolderOpen) { onOpenFolder() },
        ManagedAction(tr("content.menu.delete"), Icons.Outlined.DeleteOutline, !busy) { onDelete(selected) },
    )
}
