// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 文件夹浏览页的工具栏、路径提示、多选栏与批量删除确认。

package dev.molifox.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import dev.molifox.core.GameInstance
import dev.molifox.core.data.instance.InstanceDirectoryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.name

/** 已有选区时文件夹与文件一致只切换选择；没有选区时才允许进入文件夹。 */
internal fun Modifier.folderRowClick(
    isDirectory: Boolean,
    selectionActive: Boolean,
    toggleOnClick: Boolean,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
): Modifier =
    when {
        selectionActive && toggleOnClick -> clickable(onClick = onToggleSelect)
        isDirectory -> clickable(onClick = onOpen)
        toggleOnClick -> clickable(onClick = onToggleSelect)
        else -> this
    }

/** 顶部工具栏：返回、搜索、导入、刷新与打开文件夹。 */
@Composable
internal fun FolderToolbar(
    instance: GameInstance,
    actions: UiActions,
    folder: String,
    subPath: List<String>,
    importLabel: String,
    importExtensions: List<String>,
    busy: Boolean,
    query: String,
    onBack: () -> Unit,
    onQuery: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    val strings = LocalStrings.current
    ContentToolbar(
        leading = {
            if (subPath.isNotEmpty()) {
                ToolbarIconAction(Icons.Outlined.ArrowUpward, strings.t("pages.folder.back"), onClick = onBack)
            }
        },
        flexible = {
            ToolbarSearchField(query, onQuery, strings.t("pages.folder.search.placeholder"))
        },
        importAction =
            ToolbarAction(
                importLabel.ifBlank { strings.t("pages.folder.import") },
                icon = Icons.Outlined.FileDownload,
                enabled = !busy,
            ) {
                actions.run {
                    val chosen =
                        actions.choose(
                            importLabel.ifBlank { strings.t("pages.folder.chooseFiles") },
                            multiple = true,
                            extensions = importExtensions,
                        )
                    if (chosen.isNotEmpty()) {
                        actions.controller.content.importSchematics(instance.id, chosen)
                        withContext(Dispatchers.Swing) { onRefresh() }
                    }
                }
            },
        refresh =
            ToolbarAction(
                strings.t("pages.folder.refresh"),
                icon = Icons.Outlined.Refresh,
                enabled = !busy,
            ) { onRefresh() },
        openFolder =
            ToolbarAction(strings.t("pages.folder.open"), icon = Icons.Outlined.FolderOpen) {
                actions.run { actions.controller.openFolder(instance.id, folder) }
            },
    )
}

/** 当前目录路径与操作提示：提示语按是否支持多选切换。 */
@Composable
internal fun FolderLocationRow(
    directory: Path,
    allowDelete: Boolean,
    multiSelect: Boolean,
) {
    val strings = LocalStrings.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            directory.toString(),
            modifier = Modifier.weight(1f),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (allowDelete) {
            Text(
                strings.t(if (multiSelect) "pages.folder.hint.multiSelect" else "pages.folder.hint.contextMenu"),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .75f),
            )
        }
    }
}

/** 在文件管理器里定位条目：取文件所在目录，取不到时退回当前目录。 */
internal fun revealTargetOf(
    entry: InstanceDirectoryEntry,
    fallback: Path,
): Path = entry.path.parent ?: fallback

/** 当前选区里的第一个条目：「打开所在位置」这类操作只需要一个目标目录。 */
internal fun firstSelectedEntry(
    filtered: List<InstanceDirectoryEntry>,
    selection: ManagedSelection,
): InstanceDirectoryEntry? = filtered.firstOrNull { it.path.name in selection.selected }
