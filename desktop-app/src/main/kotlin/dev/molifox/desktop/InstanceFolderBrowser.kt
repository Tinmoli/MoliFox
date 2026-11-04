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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.onClick
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import dev.molifox.desktop.MoliTextButton as TextButton
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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.GameInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
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
    multiSelect: Boolean = false,
) {
    var entries by remember { mutableStateOf<List<FolderEntry>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    // 目录读取期间先显示骨架，读完再决定空态，避免空态闪一下被误判成“目录是空的”。
    var loading by remember(instance.id, folder) { mutableStateOf(true) }
    // 子目录浏览只在前端维护，不改实例目录结构；返回上一级时清空栈。
    var subPath by remember(instance.id, folder) { mutableStateOf<List<String>>(emptyList()) }
    var deletingEntry by remember(instance.id, folder, subPath) {
        mutableStateOf<FolderEntry?>(null)
    }
    var renamingEntry by remember(instance.id, folder, subPath) {
        mutableStateOf<FolderEntry?>(null)
    }
    // 选区只在原理图这类支持多选的目录启用，切换目录层时整体重置，避免跨目录残留。
    val selection =
        remember(instance.id, folder, subPath) {
            if (multiSelect) ManagedSelection() else null
        }
    var deletingSelected by remember(instance.id, folder, subPath) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val busy = actions.controller.isInstanceBusy(instance.id)
    val strings = LocalStrings.current
    val liveState by actions.controller.state.collectAsState()
    val root =
        actions.controller.store
            .gamePath(instance)
            .resolve(folder)
    val directory =
        if (subPath.isEmpty()) {
            root
        } else {
            subPath.fold(root) { path, segment -> path.resolve(segment) }
        }
    val filtered = entries.filter { query.isBlank() || it.path.name.contains(query, true) }
    // 选区用文件名作稳定键：同一目录内文件名唯一，跨目录切换会重建选区。
    val keys = filtered.map { it.path.name }
    LaunchedEffect(keys) { selection?.retain(keys) }
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
    // 原理图删除会移入回收站，这里先确认再执行；文件夹与文件走同一条删除流程。
    SchematicEntryDialogs(
        instance = instance,
        actions = actions,
        subPath = subPath,
        deletingEntry = deletingEntry,
        renamingEntry = renamingEntry,
        onDismissDelete = { deletingEntry = null },
        onDismissRename = { renamingEntry = null },
        onChanged = { refresh++ },
    )
    if (deletingSelected) {
        FolderBatchDeleteConfirmation(
            selection = selection,
            filtered = filtered,
            subPath = subPath,
            instance = instance,
            actions = actions,
            onDismiss = { deletingSelected = false },
            onDelete = { refresh++ },
        )
    }
    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FolderToolbar(
            instance = instance,
            actions = actions,
            folder = folder,
            subPath = subPath,
            importLabel = importLabel,
            importExtensions = importExtensions,
            busy = busy,
            query = query,
            onBack = {
                subPath = subPath.dropLast(1)
                query = ""
            },
            onQuery = { query = it },
            onRefresh = { refresh++ },
        )
        FolderLocationRow(directory, allowDelete, multiSelect)
        if (loading && entries.isNotEmpty()) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
        }
        // 空目录文案区分「目录本身为空」和「子目录为空」。
        val emptyFolderTitle =
            strings.t(if (subPath.isEmpty()) "pages.folder.empty.root" else "pages.folder.empty.sub")
        val list: @Composable () -> Unit = {
            FolderEntryList(
                entries = entries,
                filtered = filtered,
                loading = loading,
                error = error,
                emptyFolderTitle = emptyFolderTitle,
                allowDelete = allowDelete,
                busy = busy,
                selection = selection,
                listState = listState,
                onToggleSelect = { entry -> selection?.toggle(entry.path.name) },
                onOpen = { entry ->
                    if (entry.isDirectory) {
                        subPath = subPath + entry.path.name
                        query = ""
                    }
                },
                onDelete = { entry -> deletingEntry = entry },
                onRename = { entry -> renamingEntry = entry },
                onReveal = { entry ->
                    actions.run { actions.controller.platform.openFolder(revealTargetOf(entry, directory)) }
                },
                onRetry = { refresh++ },
                onClearSearch = { query = "" },
            )
        }
        if (selection != null) {
            FolderSelectionActions(
                selection = selection,
                keys = keys,
                busy = busy,
                onReveal = {
                    firstSelectedEntry(filtered, selection)?.let { entry ->
                        actions.run { actions.controller.platform.openFolder(revealTargetOf(entry, directory)) }
                    }
                },
                onDelete = { deletingSelected = true },
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
): Result<List<FolderEntry>> = actions.controller.instanceWorkspace.listDirectory(instanceId, folder, subPath)

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
    selection: ManagedSelection?,
    listState: LazyListState,
    onToggleSelect: (FolderEntry) -> Unit,
    onOpen: (FolderEntry) -> Unit,
    onDelete: (FolderEntry) -> Unit,
    onRename: (FolderEntry) -> Unit,
    onReveal: (FolderEntry) -> Unit,
    onRetry: () -> Unit,
    onClearSearch: () -> Unit,
) {
    val strings = LocalStrings.current
    when {
        loading && entries.isEmpty() -> ListSkeleton()

        error != null ->
            Panel(Modifier.fillMaxWidth()) {
                EmptyState(
                    strings.t("pages.folder.error.title"),
                    error.orEmpty(),
                    Icons.Outlined.CloudOff,
                ) {
                    TextButton(onRetry) { Text(strings.t("pages.folder.error.retry")) }
                }
            }

        entries.isEmpty() ->
            Panel(Modifier.fillMaxWidth()) {
                EmptyState(
                    emptyFolderTitle,
                    strings.t("pages.folder.empty.body"),
                    Icons.Outlined.FolderOpen,
                )
            }

        filtered.isEmpty() ->
            Panel(Modifier.fillMaxWidth()) {
                EmptyState(
                    strings.t("pages.folder.filtered.title"),
                    strings.t("pages.folder.filtered.body"),
                    Icons.Outlined.SearchOff,
                ) {
                    TextButton(onClearSearch) {
                        Text(strings.t("pages.folder.filtered.clear"))
                    }
                }
            }

        else ->
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .then(
                        if (selection == null) {
                            Modifier
                        } else {
                            Modifier.dragSelectRows(selection, filtered.map { it.path.name })
                        },
                    ),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(filtered, key = { it.path.toString() }) { entry ->
                    FolderRow(
                        entry,
                        modifier =
                            Modifier.animateItem().then(
                                if (selection == null) {
                                    Modifier
                                } else {
                                    Modifier.onGloballyPositioned { coordinates ->
                                        selection.bounds[entry.path.name] = coordinates.boundsInWindow()
                                    }
                                },
                            ),
                        selected = selection != null && entry.path.name in selection.selected,
                        selectionActive = selection?.active == true,
                        toggleOnClick = selection != null,
                        allowDelete = allowDelete,
                        onOpen = { onOpen(entry) },
                        onToggleSelect = { onToggleSelect(entry) },
                        onDelete = { onDelete(entry) },
                        onRename = { onRename(entry) },
                        onReveal = { onReveal(entry) },
                        busy = busy,
                    )
                }
            }
    }
}

@Composable
private fun FolderRow(
    entry: FolderEntry,
    modifier: Modifier,
    selected: Boolean,
    selectionActive: Boolean,
    toggleOnClick: Boolean,
    allowDelete: Boolean,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onReveal: () -> Unit,
    busy: Boolean,
) {
    val shape = RoundedCornerShape(Metrics.cardRadius)
    val strings = LocalStrings.current
    val density = LocalDensity.current
    var menuOffset by remember(entry.path) { mutableStateOf<DpOffset?>(null) }
    Box(modifier.fillMaxWidth()) {
        Surface(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .then(
                    Modifier.schematicContextClick(
                        key = entry.path.toString(),
                        enabled = allowDelete,
                        density = density,
                    ) { menuOffset = it },
                ).pressScale(),
            shape = shape,
            color = if (selected) selectedRowBackground() else MaterialTheme.colorScheme.surface,
            border =
                BorderStroke(
                    if (selected) 1.5.dp else 1.dp,
                    if (selected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = .85f)
                    } else {
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)
                    },
                ),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .hoverHighlight(shape, alpha = .04f)
                    .folderRowClick(
                        isDirectory = entry.isDirectory,
                        selectionActive = selectionActive,
                        toggleOnClick = toggleOnClick,
                        onOpen = onOpen,
                        onToggleSelect = onToggleSelect,
                    ).padding(horizontal = 10.dp, vertical = 6.dp),
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
                            if (entry.isDirectory) {
                                strings.t("pages.folder.row.directory")
                            } else {
                                formatFileSize(entry.sizeBytes)
                            },
                            entry.modified
                                ?.let {
                                    strings.t(
                                        "pages.folder.row.modified",
                                        it.toString().replace('T', ' ').take(16),
                                    )
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
                if (allowDelete && !entry.isDirectory) {
                    RowIconAction(
                        Icons.Outlined.DeleteOutline,
                        strings.t("pages.folder.row.delete", entry.path.name),
                        enabled = !busy,
                        onClick = onDelete,
                    )
                }
            }
        }
        FolderEntryContextMenu(
            expanded = menuOffset != null,
            offset = menuOffset ?: DpOffset.Zero,
            busy = busy,
            onDismiss = { menuOffset = null },
            onRename = onRename,
            onReveal = onReveal,
            onDelete = onDelete,
        )
    }
}

/** 右键菜单：只负责展开状态与指针位置，条目的打开位置/重命名/删除由调用方处理。 */
@Composable
private fun FolderEntryContextMenu(
    expanded: Boolean,
    offset: DpOffset,
    busy: Boolean,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onReveal: () -> Unit,
    onDelete: () -> Unit,
) {
    val strings = LocalStrings.current
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = offset,
    ) {
        DropdownMenuItem(
            text = { Text(strings.t("content.menu.openFolder"), fontSize = 13.sp) },
            onClick = {
                onDismiss()
                onReveal()
            },
            enabled = !busy,
        )
        DropdownMenuItem(
            text = { Text(strings.t("pages.folder.menu.rename"), fontSize = 13.sp) },
            onClick = {
                onDismiss()
                onRename()
            },
            enabled = !busy,
        )
        DropdownMenuItem(
            text = {
                Text(
                    strings.t("pages.folder.menu.delete"),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            },
            onClick = {
                onDismiss()
                onDelete()
            },
            enabled = !busy,
        )
    }
}

/** 把行内右键位置换算成 DpOffset；未允许多选删除时不接管指针事件。 */
private fun Modifier.schematicContextClick(
    key: String,
    enabled: Boolean,
    density: Density,
    onOffset: (DpOffset) -> Unit,
): Modifier =
    if (!enabled) {
        this
    } else {
        pointerInput(key) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (
                        event.type == PointerEventType.Press &&
                        event.buttons.isSecondaryPressed
                    ) {
                        onOffset(
                            event.changes.firstOrNull()?.let { change ->
                                with(density) {
                                    DpOffset(
                                        change.position.x.toDp(),
                                        change.position.y.toDp(),
                                    )
                                }
                            } ?: DpOffset.Zero,
                        )
                        event.changes.forEach { it.consume() }
                    }
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

/** 多选栏：在文件管理器里定位首个选中条目，或一次把选中的条目移入回收站。 */
@Composable
private fun FolderSelectionActions(
    selection: ManagedSelection,
    keys: List<String>,
    busy: Boolean,
    onReveal: () -> Unit,
    onDelete: () -> Unit,
) {
    val strings = LocalStrings.current
    SelectionActionBar(
        selection = selection,
        keys = keys,
        actions =
            listOf(
                ManagedAction(
                    strings.t("content.menu.openFolder"),
                    Icons.Outlined.FolderOpen,
                ) { onReveal() },
                ManagedAction(
                    strings.t("content.menu.delete"),
                    Icons.Outlined.DeleteOutline,
                    enabled = !busy,
                ) { onDelete() },
            ),
    )
}

/** 批量删除确认：一次移入回收站，条目数量在文案里说明。 */
@Composable
private fun FolderBatchDeleteConfirmation(
    selection: ManagedSelection?,
    filtered: List<FolderEntry>,
    subPath: List<String>,
    instance: GameInstance,
    actions: UiActions,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    if (selection == null) return
    SchematicBatchDeleteDialog(
        count = selection.selected.size,
        onDismiss = onDismiss,
        onConfirm = {
            onDismiss()
            val chosen = filtered.filter { it.path.name in selection.selected }
            selection.clear()
            actions.run {
                actions.controller.requireIdle(instance.id)
                chosen.forEach { entry ->
                    actions.controller.content.deleteSchematicEntry(
                        instance.id,
                        schematicRelativePath(entry, subPath),
                    )
                }
                withContext(Dispatchers.Swing) { onDelete() }
            }
        },
    )
}
