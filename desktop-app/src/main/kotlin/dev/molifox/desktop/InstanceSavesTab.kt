// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 展示实例存档、备份列表与操作状态。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import dev.molifox.desktop.MoliTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.molifox.core.DownloadJob
import dev.molifox.core.GameInstance
import dev.molifox.core.JobStatus
import dev.molifox.core.LauncherTaskKind
import dev.molifox.core.SaveBackupInfo
import dev.molifox.core.SaveEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * 存档页签：顶部搜索 / 刷新 / 打开存档文件夹，44dp 一行列出世界（名称、大小、修改时间、 「可能不是有效世界」提示），
 * 行尾「详情」「备份」「复制到…」「删除」；下方是「恢复时替换原世界」设置与「备份」卡片。
 *
 * 备份与原版游戏共用 `<游戏目录>/backups/`，原版写的备份也会列出；没有 `level.dat` 的压缩包只展示、不提供恢复。
 * 列表在 IO 线程读取；任务进行中（controller.busy）禁用所有操作按钮；后台任务失败时把后端的中文消息显示在 工具行下方，不静默吞掉。
 */
@Composable
internal fun InstanceSavesTab(
    instance: GameInstance,
    actions: UiActions,
    modifier: Modifier,
) {
    val strings = LocalStrings.current
    var saves by remember(instance.id) { mutableStateOf<List<SaveEntry>>(emptyList()) }
    var backups by remember(instance.id) { mutableStateOf<List<SaveBackupInfo>>(emptyList()) }
    var query by remember(instance.id) { mutableStateOf("") }
    var refresh by remember(instance.id) { mutableIntStateOf(0) }
    var loading by remember(instance.id) { mutableStateOf(true) }
    var loadError by remember(instance.id) { mutableStateOf<String?>(null) }
    // 操作失败与列表读取失败分开保存：刷新列表不会把刚失败的操作提示一起清掉。
    var actionError by remember(instance.id) { mutableStateOf<String?>(null) }
    // 本次页面发起的后台任务；结束时用它刷新列表并显示失败原因。
    var watchingJob by remember(instance.id) { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<List<SaveEntry>>(emptyList()) }
    var deletingBackup by remember { mutableStateOf<SaveBackupInfo?>(null) }
    // 开启「恢复时替换原世界」后，恢复会替换同名世界；执行前单独确认。
    var replacing by remember { mutableStateOf<SaveBackupInfo?>(null) }
    var copying by remember { mutableStateOf<SaveEntry?>(null) }
    var detailed by remember { mutableStateOf<SaveEntry?>(null) }
    var renaming by remember { mutableStateOf<SaveEntry?>(null) }
    val liveState by actions.controller.state.collectAsState()
    val busy = actions.controller.isInstanceBusy(instance.id)
    val running = actions.controller.game.isRunning(instance.id)
    val canManage = !busy && !running
    val canCopy = canManage
    val blockedNote = savesBlockedNote(running, busy)
    val savesDirectory =
        actions.controller.store
            .gamePath(instance)
            .resolve("saves")
    // 只把存档相关任务的结束当作刷新信号，避免其它下载任务反复触发备份 SHA1 重算。
    val saveJobsKey =
        liveState.jobs
            .filter { it.kind == LauncherTaskKind.SaveOperation }
            .map { it.id + ":" + it.status }
    SavesLoadEffect(
        actions = actions,
        instanceId = instance.id,
        refresh = refresh,
        saveJobsKey = saveJobsKey,
        onStart = {
            loading = true
            loadError = null
        },
        onLoaded = { saveList, backupList ->
            saves = saveList
            backups = backupList
        },
        onFailed = { message ->
            saves = emptyList()
            backups = emptyList()
            loadError = message ?: strings.t("pages.saves.loadFailed")
        },
        onFinished = { loading = false },
    )
    SaveJobWatcher(
        watchingJob = watchingJob,
        jobs = liveState.jobs,
        saveJobsKey = saveJobsKey,
        onFailed = { message ->
            actionError = message
            watchingJob = null
        },
        onFinished = { watchingJob = null },
    )
    val startBackup = { entry: SaveEntry ->
        actionError = null
        startSaveJob(
            fallback = strings.t("pages.saves.backup.startFailed"),
            onStarted = { watchingJob = it },
            onError = { actionError = it },
        ) { actions.controller.backupSave(instance.id, entry.name) }
    }
    val startRestore = { backup: SaveBackupInfo ->
        actionError = null
        startSaveJob(
            fallback = strings.t("pages.saves.restore.startFailed"),
            onStarted = { watchingJob = it },
            onError = { actionError = it },
        ) { actions.controller.restoreSave(instance.id, backup.id) }
    }
    // 拖入的 .zip / 世界文件夹：核心先校验（level.dat 在根或唯一一层包装目录）再解压，无效输入不落盘。
    val startImport: (List<Path>) -> Unit = { dropped ->
        actionError = null
        when {
            dropped.isEmpty() -> Unit
            dropped.size > 1 -> actionError = strings.t("pages.saves.drop.oneFile")
            else ->
                startSaveJob(
                    fallback = strings.t("pages.saves.import.startFailed"),
                    onStarted = { watchingJob = it },
                    onError = { actionError = it },
                ) { actions.controller.importSave(instance.id, dropped.single()) }
        }
    }
    val ui =
        SavesUiState(
            saves = saves,
            backups = backups,
            query = query,
            savesDirectory = savesDirectory,
            blockedNote = blockedNote,
            actionError = actionError,
            loading = loading,
            loadError = loadError,
            canManage = canManage,
            canCopy = canCopy,
            busy = busy,
            replaceOnRestore = instance.replaceSaveOnRestore,
        )
    val callbacks =
        SavesCallbacks(
            onChangeQuery = { query = it },
            onClearError = { actionError = null },
            onRefresh = { refresh++ },
            onOpenFolder = { actions.run { actions.controller.openFolder(instance.id, "saves") } },
            onDetails = { detailed = it },
            onBackup = startBackup,
            onCopy = { copying = it },
            onRename = { renaming = it },
            onDelete = { deleting = it },
            onDeleteBackup = { deletingBackup = it },
            onRestore = startRestore,
            onReplaceRestore = { replacing = it },
            onToggleReplace = { enabled ->
                actionError = null
                runCatching {
                    actions.controller.setReplaceSaveOnRestore(instance.id, enabled)
                }.onFailure {
                    actionError = it.message ?: strings.t("pages.saves.settings.saveFailed")
                }
            },
        )
    Column(
        modifier
            .fillMaxHeight()
            .contentDropTarget(
                enabled = canManage,
                hint = strings.t("pages.saves.dropHint"),
                onError = { actionError = it },
            ) { dropped -> startImport(dropped) },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SavesTabSections(ui, callbacks)
    }
    SaveDialogs(
        instance = instance,
        actions = actions,
        deleting = deleting,
        renaming = renaming,
        deletingBackup = deletingBackup,
        replacing = replacing,
        copying = copying,
        detailed = detailed,
        canManage = canManage,
        onRestore = startRestore,
        onWatchJob = { watchingJob = it },
        onError = { actionError = it },
        onRefresh = { refresh++ },
        onDismissDeleting = { deleting = emptyList() },
        onDismissRenaming = { renaming = null },
        onDismissBackup = { deletingBackup = null },
        onDismissReplacing = { replacing = null },
        onDismissCopying = { copying = null },
        onDismissDetails = { detailed = null },
    )
}

/** 游戏运行 / 任务占用时给出的操作提示；两者互斥，运行状态优先。 */
private fun savesBlockedNote(
    running: Boolean,
    busy: Boolean,
): String =
    when {
        running -> tr("pages.saves.blocked.running")
        busy -> tr("pages.saves.blocked.busy")
        else -> ""
    }

/** 一次列表读取的结果；失败信息沿用后端文案，缺失时用页面兜底文案。 */
private sealed interface SavesSnapshot {
    data class Loaded(
        val saves: List<SaveEntry>,
        val backups: List<SaveBackupInfo>,
    ) : SavesSnapshot

    data class Failed(
        val message: String?,
    ) : SavesSnapshot
}

private suspend fun loadSavesSnapshot(
    actions: UiActions,
    instanceId: String,
): SavesSnapshot =
    try {
        val snapshot =
            withContext(Dispatchers.IO) {
                actions.controller.listSaves(instanceId) to
                    actions.controller.listSaveBackups(instanceId)
            }
        SavesSnapshot.Loaded(snapshot.first, snapshot.second)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: IllegalStateException) {
        // 列表读取只会在实例已移除（store.instance 的 error）时抛错；IO 失败已由 SaveManager 内部兜底。
        SavesSnapshot.Failed(failure.message)
    }

/** 列表读取副作用：进入时置加载中，结束（含取消）时收起加载态。 */
@Composable
private fun SavesLoadEffect(
    actions: UiActions,
    instanceId: String,
    refresh: Int,
    saveJobsKey: List<String>,
    onStart: () -> Unit,
    onLoaded: (List<SaveEntry>, List<SaveBackupInfo>) -> Unit,
    onFailed: (String?) -> Unit,
    onFinished: () -> Unit,
) {
    LaunchedEffect(instanceId, refresh, saveJobsKey) {
        onStart()
        try {
            when (val snapshot = loadSavesSnapshot(actions, instanceId)) {
                is SavesSnapshot.Loaded -> onLoaded(snapshot.saves, snapshot.backups)
                is SavesSnapshot.Failed -> onFailed(snapshot.message)
            }
        } finally {
            onFinished()
        }
    }
}

/** 观察本页发起的后台任务：失败显示后端文案，结束或取消后停止观察。 */
@Composable
private fun SaveJobWatcher(
    watchingJob: String?,
    jobs: List<DownloadJob>,
    saveJobsKey: List<String>,
    onFailed: (String) -> Unit,
    onFinished: () -> Unit,
) {
    LaunchedEffect(watchingJob, saveJobsKey) {
        val id = watchingJob ?: return@LaunchedEffect
        val job = jobs.firstOrNull { it.id == id } ?: return@LaunchedEffect
        when (job.status) {
            JobStatus.Failed -> onFailed(job.error ?: tr("pages.saves.job.failed"))
            JobStatus.Completed,
            JobStatus.Cancelled,
            -> onFinished()
            else -> Unit
        }
    }
}

/** 开始一个后台存档任务；开始失败时把后端提示显示在工具行下方，不改变正在观察的任务。 */
private fun startSaveJob(
    fallback: String,
    onStarted: (String) -> Unit,
    onError: (String) -> Unit,
    action: () -> String,
) {
    try {
        onStarted(action())
    } catch (failure: IllegalStateException) {
        // 启动失败来自 requireIdle / store.instance 的 check(error)；异常类型可枚举。
        onError(failure.message ?: fallback)
    }
}

/** 把存档或备份移入回收站；未移入时给出具体原因，无论结果都刷新列表。 */
internal fun trashEntry(
    actions: UiActions,
    message: String,
    onError: (String) -> Unit,
    onRefresh: () -> Unit,
    delete: suspend () -> Boolean,
) {
    actions.run {
        val moved = delete()
        withContext(Dispatchers.Swing) {
            if (!moved) onError(message)
            onRefresh()
        }
    }
}

/** 移入回收站的确认框；存档与备份共用同一套文案结构。 */
@Composable
internal fun SaveTrashDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val strings = LocalStrings.current
    AlertDialog(
        properties =
            androidx.compose.ui.window
                .DialogProperties(dismissOnClickOutside = false),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onConfirm) {
                Text(strings.t("pages.saves.trash.confirm"), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onDismiss) { Text(strings.t("pages.saves.cancel")) }
        },
    )
}

/** 用备份替换原世界的确认框；替换前会先备份当前世界。 */
@Composable
internal fun ReplaceSaveDialog(
    backup: SaveBackupInfo,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val strings = LocalStrings.current
    AlertDialog(
        properties =
            androidx.compose.ui.window
                .DialogProperties(dismissOnClickOutside = false),
        onDismissRequest = onDismiss,
        title = { Text(strings.t("pages.saves.replace.title")) },
        text = { Text(strings.t("pages.saves.replace.message", backup.saveName)) },
        confirmButton = {
            TextButton(onConfirm) {
                Text(
                    strings.t("pages.saves.replace.confirm"),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onDismiss) { Text(strings.t("pages.saves.cancel")) }
        },
    )
}
