// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 模组列表本体：多选动作栏、行拖选与行内菜单。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.molifox.core.ModFile
import dev.molifox.core.ModUpdateItem
import java.nio.file.Path

/** 已加载的模组列表：多选栏与行共用一个选区，批量动作按选中项状态启用。 */
@Composable
internal fun ModsLoadedList(
    filtered: List<ModFile>,
    busy: Boolean,
    applying: Boolean,
    reportByFile: Map<String, ModUpdateItem>,
    modsDirectory: Path?,
    readIcon: suspend (Path) -> ByteArray?,
    onIconFailure: (Throwable) -> Unit,
    onDetail: (ModFile) -> Unit,
    onUpdate: (ModFile, ModUpdateItem) -> Unit,
    onToggle: (ModFile) -> Unit,
    onRename: (ModFile) -> Unit,
    onOpenFolder: () -> Unit,
    onUpdateSelection: (List<ModFile>) -> Unit,
    onEnableSelection: (List<ModFile>, Boolean) -> Unit,
    onDelete: (List<ModFile>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val selection = remember { ManagedSelection() }
    val listState = rememberLazyListState()
    val keys = filtered.map { it.filename }
    LaunchedEffect(keys) { selection.retain(keys) }
    val selectedMods = filtered.filter { it.filename in selection.selected }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SelectionActionBar(
            selection,
            keys,
            listOf(
                ManagedAction(
                    strings.t("content.menu.update"),
                    Icons.Outlined.SystemUpdateAlt,
                    !(busy || applying) &&
                        selectedMods.any { reportByFile[it.filename]?.batchApplicable == true },
                ) { onUpdateSelection(selectedMods) },
                ManagedAction(
                    strings.t("content.menu.enable"),
                    Icons.Outlined.PowerSettingsNew,
                    !(busy || applying) && selectedMods.any { !it.enabled },
                ) { onEnableSelection(selectedMods, true) },
                ManagedAction(
                    strings.t("content.menu.disable"),
                    Icons.Outlined.PowerSettingsNew,
                    !(busy || applying) && selectedMods.any { it.enabled },
                ) { onEnableSelection(selectedMods, false) },
                ManagedAction(
                    strings.t("content.menu.delete"),
                    Icons.Outlined.DeleteOutline,
                    !(busy || applying),
                ) { onDelete(selectedMods) },
            ),
        )
        LazyColumn(
            Modifier.weight(1f).dragSelectRows(selection, keys),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(filtered, key = { it.filename }) { mod ->
                ManagedItemMenu(
                    key = mod.filename,
                    selection = selection,
                    actions = {
                        val selected = filtered.filter { it.filename in selection.selected }
                        modMenuActions(
                            mod,
                            selected,
                            busy || applying,
                            reportByFile,
                            onDetail,
                            onUpdateSelection,
                            onRename,
                            onEnableSelection,
                            onOpenFolder,
                            onDelete,
                        )
                    },
                ) {
                    ModRow(
                        mod,
                        jar = modsDirectory?.resolve(mod.filename),
                        readIcon = readIcon,
                        onIconFailure = onIconFailure,
                        modifier = Modifier.animateItem(),
                        update = reportByFile[mod.filename],
                        busy = busy || applying,
                        // 已有选区时单击行改为切换选中，避免误开详情页。
                        onDetail = { if (selection.active) selection.toggle(mod.filename) else onDetail(mod) },
                        onUpdate = {
                            reportByFile[mod.filename]?.let { item -> onUpdate(mod, item) }
                        },
                        onToggle = { onToggle(mod) },
                        onDelete = { onDelete(listOf(mod)) },
                    )
                }
            }
        }
    }
}

private fun modMenuActions(
    mod: ModFile,
    selected: List<ModFile>,
    busy: Boolean,
    report: Map<String, ModUpdateItem>,
    onDetail: (ModFile) -> Unit,
    onUpdate: (List<ModFile>) -> Unit,
    onRename: (ModFile) -> Unit,
    onSetEnabled: (List<ModFile>, Boolean) -> Unit,
    onOpenFolder: () -> Unit,
    onDelete: (List<ModFile>) -> Unit,
): List<ManagedAction> {
    val targets = selected.ifEmpty { listOf(mod) }
    val canUpdate = targets.any { report[it.filename]?.batchApplicable == true }
    val enable = targets.any { !it.enabled }
    return listOf(
        ManagedAction(
            tr("content.menu.details"),
            Icons.Outlined.Info,
            targets.size == 1,
        ) { onDetail(targets.single()) },
        ManagedAction(
            tr("content.menu.update"),
            Icons.Outlined.SystemUpdateAlt,
            !busy && canUpdate,
        ) { onUpdate(targets) },
        ManagedAction(tr("content.menu.rename"), Icons.Outlined.DriveFileRenameOutline, !busy && targets.size == 1) {
            onRename(targets.single())
        },
        ManagedAction(
            tr(if (enable) "content.menu.enable" else "content.menu.disable"),
            Icons.Outlined.PowerSettingsNew,
            !busy,
        ) { onSetEnabled(targets, enable) },
        ManagedAction(tr("content.menu.openFolder"), Icons.Outlined.FolderOpen) { onOpenFolder() },
        ManagedAction(tr("content.menu.delete"), Icons.Outlined.DeleteOutline, !busy) { onDelete(targets) },
    )
}
