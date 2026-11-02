// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 存档页签的分区组件：工具行、状态提示、世界列表与备份卡片。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.SaveBackupInfo
import dev.molifox.core.SaveEntry
import java.nio.file.Path

/** 存档页签一帧的只读状态；每次重组构造一次，供各分区共享。 */
internal class SavesUiState(
    val saves: List<SaveEntry>,
    val backups: List<SaveBackupInfo>,
    val query: String,
    val savesDirectory: Path,
    val blockedNote: String,
    val actionError: String?,
    val loading: Boolean,
    val loadError: String?,
    val canManage: Boolean,
    val canCopy: Boolean,
    val busy: Boolean,
    val replaceOnRestore: Boolean,
)

/** 存档页签的操作回调；全部由页面持有，分区组件只负责触发。 */
internal class SavesCallbacks(
    val onChangeQuery: (String) -> Unit,
    val onClearError: () -> Unit,
    val onRefresh: () -> Unit,
    val onOpenFolder: () -> Unit,
    val onDetails: (SaveEntry) -> Unit,
    val onBackup: (SaveEntry) -> Unit,
    val onCopy: (SaveEntry) -> Unit,
    val onRename: (SaveEntry) -> Unit,
    val onDelete: (List<SaveEntry>) -> Unit,
    val onDeleteBackup: (SaveBackupInfo) -> Unit,
    val onRestore: (SaveBackupInfo) -> Unit,
    val onReplaceRestore: (SaveBackupInfo) -> Unit,
    val onToggleReplace: (Boolean) -> Unit,
)

/** 工具行与内容区；作为 Column 的直接子项排布，保持原有 10dp 间距。 */
@Composable
internal fun ColumnScope.SavesTabSections(
    ui: SavesUiState,
    callbacks: SavesCallbacks,
) {
    val strings = LocalStrings.current
    ContentToolbar(
        flexible = {
            ToolbarSearchField(
                ui.query,
                callbacks.onChangeQuery,
                strings.t("pages.saves.search.placeholder"),
            )
        },
        refresh =
            ToolbarAction(
                strings.t("pages.saves.toolbar.refresh"),
                icon = Icons.Outlined.Refresh,
                enabled = !ui.busy,
            ) {
                callbacks.onClearError()
                callbacks.onRefresh()
            },
        openFolder =
            ToolbarAction(
                strings.t("pages.saves.toolbar.openFolder"),
                icon = Icons.Outlined.FolderOpen,
                enabled = !ui.busy,
            ) {
                callbacks.onOpenFolder()
            },
    )
    SavesStatusLines(ui)
    SavesListArea(ui, callbacks)
}

/** 汇总行、占用提示、操作错误与刷新进度条。 */
@Composable
private fun SavesStatusLines(ui: SavesUiState) {
    Text(
        LocalStrings.current.t(
            "pages.saves.summary",
            ui.saves.size,
            ui.backups.size,
            ui.savesDirectory,
        ),
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    if (ui.blockedNote.isNotBlank()) {
        Text(
            ui.blockedNote,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    ui.actionError?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
    // 刷新时保留旧列表，只在顶部显示一条细进度；首次读取才给骨架。
    if (ui.loading && ui.saves.isNotEmpty()) {
        LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
    }
}

/** 首次加载骨架、读取失败与正常内容三态。 */
@Composable
private fun ColumnScope.SavesListArea(
    ui: SavesUiState,
    callbacks: SavesCallbacks,
) {
    val strings = LocalStrings.current
    when {
        ui.loading && ui.saves.isEmpty() && ui.backups.isEmpty() -> ListSkeleton()
        ui.loadError != null ->
            Panel(Modifier.fillMaxWidth()) {
                EmptyState(
                    strings.t("pages.saves.load.title"),
                    ui.loadError.orEmpty(),
                    Icons.Outlined.CloudOff,
                ) {
                    TextButton({ callbacks.onRefresh() }) {
                        Text(strings.t("pages.saves.load.retry"))
                    }
                }
            }
        else ->
            ScrollColumn(Modifier.weight(1f).fillMaxWidth()) {
                SavesContentSection(ui, callbacks)
            }
    }
}

/** 世界列表、「恢复时替换原世界」设置与备份卡片。 */
@Composable
private fun SavesContentSection(
    ui: SavesUiState,
    callbacks: SavesCallbacks,
) {
    val strings = LocalStrings.current
    val filtered = ui.saves.filter { ui.query.isBlank() || it.name.contains(ui.query, true) }
    when {
        ui.saves.isEmpty() ->
            Panel(Modifier.fillMaxWidth()) {
                EmptyState(
                    strings.t("pages.saves.empty.title"),
                    strings.t("pages.saves.empty.body"),
                    Icons.Outlined.Save,
                )
            }

        filtered.isEmpty() ->
            Panel(Modifier.fillMaxWidth()) {
                EmptyState(
                    strings.t("pages.saves.emptyFiltered.title"),
                    strings.t("pages.saves.emptyFiltered.body"),
                    Icons.Outlined.SearchOff,
                ) {
                    TextButton({ callbacks.onChangeQuery("") }) {
                        Text(strings.t("pages.saves.clearSearch"))
                    }
                }
            }

        else ->
            run {
                val selection = remember { ManagedSelection() }
                val keys = filtered.map { it.name }
                LaunchedEffect(keys) { selection.retain(keys) }
                SelectionBar(selection, keys)
                Column(
                    Modifier.dragSelectRows(selection, keys),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    filtered.forEach { entry ->
                        key(entry.name) {
                            ManagedItemMenu(
                                key = entry.name,
                                selection = selection,
                                actions = {
                                    val selected =
                                        filtered.filter { it.name in selection.selected }.ifEmpty {
                                            listOf(
                                                entry,
                                            )
                                        }
                                    saveMenuActions(ui, callbacks, selected)
                                },
                            ) {
                                SaveRow(
                                    entry = entry,
                                    canManage = ui.canManage,
                                    canCopy = ui.canCopy,
                                    onDetails = { callbacks.onDetails(entry) },
                                    onBackup = { callbacks.onBackup(entry) },
                                    onCopy = { callbacks.onCopy(entry) },
                                    onDelete = { callbacks.onDelete(listOf(entry)) },
                                )
                            }
                        }
                    }
                }
            }
    }
    RestoreReplaceSetting(ui, callbacks)
    SaveBackupsPanel(ui, callbacks)
}

private fun saveMenuActions(
    ui: SavesUiState,
    callbacks: SavesCallbacks,
    selected: List<SaveEntry>,
): List<ManagedAction> {
    val single = selected.singleOrNull()
    val canView = single?.let { it.hasLevelDat && !it.name.endsWith(".zip", true) } == true
    return listOf(
        ManagedAction(tr("content.menu.details"), Icons.Outlined.Info, canView) {
            single?.let(callbacks.onDetails)
        },
        ManagedAction(tr("saveC.row.backup"), Icons.Outlined.Archive, ui.canManage && single != null) {
            single?.let(callbacks.onBackup)
        },
        ManagedAction(tr("saveC.row.copyTo"), Icons.Outlined.ContentCopy, ui.canCopy && single != null) {
            single?.let(callbacks.onCopy)
        },
        ManagedAction(
            tr("content.menu.rename"),
            Icons.Outlined.DriveFileRenameOutline,
            ui.canManage && single != null,
        ) {
            single?.let(callbacks.onRename)
        },
        ManagedAction(tr("content.menu.openFolder"), Icons.Outlined.FolderOpen) { callbacks.onOpenFolder() },
        ManagedAction(
            tr("content.menu.delete"),
            Icons.Outlined.DeleteOutline,
            ui.canManage,
        ) { callbacks.onDelete(selected) },
    )
}

/** 「恢复时替换原世界」开关；切换立即写入实例配置。 */
@Composable
private fun RestoreReplaceSetting(
    ui: SavesUiState,
    callbacks: SavesCallbacks,
) {
    val strings = LocalStrings.current
    Panel(Modifier.fillMaxWidth()) {
        SettingRow(
            strings.t("pages.saves.replaceSetting.title"),
            strings.t("pages.saves.replaceSetting.detail"),
        ) {
            CompactSwitch(
                ui.replaceOnRestore,
                callbacks.onToggleReplace,
                enabled = !ui.busy,
            )
        }
    }
}

/** 备份卡片：与原版游戏共用 backups/ 目录；同名世界存在且开关开启时才走替换确认。 */
@Composable
private fun SaveBackupsPanel(
    ui: SavesUiState,
    callbacks: SavesCallbacks,
) {
    val strings = LocalStrings.current
    Panel(
        Modifier.fillMaxWidth(),
        title = strings.t("pages.saves.backups.title"),
        detail = strings.t("pages.saves.backups.detail"),
    ) {
        if (ui.backups.isEmpty()) {
            Text(
                strings.t("pages.saves.backups.empty"),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ui.backups.forEach { backup ->
                    key(backup.id) {
                        SaveBackupRow(
                            backup = backup,
                            busy = ui.busy,
                            onRestore = { onRestoreBackup(ui, callbacks, backup) },
                            onDelete = { callbacks.onDeleteBackup(backup) },
                        )
                    }
                }
            }
        }
    }
}

/** 恢复入口：仅当开启「恢复时替换原世界」且同名世界存在时才要求替换确认。 */
private fun onRestoreBackup(
    ui: SavesUiState,
    callbacks: SavesCallbacks,
    backup: SaveBackupInfo,
) {
    val exists =
        ui.saves.any {
            it.name.equals(backup.saveName, true) ||
                saveBaseName(it.name).equals(backup.saveName, true)
        }
    if (ui.replaceOnRestore && exists) {
        callbacks.onReplaceRestore(backup)
    } else {
        callbacks.onRestore(backup)
    }
}
