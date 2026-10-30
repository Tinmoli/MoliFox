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
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
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
    val onBackup: (SaveEntry) -> Unit,
    val onCopy: (SaveEntry) -> Unit,
    val onDelete: (SaveEntry) -> Unit,
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
    ContentToolbar(
        flexible = { ToolbarSearchField(ui.query, callbacks.onChangeQuery, "搜索存档名称") },
        refresh =
            ToolbarAction("刷新", icon = Icons.Outlined.Refresh, enabled = !ui.busy) {
                callbacks.onClearError()
                callbacks.onRefresh()
            },
        openFolder =
            ToolbarAction("打开文件夹", icon = Icons.Outlined.FolderOpen, enabled = !ui.busy) {
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
        "${ui.saves.size} 个存档 · ${ui.backups.size} 个备份 · ${ui.savesDirectory}",
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
    when {
        ui.loading && ui.saves.isEmpty() && ui.backups.isEmpty() -> ListSkeleton()
        ui.loadError != null ->
            Panel(Modifier.fillMaxWidth()) {
                EmptyState("无法读取存档", ui.loadError.orEmpty(), Icons.Outlined.CloudOff) {
                    TextButton({ callbacks.onRefresh() }) { Text("重试") }
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
    val filtered = ui.saves.filter { ui.query.isBlank() || it.name.contains(ui.query, true) }
    when {
        ui.saves.isEmpty() ->
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
                    TextButton({ callbacks.onChangeQuery("") }) { Text("清除搜索") }
                }
            }
        else ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                filtered.forEach { entry ->
                    key(entry.name) {
                        SaveRow(
                            entry = entry,
                            canManage = ui.canManage,
                            canCopy = ui.canCopy,
                            onBackup = { callbacks.onBackup(entry) },
                            onCopy = { callbacks.onCopy(entry) },
                            onDelete = { callbacks.onDelete(entry) },
                        )
                    }
                }
            }
    }
    RestoreReplaceSetting(ui, callbacks)
    SaveBackupsPanel(ui, callbacks)
}

/** 「恢复时替换原世界」开关；切换立即写入实例配置。 */
@Composable
private fun RestoreReplaceSetting(
    ui: SavesUiState,
    callbacks: SavesCallbacks,
) {
    Panel(Modifier.fillMaxWidth()) {
        SettingRow(
            "恢复时替换原世界",
            "关闭时恢复只解压成同级的新世界，不改动原存档；开启后先备份当前世界，再用备份替换它，" +
                "当前世界改名为「（替换前 时间）」保留。",
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
    Panel(
        Modifier.fillMaxWidth(),
        title = "备份",
        detail =
            "与原版游戏共用 backups/ 目录（原版自己写的备份也会列在这里）；" +
                "恢复默认解压成新世界，不直接覆盖。",
    ) {
        if (ui.backups.isEmpty()) {
            Text(
                "还没有备份。存档行末尾的「备份」会把整个世界打包到这里。",
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
