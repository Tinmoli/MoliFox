// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 展示一个存档的数据包列表与操作状态，供存档详情页嵌入。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.DatapackEntry
import dev.molifox.core.DatapackListing
import dev.molifox.core.GameInstance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 存档详情里的「数据包」页：只管理 [world] 的 `datapacks/`。
 *
 * 数据包属于具体世界，所以这里不再选择存档；安装、启停、删除与打开文件夹的行为与原来的数据包页一致。
 * `level.dat` 无法解析或世界正被打开时，开关禁用并说明原因。
 */
@Composable
internal fun SaveDatapackPanel(
    instance: GameInstance,
    world: String,
    actions: UiActions,
    modifier: Modifier = Modifier,
) {
    val state = remember(instance.id, world) { DatapackPanelState(world) }
    var deleting by remember { mutableStateOf<DatapackEntry?>(null) }
    LaunchedEffect(instance.id, world, state.refresh) {
        state.load(actions, instance.id)
    }
    SaveDatapackBody(instance, actions, state, modifier) { deleting = it }
    deleting?.let { entry ->
        DeleteDatapackDialog(
            entry = entry,
            onDismiss = { deleting = null },
            onConfirm = {
                deleting = null
                state.actionError = null
                actions.run { state.deleteDatapack(actions, instance, entry) }
            },
        )
    }
}

/** 数据包页的状态：世界名固定，只加载它自己的列表。 */
private class DatapackPanelState(
    val world: String,
) {
    var listing by mutableStateOf<DatapackListing?>(null)
    var loading by mutableStateOf(true)
    var loadError by mutableStateOf<String?>(null)
    var actionError by mutableStateOf<String?>(null)
    var refresh by mutableIntStateOf(0)
    var working by mutableStateOf<String?>(null)

    /** 读取当前存档的数据包列表；失败只提示，不改动磁盘。 */
    suspend fun load(
        actions: UiActions,
        instanceId: String,
    ) {
        loading = true
        loadError = null
        try {
            listing =
                withContext(Dispatchers.IO) {
                    actions.controller.listDatapacks(instanceId, world)
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            loadError = failure.message ?: tr("saveA1.datapackPanel.loadFailed")
        } catch (failure: IllegalArgumentException) {
            loadError = failure.message ?: tr("saveA1.datapackPanel.loadFailed")
        } catch (failure: IllegalStateException) {
            loadError = failure.message ?: tr("saveA1.datapackPanel.loadFailed")
        } finally {
            loading = false
        }
    }

    /** 选择并安装 ZIP；成功后刷新列表，失败保留提示。 */
    suspend fun installDatapack(
        actions: UiActions,
        instance: GameInstance,
    ) {
        val chosen =
            actions.choose(tr("saveA1.datapackPanel.chooseZip"), extensions = listOf("zip")).firstOrNull()
        if (chosen == null) return
        try {
            withContext(Dispatchers.IO) {
                actions.controller.installDatapackFile(instance.id, world, chosen)
            }
            withContext(Dispatchers.Swing) { refresh++ }
        } catch (failure: IOException) {
            reportActionFailure(failure, tr("saveA1.datapackPanel.installFailed"))
        } catch (failure: IllegalArgumentException) {
            reportActionFailure(failure, tr("saveA1.datapackPanel.installFailed"))
        } catch (failure: IllegalStateException) {
            reportActionFailure(failure, tr("saveA1.datapackPanel.installFailed"))
        }
    }

    /** 切换启用状态；无论成功失败都清理进行中的标记。 */
    suspend fun toggleDatapack(
        actions: UiActions,
        instance: GameInstance,
        entry: DatapackEntry,
        enabled: Boolean,
    ) {
        withContext(Dispatchers.Swing) { working = entry.name }
        try {
            withContext(Dispatchers.IO) {
                actions.controller.setDatapackEnabled(instance.id, world, entry.name, enabled)
            }
            withContext(Dispatchers.Swing) { refresh++ }
        } catch (failure: IOException) {
            reportActionFailure(failure, tr("saveA1.datapackPanel.toggleFailed"))
        } catch (failure: IllegalArgumentException) {
            reportActionFailure(failure, tr("saveA1.datapackPanel.toggleFailed"))
        } catch (failure: IllegalStateException) {
            reportActionFailure(failure, tr("saveA1.datapackPanel.toggleFailed"))
        } finally {
            withContext(Dispatchers.Swing) { working = null }
        }
    }

    /** 动作失败统一回到界面线程提示；调用点按异常类型选择，不再按类型兜底。 */
    private suspend fun reportActionFailure(
        failure: Throwable,
        fallback: String,
    ) {
        withContext(Dispatchers.Swing) {
            actionError = failure.message ?: fallback
        }
    }

    /** 移入回收站；未移动成功且没有其它提示时给出明确说明。 */
    suspend fun deleteDatapack(
        actions: UiActions,
        instance: GameInstance,
        entry: DatapackEntry,
    ) {
        val moved =
            withContext(Dispatchers.IO) {
                try {
                    actions.controller.deleteDatapack(instance.id, world, entry.name)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: IOException) {
                    reportActionFailure(failure, tr("saveA1.datapackPanel.deleteFailed"))
                    false
                } catch (failure: IllegalArgumentException) {
                    reportActionFailure(failure, tr("saveA1.datapackPanel.deleteFailed"))
                    false
                } catch (failure: IllegalStateException) {
                    reportActionFailure(failure, tr("saveA1.datapackPanel.deleteFailed"))
                    false
                }
            }
        withContext(Dispatchers.Swing) {
            if (!moved && actionError == null) {
                actionError = tr("saveA1.datapackPanel.trashFailed", entry.name)
            }
            refresh++
        }
    }
}

@Composable
private fun SaveDatapackBody(
    instance: GameInstance,
    actions: UiActions,
    state: DatapackPanelState,
    modifier: Modifier,
    onDeleteRequest: (DatapackEntry) -> Unit,
) {
    val busy = actions.controller.isInstanceBusy(instance.id)
    val running = actions.controller.game.isRunning(instance.id)
    val canEdit = !busy && !running
    val strings = LocalStrings.current
    val blockedNote =
        when {
            running -> strings.t("saveA1.datapackPanel.blocked.running")
            busy -> strings.t("saveA1.datapackPanel.blocked.busy")
            else -> ""
        }
    val savesDirectory =
        actions.controller.store
            .gamePath(instance)
            .resolve("saves")
    val datapacksDirectory = savesDirectory.resolve(state.world).resolve("datapacks")
    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DatapackToolbar(instance, actions, state, canEdit, busy)
        Text(
            strings.t(
                "saveA1.datapackPanel.summary",
                state.listing?.entries?.size ?: 0,
                datapacksDirectory,
            ),
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
        state.actionError?.let {
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
        if (state.loading && state.listing != null) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
        }
        DatapackListingBody(instance, actions, state, canEdit, onDeleteRequest)
    }
}

@Composable
private fun DatapackToolbar(
    instance: GameInstance,
    actions: UiActions,
    state: DatapackPanelState,
    canEdit: Boolean,
    busy: Boolean,
) {
    val strings = LocalStrings.current
    ContentToolbar(
        flexible = {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    state.world,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        },
        importAction =
            ToolbarAction(
                strings.t("dialogs.content.datapack.title"),
                icon = Icons.Outlined.FileDownload,
                enabled = canEdit,
            ) {
                state.actionError = null
                actions.run { state.installDatapack(actions, instance) }
            },
        refresh =
            ToolbarAction(
                strings.t("pages.saves.toolbar.refresh"),
                icon = Icons.Outlined.Refresh,
                enabled = !busy,
            ) {
                state.actionError = null
                state.refresh++
            },
        openFolder =
            ToolbarAction(
                strings.t("pages.saves.toolbar.openFolder"),
                icon = Icons.Outlined.FolderOpen,
                enabled = !busy,
            ) {
                actions.run { actions.controller.openWorldDatapacks(instance.id, state.world) }
            },
    )
}

@Composable
private fun ColumnScope.DatapackListingBody(
    instance: GameInstance,
    actions: UiActions,
    state: DatapackPanelState,
    canEdit: Boolean,
    onDeleteRequest: (DatapackEntry) -> Unit,
) {
    val strings = LocalStrings.current
    val current = state.listing
    when {
        state.loadError != null ->
            Panel(Modifier.fillMaxWidth()) {
                EmptyState(
                    strings.t("saveA1.datapackPanel.loadFailed"),
                    state.loadError.orEmpty(),
                    Icons.Outlined.CloudOff,
                ) {
                    TextButton({ state.refresh++ }) { Text(strings.t("pages.saves.load.retry")) }
                }
            }
        state.loading && current == null ->
            Panel(Modifier.fillMaxWidth()) {
                EmptyState(
                    strings.t("saveA1.datapackPanel.loadingTitle"),
                    strings.t("saveA1.datapackPanel.loadingBody"),
                    Icons.Outlined.FolderZip,
                )
            }
        current != null && current.entries.isEmpty() ->
            Panel(Modifier.fillMaxWidth()) {
                EmptyState(
                    strings.t("saveA1.datapackPanel.emptyTitle"),
                    strings.t("saveA1.datapackPanel.emptyBody"),
                    Icons.Outlined.FolderZip,
                )
            }
        current != null ->
            DatapackList(
                current = current,
                state = state,
                canEdit = canEdit,
                onToggle = { entry, enabled ->
                    state.actionError = null
                    actions.run { state.toggleDatapack(actions, instance, entry, enabled) }
                },
                onDeleteRequest = onDeleteRequest,
            )
    }
}

@Composable
private fun ColumnScope.DatapackList(
    current: DatapackListing,
    state: DatapackPanelState,
    canEdit: Boolean,
    onToggle: (DatapackEntry, Boolean) -> Unit,
    onDeleteRequest: (DatapackEntry) -> Unit,
) {
    val entries = current.entries
    ScrollColumn(Modifier.weight(1f).fillMaxWidth()) {
        if (current.note.isNotBlank()) {
            Text(current.note, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            entries.forEach { entry ->
                key(entry.name) {
                    DatapackRow(
                        entry = entry,
                        toggleEnabled =
                            current.toggleAvailable &&
                                canEdit &&
                                state.working == null &&
                                entry.enabled != null,
                        onToggle = { enabled -> onToggle(entry, enabled) },
                        deleteEnabled = canEdit && state.working == null,
                        onDelete = { onDeleteRequest(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeleteDatapackDialog(
    entry: DatapackEntry,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val strings = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.t("pages.saves.trash.title")) },
        text = { Text(strings.t("saveA1.datapackPanel.trashMessage", entry.name)) },
        confirmButton = {
            TextButton(onConfirm) {
                Text(strings.t("pages.saves.trash.confirm"), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(strings.t("pages.saves.cancel")) } },
    )
}
