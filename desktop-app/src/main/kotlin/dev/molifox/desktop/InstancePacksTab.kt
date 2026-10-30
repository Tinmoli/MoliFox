// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 展示实例资源包或光影包列表及其操作状态。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.GameInstance
import dev.molifox.core.JobStatus
import dev.molifox.core.LocalPackEntry
import dev.molifox.core.PackFolder
import dev.molifox.core.PackUpdateStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
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
    var deleting by remember { mutableStateOf<LocalPackEntry?>(null) }
    var watchingJob by remember(instance.id, folder) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val liveState by actions.controller.state.collectAsState()
    val busy = actions.controller.isInstanceBusy(instance.id)
    val checking = updates.packsChecking
    val report = updates.packs
    val reportItems = report?.items(folder).orEmpty()
    // 列表还没读出来时不按文件名过滤，避免先闪一下「全部为最新」。
    val visibleItems =
        if (entries.isEmpty()) {
            reportItems
        } else {
            val installed = entries.mapTo(HashSet()) { it.filename }
            reportItems.filter { it.filename in installed }
        }
    val itemsByFile = reportItems.associateBy { it.filename }
    val updatable = visibleItems.count { it.status == PackUpdateStatus.Updatable }
    val uncertain = visibleItems.count { it.status == PackUpdateStatus.Unknown }
    val statusText =
        when {
            checking -> "正在检查更新…"
            report == null -> ""
            updatable > 0 ->
                "$updatable 项可更新" + if (uncertain > 0) " · $uncertain 项无法确认" else ""
            uncertain > 0 -> "$uncertain 项无法确认"
            else -> "全部为最新"
        }
    LaunchedEffect(instance.id, folder, refresh, liveState.jobs.map { it.id + ":" + it.status }) {
        loading = true
        error = null
        try {
            entries =
                withContext(Dispatchers.IO) { actions.controller.listPacks(instance.id, folder) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            entries = emptyList()
            error = failure.message
        } finally {
            loading = false
        }
    }
    // 导入与删除都是任务中心里的后台任务；结束后刷新列表，失败原因留在工具行下方。
    LaunchedEffect(watchingJob, liveState.jobs.map { it.id + ":" + it.status }) {
        val id = watchingJob ?: return@LaunchedEffect
        val job = liveState.jobs.firstOrNull { it.id == id } ?: return@LaunchedEffect
        when (job.status) {
            JobStatus.Failed -> {
                actionError = job.error ?: "操作失败，详情见日志"
                watchingJob = null
            }
            JobStatus.Completed,
            JobStatus.Cancelled,
            -> {
                watchingJob = null
                refresh++
            }
            else -> Unit
        }
    }
    val startImport: (List<Path>) -> Unit = { chosen ->
        actionError = null
        if (chosen.isNotEmpty()) {
            try {
                watchingJob = actions.controller.importPacks(instance.id, folder, chosen)
            } catch (failure: Exception) {
                actionError = failure.message ?: "无法导入"
            }
        }
    }
    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("移入回收站？") },
            text = { Text("「${entry.filename}」会移到回收站，需要时可以再找回。") },
            confirmButton = {
                TextButton({
                    deleting = null
                    actionError = null
                    actions.run {
                        actions.controller.requireIdle(instance.id)
                        val removed =
                            runCatching {
                                actions.controller.deletePack(
                                    instance.id,
                                    folder,
                                    entry.filename,
                                )
                            }.getOrElse { failure ->
                                withContext(Dispatchers.Swing) {
                                    actionError = failure.message ?: "无法删除"
                                }
                                false
                            }
                        withContext(Dispatchers.Swing) {
                            if (!removed && actionError == null) {
                                actionError =
                                    "「${entry.filename}」没有移入回收站：文件可能已不存在，" +
                                    "或回收站不可用；详情见日志。"
                            }
                            // 报告里的旧条目同步移除，避免「N 项可更新」把已删除的文件算进去。
                            if (removed) {
                                updates.packs?.let { current ->
                                    updates.packs =
                                        current.copy(
                                            items =
                                                current.items.filterNot {
                                                    it.folder == folder &&
                                                        it.filename == entry.filename
                                                },
                                        )
                                }
                            }
                            refresh++
                        }
                    }
                }) {
                    Text("移至回收站", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton({ deleting = null }) { Text("取消") } },
        )
    }
    Column(
        modifier
            .fillMaxHeight()
            .contentDropTarget(
                enabled = !busy,
                hint = "请拖入 .zip 或文件夹",
                onError = { actionError = it },
            ) { dropped -> startImport(dropped) },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ContentToolbar(
            flexible = {
                ToolbarSearchField(query, { query = it }, "搜索已安装${folder.title}")
            },
            refresh =
                ToolbarAction("刷新", icon = Icons.Outlined.Refresh, enabled = !busy) { refresh++ },
            importAction =
                ToolbarAction("导入", icon = Icons.Outlined.FileDownload, enabled = !busy) {
                    actions.run {
                        val chosen =
                            actions.choose(
                                "导入${folder.title}（.zip）",
                                multiple = true,
                                extensions = listOf("zip"),
                            )
                        withContext(Dispatchers.Swing) { startImport(chosen) }
                    }
                },
            openFolder =
                ToolbarAction("打开文件夹", icon = Icons.Outlined.FolderOpen, enabled = !busy) {
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
        if (checking) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
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
                TextButton(
                    {
                        updates.packsError = null
                        updates.checkPacks(scope)
                    },
                    enabled = !busy && !checking,
                ) {
                    Text("重新检查", fontSize = 12.sp)
                }
            }
        }
        actionError?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
        val filtered =
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
        when {
            loading && entries.isEmpty() -> ListSkeleton()
            error != null ->
                Panel(Modifier.fillMaxWidth()) {
                    EmptyState("无法读取${folder.title}目录", error.orEmpty(), Icons.Outlined.CloudOff) {
                        TextButton({ refresh++ }) { Text("重试") }
                    }
                }
            entries.isEmpty() ->
                Panel(Modifier.fillMaxWidth()) {
                    EmptyState(
                        "还没有${folder.title}",
                        "点「导入」选择 .zip，或把 .zip / 文件夹直接拖进这个页面。",
                        if (folder == PackFolder.ShaderPacks) {
                            Icons.Outlined.AutoAwesome
                        } else {
                            Icons.Outlined.Palette
                        },
                    )
                }
            filtered.isEmpty() ->
                Panel(Modifier.fillMaxWidth()) {
                    EmptyState(
                        if (chip != ContentChip.All && query.isBlank()) {
                            "没有「${chip.title}」的${folder.title}"
                        } else {
                            "没有匹配的${folder.title}"
                        },
                        if (chip != ContentChip.All && query.isBlank()) {
                            "切换到「全部」查看全部内容。"
                        } else {
                            "换个关键词试试"
                        },
                        Icons.Outlined.SearchOff,
                    ) {
                        TextButton({
                            query = ""
                            chip = ContentChip.All
                        }) {
                            Text("清除筛选")
                        }
                    }
                }
            else ->
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(filtered, key = { it.filename }) { entry ->
                        PackRow(
                            entry = entry,
                            folder = folder,
                            update = itemsByFile[entry.filename],
                            busy = busy,
                            iconRevision = refresh,
                            readIcon = { path ->
                                actions.controller.content.packIcon(
                                    instance.id,
                                    path,
                                    folder,
                                )
                            },
                            onIconFailure = {
                                actions.controller.store.log(
                                    "本地包头像解码失败：${it.message}",
                                    true,
                                )
                            },
                            onDelete = { deleting = entry },
                        )
                    }
                }
        }
        Text(
            "共 ${entries.size} 项 · ${folder.directory}",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
