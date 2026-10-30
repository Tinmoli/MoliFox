// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 展示选中存档的数据包列表与操作状态。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderZip
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.DatapackEntry
import dev.molifox.core.DatapackListing
import dev.molifox.core.GameInstance
import dev.molifox.core.SaveEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * 数据包页签：先选世界，再列出 `<世界>/datapacks/` 里的压缩包与文件夹（名称、大小、启用状态）。
 *
 * 支持安装 `.zip`（校验 `pack.mcmeta` 与 `pack_format`）、删除到回收站、打开文件夹、启用/禁用
 * （写 `level.dat` 的 `Data.Packs`）。`level.dat` 无法解析或世界正被打开时，开关禁用并在顶部说明原因，
 * 宁可只读也不冒险改世界。
 */
@Composable
internal fun InstanceDatapacksTab(
    instance: GameInstance,
    actions: UiActions,
    modifier: Modifier,
) {
    var worlds by remember(instance.id) { mutableStateOf<List<SaveEntry>>(emptyList()) }
    var world by remember(instance.id) { mutableStateOf("") }
    var listing by remember(instance.id) { mutableStateOf<DatapackListing?>(null) }
    var loading by remember(instance.id) { mutableStateOf(true) }
    var loadError by remember(instance.id) { mutableStateOf<String?>(null) }
    var actionError by remember(instance.id) { mutableStateOf<String?>(null) }
    var refresh by remember(instance.id) { mutableIntStateOf(0) }
    var query by remember(instance.id) { mutableStateOf("") }
    var deleting by remember { mutableStateOf<DatapackEntry?>(null) }
    var working by remember(instance.id) { mutableStateOf<String?>(null) }
    val busy = actions.controller.isInstanceBusy(instance.id)
    val running = actions.controller.game.isRunning(instance.id)
    val canEdit = !busy && !running
    val blockedNote =
        when {
            running -> "游戏正在使用存档，请先退出游戏再管理数据包。"
            busy -> "任务进行中，数据包操作暂时不可用。"
            else -> ""
        }
    val savesDirectory =
        actions.controller.store
            .instancePath(instance.id)
            .resolve("saves")
    val datapacksDirectory = savesDirectory.resolve(world).resolve("datapacks")
    // 数据包只属于真实世界目录，`saves/` 里的 .zip 存档没有可写的 datapacks 目录。
    LaunchedEffect(instance.id, refresh) {
        try {
            worlds =
                withContext(Dispatchers.IO) {
                    actions.controller.listSaves(instance.id).filter {
                        it.path.isDirectory() && it.hasLevelDat
                    }
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            worlds = emptyList()
            loadError = failure.message ?: "无法读取存档目录"
        }
        if (worlds.none { it.name == world }) world = worlds.firstOrNull()?.name.orEmpty()
    }
    LaunchedEffect(instance.id, world, refresh) {
        listing = null
        if (world.isBlank()) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        loadError = null
        try {
            listing =
                withContext(Dispatchers.IO) {
                    actions.controller.listDatapacks(instance.id, world)
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            loadError = failure.message ?: "无法读取数据包"
        } finally {
            loading = false
        }
    }
    val install = {
        actionError = null
        actions.run {
            val chosen =
                actions.choose("选择数据包 ZIP", extensions = listOf("zip")).firstOrNull()
            if (chosen != null) {
                try {
                    withContext(Dispatchers.IO) {
                        actions.controller.installDatapackFile(instance.id, world, chosen)
                    }
                    withContext(Dispatchers.Swing) { refresh++ }
                } catch (failure: Exception) {
                    withContext(Dispatchers.Swing) {
                        actionError = failure.message ?: "安装数据包失败"
                    }
                }
            }
        }
    }
    val toggle = { entry: DatapackEntry, enabled: Boolean ->
        actionError = null
        actions.run {
            withContext(Dispatchers.Swing) { working = entry.name }
            try {
                withContext(Dispatchers.IO) {
                    actions.controller.setDatapackEnabled(instance.id, world, entry.name, enabled)
                }
                withContext(Dispatchers.Swing) { refresh++ }
            } catch (failure: Exception) {
                withContext(Dispatchers.Swing) {
                    actionError = failure.message ?: "切换数据包状态失败"
                }
            } finally {
                withContext(Dispatchers.Swing) { working = null }
            }
        }
    }
    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("移入回收站？") },
            text = { Text("「${entry.name}」会从该存档的 datapacks 目录移入回收站，可从回收站恢复。") },
            confirmButton = {
                TextButton({
                    deleting = null
                    actionError = null
                    actions.run {
                        val moved =
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    actions.controller.deleteDatapack(
                                        instance.id,
                                        world,
                                        entry.name,
                                    )
                                }.getOrElse { failure ->
                                    withContext(Dispatchers.Swing) {
                                        actionError =
                                            failure.message ?: "删除数据包失败，详情见日志"
                                    }
                                    false
                                }
                            }
                        withContext(Dispatchers.Swing) {
                            if (!moved && actionError == null) {
                                actionError = "「${entry.name}」没有移入回收站：文件可能已不存在，或回收站不可用。"
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
    val current = listing
    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ContentToolbar(
            flexible = {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "世界",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CompactSelect(
                        world,
                        if (worlds.isEmpty()) {
                            listOf("" to "此实例还没有世界")
                        } else {
                            worlds.map { it.name to it.name }
                        },
                        { world = it },
                        Modifier.weight(1f).height(ContentToolbarMetrics.height),
                        enabled = worlds.isNotEmpty() && !busy,
                    )
                }
            },
            importAction =
                ToolbarAction(
                    "安装数据包",
                    icon = Icons.Outlined.FileDownload,
                    enabled = world.isNotBlank() && canEdit,
                ) { install() },
            refresh =
                ToolbarAction("刷新", icon = Icons.Outlined.Refresh, enabled = !busy) {
                    actionError = null
                    refresh++
                },
            openFolder =
                ToolbarAction(
                    "打开文件夹",
                    icon = Icons.Outlined.FolderOpen,
                    enabled = world.isNotBlank() && !busy,
                ) {
                    actions.run { actions.controller.openWorldDatapacks(instance.id, world) }
                },
        )
        Text(
            if (world.isBlank()) {
                "$savesDirectory"
            } else {
                "${current?.entries?.size ?: 0} 个数据包 · $datapacksDirectory"
            },
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
        if (loading && current != null) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
        }
        when {
            loadError != null ->
                Panel(Modifier.fillMaxWidth()) {
                    EmptyState("无法读取数据包", loadError.orEmpty(), Icons.Outlined.CloudOff) {
                        TextButton({ refresh++ }) { Text("重试") }
                    }
                }
            worlds.isEmpty() && !loading ->
                Panel(Modifier.fillMaxWidth()) {
                    EmptyState(
                        "这个实例还没有世界",
                        "先创建或复制一个世界，再回来管理它的数据包。",
                        Icons.Outlined.Save,
                    )
                }
            current != null && current.entries.isEmpty() ->
                Panel(Modifier.fillMaxWidth()) {
                    EmptyState(
                        "这个存档还没有数据包",
                        "点「安装数据包」选一个 .zip；也可以把文件夹放进 datapacks/。",
                        Icons.Outlined.FolderZip,
                    )
                }
            current != null -> {
                val filtered =
                    current.entries.filter { query.isBlank() || it.name.contains(query, true) }
                ScrollColumn(Modifier.weight(1f).fillMaxWidth()) {
                    if (current.note.isNotBlank()) {
                        Text(
                            current.note,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (filtered.isEmpty()) {
                        Panel(Modifier.fillMaxWidth()) {
                            EmptyState("没有匹配的数据包", "换个关键词试试", Icons.Outlined.SearchOff) {
                                TextButton({ query = "" }) { Text("清除搜索") }
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            filtered.forEach { entry ->
                                key(entry.name) {
                                    DatapackRow(
                                        entry = entry,
                                        toggleEnabled =
                                            current.toggleAvailable &&
                                                canEdit &&
                                                working == null &&
                                                entry.enabled != null,
                                        onToggle = { enabled -> toggle(entry, enabled) },
                                        deleteEnabled = canEdit && working == null,
                                        onDelete = { deleting = entry },
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
