// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 展示实例目录查询结果、文件条目与加载状态。

package dev.molifox.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.onClick
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.GameInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import dev.molifox.core.data.instance.InstanceDirectoryEntry as FolderEntry

private const val BYTES_PER_KB = 1024f
private const val BYTES_PER_MB = 1048576f

/** 内容文件夹浏览：原理图专用（可导入、可逐级进入、可删除）；大小与修改时间来自文件系统。 */
@Composable
internal fun InstanceFolderBrowser(
    instance: GameInstance,
    actions: UiActions,
    folder: String,
    modifier: Modifier,
    importExtensions: List<String> = emptyList(),
    importLabel: String = "",
    allowDelete: Boolean = false,
) {
    var entries by remember { mutableStateOf<List<FolderEntry>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    // 目录读取期间先显示骨架，读完再决定空态，避免空态闪一下被误判成“目录是空的”。
    var loading by remember(instance.id, folder) { mutableStateOf(true) }
    var deletingFile by remember { mutableStateOf<Path?>(null) }
    // 子目录浏览只在前端维护，不改实例目录结构；返回上一级时清空栈。
    var subPath by remember(instance.id, folder) { mutableStateOf<List<String>>(emptyList()) }
    val busy = actions.controller.isInstanceBusy(instance.id)
    val liveState by actions.controller.state.collectAsState()
    val root =
        actions.controller.store
            .instancePath(instance.id)
            .resolve(folder)
    val directory =
        if (subPath.isEmpty()) {
            root
        } else {
            subPath.fold(root) { path, segment -> path.resolve(segment) }
        }
    LaunchedEffect(instance.id, folder, refresh, liveState.jobs.map { it.status }, directory) {
        loading = true
        error = null
        loadFolderEntries(actions, instance.id, folder, subPath)
            .onSuccess { entries = it }
            .onFailure { failure ->
                entries = emptyList()
                error = failure.message
            }
        loading = false
    }
    // 原理图删除会移入回收站，这里先确认再执行。
    deletingFile?.let { file ->
        SchematicTrashDialog(
            file = file,
            onDismiss = { deletingFile = null },
            onConfirm = {
                val name = file.name
                deletingFile = null
                actions.run {
                    actions.controller.requireIdle(instance.id)
                    actions.controller.content.deleteSchematic(instance.id, name)
                    withContext(Dispatchers.Swing) { refresh++ }
                }
            },
        )
    }
    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ContentToolbar(
            leading = {
                if (subPath.isNotEmpty()) {
                    ToolbarIconAction(Icons.Outlined.ArrowUpward, "返回上一级目录") {
                        subPath = subPath.dropLast(1)
                        query = ""
                    }
                }
            },
            flexible = { ToolbarSearchField(query, { query = it }, "在文件夹内搜索") },
            importAction =
                ToolbarAction(
                    importLabel.ifBlank { "导入" },
                    icon = Icons.Outlined.FileDownload,
                    enabled = !busy,
                ) {
                    actions.run {
                        val chosen =
                            actions.choose(
                                importLabel.ifBlank { "选择文件" },
                                multiple = true,
                                extensions = importExtensions,
                            )
                        if (chosen.isNotEmpty()) {
                            actions.controller.content.importSchematics(instance.id, chosen)
                            withContext(Dispatchers.Swing) { refresh++ }
                        }
                    }
                },
            refresh =
                ToolbarAction("刷新", icon = Icons.Outlined.Refresh, enabled = !busy) { refresh++ },
            openFolder =
                ToolbarAction("打开文件夹", icon = Icons.Outlined.FolderOpen) {
                    actions.run { actions.controller.openFolder(instance.id, folder) }
                },
        )
        Text(
            directory.toString(),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (loading && entries.isNotEmpty()) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
        }
        val filtered = entries.filter { query.isBlank() || it.path.name.contains(query, true) }
        // 空目录文案区分「目录本身为空」和「子目录为空」。
        val emptyFolderTitle = if (subPath.isEmpty()) "这个目录还没有文件" else "这个子目录是空的"
        val list: @Composable () -> Unit = {
            FolderEntryList(
                entries = entries,
                filtered = filtered,
                loading = loading,
                error = error,
                emptyFolderTitle = emptyFolderTitle,
                allowDelete = allowDelete,
                busy = busy,
                onOpen = { entry ->
                    if (entry.isDirectory) {
                        subPath = subPath + entry.path.name
                        query = ""
                    }
                },
                onDelete = { entry -> deletingFile = entry.path },
                onRetry = { refresh++ },
                onClearSearch = { query = "" },
            )
        }
        AnimatedFolderContent(subPath, list)
    }
}

/** 读取当前目录条目：网络无关，失败信息交给调用方提示。 */
private suspend fun loadFolderEntries(
    actions: UiActions,
    instanceId: String,
    folder: String,
    subPath: List<String>,
): Result<List<FolderEntry>> =
    actions.controller.instanceWorkspace.listDirectory(instanceId, folder, subPath)

/** 进入 / 返回子目录时整块淡入并轻微位移 4dp，告诉用户“换了一层目录”。 */
@Composable
private fun AnimatedFolderContent(
    subPath: List<String>,
    content: @Composable () -> Unit,
) {
    if (LocalAnimationsEnabled.current) {
        AnimatedContent(
            subPath,
            label = "folder-drill",
            transitionSpec = {
                (
                    fadeIn(tween(150)) +
                        slideInHorizontally(tween(150)) { it / 24 }
                ) togetherWith
                    fadeOut(tween(120))
            },
        ) {
            Box(Modifier.fillMaxSize()) { content() }
        }
    } else {
        Box(Modifier.fillMaxSize()) { content() }
    }
}

/** 目录条目列表与骨架、错误、空态：列表常驻，只有筛选后为空时才提示换关键词。 */
@Composable
private fun FolderEntryList(
    entries: List<FolderEntry>,
    filtered: List<FolderEntry>,
    loading: Boolean,
    error: String?,
    emptyFolderTitle: String,
    allowDelete: Boolean,
    busy: Boolean,
    onOpen: (FolderEntry) -> Unit,
    onDelete: (FolderEntry) -> Unit,
    onRetry: () -> Unit,
    onClearSearch: () -> Unit,
) {
    when {
        loading && entries.isEmpty() -> ListSkeleton()
        error != null ->
            Panel(Modifier.fillMaxWidth()) {
                EmptyState("无法读取这个目录", error.orEmpty(), Icons.Outlined.CloudOff) {
                    TextButton(onRetry) { Text("重试") }
                }
            }
        entries.isEmpty() ->
            Panel(Modifier.fillMaxWidth()) {
                EmptyState(
                    emptyFolderTitle,
                    "游戏生成或安装的内容会显示在这里。",
                    Icons.Outlined.FolderOpen,
                )
            }
        filtered.isEmpty() ->
            Panel(Modifier.fillMaxWidth()) {
                EmptyState("没有匹配的文件", "换个关键词试试", Icons.Outlined.SearchOff) {
                    TextButton(onClearSearch) { Text("清除搜索") }
                }
            }
        else ->
            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(filtered, key = { it.path.toString() }) { entry ->
                    FolderRow(
                        entry,
                        modifier = Modifier.animateItem(),
                        allowDelete = allowDelete && !entry.isDirectory,
                        onOpen = { onOpen(entry) },
                        onDelete = { onDelete(entry) },
                        busy = busy,
                    )
                }
            }
    }
}

/** 说明「移至回收站」的后果，并给出确认与取消两个动作。 */
@Composable
private fun SchematicTrashDialog(
    file: Path,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移至回收站？") },
        text = { Text("「" + file.name + "」会从原理图目录移至回收站，需要时可以再找回。") },
        confirmButton = {
            TextButton(onConfirm) {
                Text("移至回收站", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}

@Composable
private fun FolderRow(
    entry: FolderEntry,
    modifier: Modifier,
    allowDelete: Boolean,
    busy: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val shape = RoundedCornerShape(Metrics.cardRadius)
    Surface(
        modifier.fillMaxWidth().heightIn(min = 52.dp).pressScale(),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .hoverHighlight(shape, alpha = .04f)
                .then(if (entry.isDirectory) Modifier.clickable(onClick = onOpen) else Modifier)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (entry.isDirectory) Icons.Outlined.Folder else Icons.Outlined.Description,
                null,
                Modifier.size(19.dp),
                MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    entry.path.name,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(
                        if (entry.isDirectory) "文件夹" else formatFileSize(entry.sizeBytes),
                        entry.modified
                            ?.let {
                                "修改于 " + it.toString().replace('T', ' ').take(16)
                            }.orEmpty(),
                    ).filter { it.isNotBlank() }
                        .joinToString(" · "),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (entry.isDirectory) {
                Icon(
                    Icons.Outlined.ChevronRight,
                    null,
                    Modifier.size(16.dp),
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (allowDelete) {
                RowIconAction(
                    Icons.Outlined.DeleteOutline,
                    "移至回收站 " + entry.path.name,
                    enabled = !busy,
                    onClick = onDelete,
                )
            }
        }
    }
}

internal fun formatFileSize(bytes: Long): String =
    when {
        bytes >= BYTES_PER_MB -> "%.1f MB".format(bytes / BYTES_PER_MB)
        bytes >= BYTES_PER_KB -> "%.1f KB".format(bytes / BYTES_PER_KB)
        else -> bytes.toString() + " B"
    }

/** 列表骨架：3 行灰条，首次加载时占位，避免内容区高度跳变。 */
@Composable
internal fun ListSkeleton() {
    val bar = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .16f)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(19.dp).clip(RoundedCornerShape(4.dp)).background(bar))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth(.4f)
                            .height(12.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(bar),
                    )
                    Box(
                        Modifier
                            .fillMaxWidth(.7f)
                            .height(9.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(bar),
                    )
                }
            }
        }
    }
}
