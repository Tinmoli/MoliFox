// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 展示实例模组列表、筛选与更新操作状态。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.molifox.core.DownloadJob
import dev.molifox.core.GameInstance
import dev.molifox.core.JobStatus
import dev.molifox.core.ModFile
import dev.molifox.core.ModUpdateItem
import dev.molifox.core.ModUpdateReport
import dev.molifox.core.ModUpdateStatus
import dev.molifox.core.domain.mods.completeModUpdateReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.io.IOException

/** 模组列表：工具行（刷新/导入/下载/打开文件夹）+ 筛选芯片 + 52dp 行（版本与来源/更新/开关/删除）+ 骨架 + 空态。 */
@Composable
internal fun InstanceMods(
    instance: GameInstance,
    actions: UiActions,
    updates: InstanceUpdateCenter,
    modifier: Modifier,
    onDiscover: () -> Unit,
) {
    val strings = LocalStrings.current
    var mods by remember { mutableStateOf<List<ModFile>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var jarInJarOpen by remember { mutableStateOf(false) }
    var detailMod by remember { mutableStateOf<ModFile?>(null) }
    var deletingMods by remember { mutableStateOf<List<ModFile>>(emptyList()) }
    var renamingMod by remember { mutableStateOf<ModFile?>(null) }
    var chip by remember(instance.id) { mutableStateOf(ContentChip.All) }
    val checking = updates.modsChecking
    val updateReport = updates.mods
    val updateError = updates.modsError
    val checkScope = rememberCoroutineScope()
    // 本次页面发起的更新任务；完成/失败后刷新列表并更新报告。
    var applyingJob by remember(instance.id) { mutableStateOf<Pair<String, List<ModUpdateItem>>?>(null) }
    val liveState by actions.controller.state.collectAsState()
    // 检查只读网络与 JAR 声明：自动检查由页面发起，手动重查只取消它自己的请求。
    val startCheck: () -> Unit = { updates.checkMods(checkScope) }
    val startUpdate: (List<ModUpdateItem>) -> Unit = { items ->
        applyingJob = startModUpdates(actions, instance.id, items, updates)
    }
    ModsDialogs(
        jarInJarOpen = jarInJarOpen,
        instance = instance,
        actions = actions,
        mods = mods,
        detailMod = detailMod,
        deletingMods = deletingMods,
        renamingMod = renamingMod,
        onCloseJarInJar = { jarInJarOpen = false },
        onCloseDetail = { detailMod = null },
        onDeleteMods = { targets ->
            deletingMods = emptyList()
            deleteMods(actions, instance.id, targets) { refresh++ }
        },
        onDismissDelete = { deletingMods = emptyList() },
        onRenameMod = { mod, newName ->
            renamingMod = null
            renameMod(actions, instance.id, mod, newName) { refresh++ }
        },
        onDismissRename = { renamingMod = null },
    )
    LaunchedEffect(instance.id, refresh, liveState.jobs.map { it.status }) {
        loading = true
        error = null
        loadMods(actions, instance.id)
            .onSuccess { mods = it }
            .onFailure { failure -> error = failure.message }
        loading = false
    }
    // 应用更新是任务中心里的后台任务；结束后刷新列表，并把旧文件对应的报告项改为已是最新。
    LaunchedEffect(applyingJob, liveState.jobs.map { it.id + ":" + it.status }) {
        val pending = applyingJob ?: return@LaunchedEffect
        val job = liveState.jobs.firstOrNull { it.id == pending.first } ?: return@LaunchedEffect
        val outcome = modUpdateJobOutcome(job, pending, updateReport) ?: return@LaunchedEffect
        outcome.report?.let { updates.mods = it }
        outcome.error?.let { updates.modsError = it }
        applyingJob = null
        refresh++
    }
    val busy = rememberInstanceBusy(actions, instance.id)
    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ContentToolbar(
            flexible = { ToolbarSearchField(query, { query = it }, strings.t("deskD.mods.searchPlaceholder")) },
            refresh =
                ToolbarAction(
                    strings.t("deskD.mods.refresh"),
                    icon = Icons.Outlined.Refresh,
                    enabled = !busy,
                ) { refresh++ },
            importAction =
                ToolbarAction(strings.t("deskD.mods.importMods"), icon = Icons.Outlined.FileDownload, enabled = !busy) {
                    actions.run {
                        actions.controller.requireIdle(instance.id)
                        val files =
                            actions.choose(
                                strings.t("deskD.mods.importDialogTitle"),
                                multiple = true,
                                extensions = listOf("jar"),
                            )
                        actions.controller.content.importMods(instance.id, files)
                        withContext(Dispatchers.Swing) { refresh++ }
                    }
                },
            downloads =
                listOf(
                    ToolbarAction(
                        strings.t("deskD.mods.download"),
                        icon = Icons.Outlined.Add,
                        style = ToolbarActionStyle.Filled,
                    ) { onDiscover() },
                ),
            openFolder =
                ToolbarAction(strings.t("deskD.mods.openFolder"), icon = Icons.Outlined.FolderOpen, enabled = !busy) {
                    actions.run { actions.controller.openFolder(instance.id, "mods") }
                },
        )
        val reportByFile = updateReport?.items?.associateBy { it.filename }.orEmpty()
        val activeNames = mods.map { it.filename }.toSet()
        val counts = countModUpdates(updateReport, activeNames)
        // 芯片按更新状态筛选：「未找到更新」只收「无法确认」，「已是最新」只出现在「全部」。
        // 数量与芯片一致：可更新数量、无法确认数量都不含已是最新的条目。
        val statusText = modsStatusText(checking, updateReport, counts)
        ModsStatusBar(
            chip = chip,
            onChipSelect = { chip = it },
            statusText = statusText,
            updateCount = counts.updatable,
            checking = checking,
            instanceBusy = busy,
            onCheckUpdates = { updates.checkAll(checkScope, refresh = true) },
            onOpenJarInJar = { jarInJarOpen = true },
            onUpdateAll = {
                startUpdate(updateReport?.batchItems(activeNames).orEmpty())
            },
        )
        ModsThinProgress(checking)
        ModsErrorRow(
            message = updateError,
            checking = checking,
            instanceBusy = busy,
            onRetry = {
                updates.modsError = null
                startCheck()
            },
        )
        val filtered = filterMods(mods, query, chip, reportByFile)
        // 模组图标只从 JAR 自带资源读取字节并在内存解码，不写缓存文件；行内按 JAR 路径 + 修改时间复用。
        val modsDirectory =
            runCatching {
                actions.controller.store
                    .gamePath(instance)
                    .resolve("mods")
            }.getOrNull()
        // 首次读取还没有数据时给骨架；刷新时保留旧列表，只在顶部显示一条细进度。
        ModsThinProgress(loading && mods.isNotEmpty())
        ModsListBody(
            mods = mods,
            filtered = filtered,
            loading = loading,
            error = error,
            chip = chip,
            query = query,
            busy = busy,
            applying = applyingJob != null,
            reportByFile = reportByFile,
            modsDirectory = modsDirectory,
            readIcon = actions.controller::modIconBytes,
            onIconFailure = {
                actions.controller.store.log(strings.t("deskD.mods.iconDecodeFailed", it.message), true)
            },
            onRetry = { refresh++ },
            onDiscover = onDiscover,
            onClearFilter = {
                query = ""
                chip = ContentChip.All
            },
            onDetail = { mod -> detailMod = mod },
            onUpdate = { _, item ->
                startUpdate(listOf(item))
            },
            onToggle = { mod ->
                actions.run {
                    actions.controller.requireIdle(instance.id)
                    actions.controller.content.toggleMod(instance.id, mod.filename)
                    withContext(Dispatchers.Swing) { refresh++ }
                }
            },
            onRename = { mod -> renamingMod = mod },
            onOpenFolder = { actions.run { actions.controller.openFolder(instance.id, "mods") } },
            onUpdateSelection = { selected ->
                startUpdate(updateReport?.batchItems(selected.mapTo(HashSet()) { it.filename }).orEmpty())
            },
            onEnableSelection = { selected, enabled ->
                setModsEnabled(actions, instance.id, selected, enabled) { refresh++ }
            },
            onDelete = { selected -> deletingMods = selected },
            modifier = Modifier.weight(1f),
        )
        ModsCountFooter(loading, mods)
    }
}

private fun startModUpdates(
    actions: UiActions,
    instanceId: String,
    items: List<ModUpdateItem>,
    updates: InstanceUpdateCenter,
): Pair<String, List<ModUpdateItem>>? {
    if (items.isEmpty()) return null
    updates.modsError = null
    return runCatching {
        if (items.size == 1) {
            actions.controller.updateMod(instanceId, items.single())
        } else {
            actions.controller.updateMods(instanceId, items)
        }
    }.fold(
        onSuccess = { job -> job to items },
        onFailure = { failure ->
            updates.modsError = failure.message
            null
        },
    )
}

private fun deleteMods(
    actions: UiActions,
    instanceId: String,
    targets: List<ModFile>,
    onRefresh: () -> Unit,
) {
    actions.run {
        targets.forEach { mod -> actions.controller.content.deleteMod(instanceId, mod.filename) }
        withContext(Dispatchers.Swing) { onRefresh() }
    }
}

private fun renameMod(
    actions: UiActions,
    instanceId: String,
    mod: ModFile,
    newName: String,
    onRefresh: () -> Unit,
) {
    actions.run {
        actions.controller.requireIdle(instanceId)
        actions.controller.content.renameMod(instanceId, mod.filename, newName)
        withContext(Dispatchers.Swing) { onRefresh() }
    }
}

private fun setModsEnabled(
    actions: UiActions,
    instanceId: String,
    selected: List<ModFile>,
    enabled: Boolean,
    onRefresh: () -> Unit,
) {
    actions.run {
        actions.controller.requireIdle(instanceId)
        selected.filter { it.enabled != enabled }.forEach { mod ->
            actions.controller.content.toggleMod(instanceId, mod.filename)
        }
        withContext(Dispatchers.Swing) { onRefresh() }
    }
}

/** 读取已安装模组：目录读取与 JAR 声明解析的错误交给列表提示。 */
private suspend fun loadMods(
    actions: UiActions,
    instanceId: String,
): Result<List<ModFile>> =
    withContext(Dispatchers.IO) {
        try {
            Result.success(actions.controller.content.listMods(instanceId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            Result.failure(failure)
        } catch (failure: IllegalStateException) {
            Result.failure(failure)
        } catch (failure: IllegalArgumentException) {
            Result.failure(failure)
        }
    }

/** 后台更新任务的结果：报告项可选（无需改动时为空），错误信息可选。 */
private class ModUpdateJobOutcome(
    val report: ModUpdateReport?,
    val error: String?,
)

/** 只有结束状态才产生结果；完成时把旧文件对应的报告项改为已是最新。 */
private fun modUpdateJobOutcome(
    job: DownloadJob,
    pending: Pair<String, List<ModUpdateItem>>,
    report: ModUpdateReport?,
): ModUpdateJobOutcome? =
    when (job.status) {
        JobStatus.Completed ->
            ModUpdateJobOutcome(
                pending.second.fold(report) { current, item ->
                    if (item.candidate != null) completeModUpdateReport(current, item) else current
                },
                null,
            )

        JobStatus.Failed, JobStatus.Cancelled ->
            ModUpdateJobOutcome(
                null,
                job.error
                    ?: if (job.status == JobStatus.Cancelled) {
                        tr("deskD.mods.updateCancelled")
                    } else {
                        tr("deskD.mods.updateFailed")
                    },
            )

        else -> null
    }

/** 可更新与无法确认的条目数：只统计仍在列表里的文件。 */
private class ModUpdateCounts(
    val updatable: Int,
    val unknown: Int,
)

private fun countModUpdates(
    report: ModUpdateReport?,
    activeNames: Set<String>,
): ModUpdateCounts {
    val items = report?.items.orEmpty()
    return ModUpdateCounts(
        updatable =
            items.count {
                it.status == ModUpdateStatus.Updatable && it.filename in activeNames
            },
        unknown =
            items.count {
                it.status == ModUpdateStatus.Unknown && it.filename in activeNames
            },
    )
}

/** 更新状态文案：检查中、未检查、有可更新、只有无法确认、全部最新。 */
private fun modsStatusText(
    checking: Boolean,
    report: ModUpdateReport?,
    counts: ModUpdateCounts,
): String =
    when {
        checking -> tr("deskD.mods.checking")
        report == null -> ""
        counts.updatable > 0 ->
            if (counts.unknown > 0) {
                tr("deskD.mods.updatableWithUnknown", counts.updatable, counts.unknown)
            } else {
                tr("deskD.mods.updatableCount", counts.updatable)
            }
        counts.unknown > 0 -> tr("deskD.mods.unknownCount", counts.unknown)
        else -> tr("deskD.mods.allUpToDate")
    }
