// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 展示实例存档、备份列表与操作状态。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.GameInstance
import dev.molifox.core.JobStatus
import dev.molifox.core.SaveBackupInfo
import dev.molifox.core.SaveEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.job
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.name

/**
 * 存档页签：顶部搜索 / 刷新 / 打开存档文件夹，44dp 一行列出世界（名称、大小、修改时间、 「可能不是有效世界」提示），行尾「备份」「复制到…」「删除」；下方是「恢复时替换原世界」设置与「备份」卡片。
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
    var deleting by remember { mutableStateOf<SaveEntry?>(null) }
    var deletingBackup by remember { mutableStateOf<SaveBackupInfo?>(null) }
    // 开启「恢复时替换原世界」后，恢复会替换同名世界；执行前单独确认。
    var replacing by remember { mutableStateOf<SaveBackupInfo?>(null) }
    var copying by remember { mutableStateOf<SaveEntry?>(null) }
    val liveState by actions.controller.state.collectAsState()
    val busy = actions.controller.isInstanceBusy(instance.id)
    val running = actions.controller.game.isRunning(instance.id)
    val canManage = !busy && !running
    val canCopy = canManage
    val blockedNote =
        when {
            running -> "游戏正在使用存档，请先退出游戏再进行存档操作。"
            busy -> "任务进行中，存档操作暂时不可用。"
            else -> ""
        }
    val savesDirectory =
        actions.controller.store
            .instancePath(instance.id)
            .resolve("saves")
    // 只把存档相关任务的结束当作刷新信号，避免其它下载任务反复触发备份 SHA1 重算。
    val saveJobsKey =
        liveState.jobs.filter { it.title.contains("存档") }.map { it.id + ":" + it.status }
    LaunchedEffect(instance.id, refresh, saveJobsKey) {
        loading = true
        loadError = null
        try {
            val snapshot =
                withContext(Dispatchers.IO) {
                    actions.controller.listSaves(instance.id) to
                        actions.controller.listSaveBackups(instance.id)
                }
            saves = snapshot.first
            backups = snapshot.second
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            saves = emptyList()
            backups = emptyList()
            loadError = failure.message ?: "无法读取存档目录"
        } finally {
            loading = false
        }
    }
    LaunchedEffect(watchingJob, saveJobsKey) {
        val id = watchingJob ?: return@LaunchedEffect
        val job = liveState.jobs.firstOrNull { it.id == id } ?: return@LaunchedEffect
        when (job.status) {
            JobStatus.Failed -> {
                actionError = job.error ?: "存档操作失败，详情见日志"
                watchingJob = null
            }
            JobStatus.Completed,
            JobStatus.Cancelled,
            -> watchingJob = null
            else -> Unit
        }
    }
    val startBackup = { entry: SaveEntry ->
        actionError = null
        try {
            watchingJob = actions.controller.backupSave(instance.id, entry.name)
        } catch (failure: Exception) {
            actionError = failure.message ?: "无法开始备份存档"
        }
    }
    val startRestore = { backup: SaveBackupInfo ->
        actionError = null
        try {
            watchingJob = actions.controller.restoreSave(instance.id, backup.id)
        } catch (failure: Exception) {
            actionError = failure.message ?: "无法开始恢复存档"
        }
    }
    // 拖入的 .zip / 世界文件夹：核心先校验（level.dat 在根或唯一一层包装目录）再解压，无效输入不落盘。
    val startImport: (List<Path>) -> Unit = { dropped ->
        actionError = null
        when {
            dropped.isEmpty() -> Unit
            dropped.size > 1 -> actionError = "请一次拖入一个存档"
            else ->
                try {
                    watchingJob = actions.controller.importSave(instance.id, dropped.single())
                } catch (failure: Exception) {
                    actionError = failure.message ?: "无法导入存档"
                }
        }
    }
    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("移入回收站？") },
            text = { Text("「${entry.name}」会移入回收站，可从回收站恢复。") },
            confirmButton = {
                TextButton({
                    deleting = null
                    actionError = null
                    actions.run {
                        val moved = actions.controller.deleteSave(instance.id, entry.name)
                        withContext(Dispatchers.Swing) {
                            if (!moved) {
                                actionError = "「${entry.name}」没有移入回收站：存档可能已不存在，或回收站不可用；详情见日志。"
                            }
                            refresh++
                        }
                    }
                }) {
                    Text("移入回收站", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton({ deleting = null }) { Text("取消") } },
        )
    }
    deletingBackup?.let { backup ->
        AlertDialog(
            onDismissRequest = { deletingBackup = null },
            title = { Text("删除这个备份？") },
            text = {
                Text(
                    "「" +
                        backup.saveName +
                        "」（" +
                        formatSaveTime(backup.createdAt) +
                        "）的备份会移入回收站，可从回收站恢复。",
                )
            },
            confirmButton = {
                TextButton({
                    deletingBackup = null
                    actionError = null
                    actions.run {
                        val moved = actions.controller.deleteSaveBackup(instance.id, backup.id)
                        withContext(Dispatchers.Swing) {
                            if (!moved) actionError = "备份没有移入回收站：文件可能已不存在，或回收站不可用；详情见日志。"
                            refresh++
                        }
                    }
                }) {
                    Text("移入回收站", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton({ deletingBackup = null }) { Text("取消") } },
        )
    }
    replacing?.let { backup ->
        AlertDialog(
            onDismissRequest = { replacing = null },
            title = { Text("用备份替换原世界？") },
            text = {
                Text(
                    "当前世界「" + backup.saveName + "」会先备份到 backups/，再用这个备份替换它；" +
                        "当前世界会改名为「" + backup.saveName + "（替换前 时间）」保留，不会删除。",
                )
            },
            confirmButton = {
                TextButton({
                    replacing = null
                    startRestore(backup)
                }) {
                    Text("替换并恢复", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton({ replacing = null }) { Text("取消") } },
        )
    }
    copying?.let { entry ->
        CopySaveDialog(
            instance = instance,
            saveName = entry.name,
            actions = actions,
            onDismiss = { copying = null },
            onStarted = { jobId -> watchingJob = jobId },
        )
    }
    Column(
        modifier
            .fillMaxHeight()
            .contentDropTarget(
                enabled = canManage,
                hint = "请拖入存档 .zip 或世界文件夹",
                onError = { actionError = it },
            ) { dropped -> startImport(dropped) },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ContentToolbar(
            flexible = { ToolbarSearchField(query, { query = it }, "搜索存档名称") },
            refresh =
                ToolbarAction("刷新", icon = Icons.Outlined.Refresh, enabled = !busy) {
                    actionError = null
                    refresh++
                },
            openFolder =
                ToolbarAction("打开文件夹", icon = Icons.Outlined.FolderOpen, enabled = !busy) {
                    actions.run { actions.controller.openFolder(instance.id, "saves") }
                },
        )
        Text(
            "${saves.size} 个存档 · ${backups.size} 个备份 · $savesDirectory",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (blockedNote.isNotBlank()) {
            Text(
                blockedNote,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        actionError?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        // 刷新时保留旧列表，只在顶部显示一条细进度；首次读取才给骨架。
        if (loading && saves.isNotEmpty()) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
        }
        val filtered = saves.filter { query.isBlank() || it.name.contains(query, true) }
        when {
            loading && saves.isEmpty() && backups.isEmpty() -> ListSkeleton()
            loadError != null ->
                Panel(Modifier.fillMaxWidth()) {
                    EmptyState("无法读取存档", loadError.orEmpty(), Icons.Outlined.CloudOff) {
                        TextButton({ refresh++ }) { Text("重试") }
                    }
                }
            else ->
                ScrollColumn(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        saves.isEmpty() ->
                            Panel(Modifier.fillMaxWidth()) {
                                EmptyState(
                                    "这个实例还没有存档",
                                    "在游戏里创建一个世界，用「复制到…」从别的实例复制，" +
                                        "或把存档 .zip / 世界文件夹拖进这个页面。",
                                    Icons.Outlined.Save,
                                )
                            }
                        filtered.isEmpty() ->
                            Panel(Modifier.fillMaxWidth()) {
                                EmptyState("没有匹配的存档", "换个关键词试试", Icons.Outlined.SearchOff) {
                                    TextButton({ query = "" }) { Text("清除搜索") }
                                }
                            }
                        else ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                filtered.forEach { entry ->
                                    key(entry.name) {
                                        SaveRow(
                                            entry = entry,
                                            canManage = canManage,
                                            canCopy = canCopy,
                                            onBackup = { startBackup(entry) },
                                            onCopy = { copying = entry },
                                            onDelete = { deleting = entry },
                                        )
                                    }
                                }
                            }
                    }
                    Panel(Modifier.fillMaxWidth()) {
                        SettingRow(
                            "恢复时替换原世界",
                            "关闭时恢复只解压成同级的新世界，不改动原存档；开启后先备份当前世界，再用备份替换它，" +
                                "当前世界改名为「（替换前 时间）」保留。",
                        ) {
                            CompactSwitch(
                                instance.replaceSaveOnRestore,
                                { enabled ->
                                    actionError = null
                                    runCatching {
                                        actions.controller.setReplaceSaveOnRestore(
                                            instance.id,
                                            enabled,
                                        )
                                    }.onFailure {
                                        actionError = it.message ?: "无法保存设置"
                                    }
                                },
                                enabled = !busy,
                            )
                        }
                    }
                    Panel(
                        Modifier.fillMaxWidth(),
                        title = "备份",
                        detail =
                            "与原版游戏共用 backups/ 目录（原版自己写的备份也会列在这里）；" +
                                "恢复默认解压成新世界，不直接覆盖。",
                    ) {
                        if (backups.isEmpty()) {
                            Text(
                                "还没有备份。存档行末尾的「备份」会把整个世界打包到这里。",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                backups.forEach { backup ->
                                    key(backup.id) {
                                        SaveBackupRow(
                                            backup = backup,
                                            busy = busy,
                                            onRestore = {
                                                val exists =
                                                    saves.any {
                                                        it.name.equals(backup.saveName, true) ||
                                                            saveBaseName(it.name)
                                                                .equals(backup.saveName, true)
                                                    }
                                                // 只有开启「恢复时替换原世界」且同名世界存在时才替换。
                                                if (instance.replaceSaveOnRestore && exists) {
                                                    replacing = backup
                                                } else {
                                                    startRestore(backup)
                                                }
                                            },
                                            onDelete = { deletingBackup = backup },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
        }
    }
}
