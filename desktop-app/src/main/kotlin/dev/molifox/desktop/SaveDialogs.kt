// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.desktop

import androidx.compose.runtime.Composable
import dev.molifox.core.GameInstance
import dev.molifox.core.SaveBackupInfo
import dev.molifox.core.SaveEntry
import dev.molifox.core.i18n.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

@Composable
internal fun SaveDialogs(
    instance: GameInstance,
    actions: UiActions,
    deleting: List<SaveEntry>,
    renaming: SaveEntry?,
    deletingBackup: SaveBackupInfo?,
    replacing: SaveBackupInfo?,
    copying: SaveEntry?,
    detailed: SaveEntry?,
    canManage: Boolean,
    onRestore: (SaveBackupInfo) -> Unit,
    onWatchJob: (String) -> Unit,
    onError: (String?) -> Unit,
    onRefresh: () -> Unit,
    onDismissDeleting: () -> Unit,
    onDismissRenaming: () -> Unit,
    onDismissBackup: () -> Unit,
    onDismissReplacing: () -> Unit,
    onDismissCopying: () -> Unit,
    onDismissDetails: () -> Unit,
) {
    val strings = LocalStrings.current
    if (deleting.isNotEmpty()) {
        SaveTrashDialog(
            title = strings.t("pages.saves.trash.title"),
            message = saveTrashMessage(strings, deleting),
            onDismiss = onDismissDeleting,
            onConfirm = {
                onDismissDeleting()
                onError(null)
                trashEntry(
                    actions = actions,
                    message = strings.t("pages.saves.trash.failed", deleting.joinToString { it.name }),
                    onError = onError,
                    onRefresh = onRefresh,
                ) { actions.controller.deleteSaves(instance.id, deleting.map { it.name }) }
            },
        )
    }
    renaming?.let { entry ->
        ContentRenameDialog(
            title = strings.t("pages.saves.rename.title"),
            currentName = entry.name,
            onConfirm = { newName ->
                onDismissRenaming()
                renameSave(actions, instance.id, entry, newName, onRefresh)
            },
            onDismiss = onDismissRenaming,
        )
    }
    deletingBackup?.let { backup ->
        SaveTrashDialog(
            title = strings.t("pages.saves.backup.delete.title"),
            message =
                strings.t(
                    "pages.saves.backup.delete.message",
                    backup.saveName,
                    formatSaveTime(backup.createdAt),
                ),
            onDismiss = onDismissBackup,
            onConfirm = {
                onDismissBackup()
                onError(null)
                trashEntry(
                    actions = actions,
                    message = strings.t("pages.saves.backup.delete.failed"),
                    onError = onError,
                    onRefresh = onRefresh,
                ) { actions.controller.deleteSaveBackup(instance.id, backup.id) }
            },
        )
    }
    replacing?.let { backup ->
        ReplaceSaveDialog(
            backup = backup,
            onDismiss = onDismissReplacing,
            onConfirm = {
                onDismissReplacing()
                onRestore(backup)
            },
        )
    }
    copying?.let { entry ->
        CopySaveDialog(
            instance = instance,
            saveName = entry.name,
            actions = actions,
            onDismiss = onDismissCopying,
            onStarted = onWatchJob,
        )
    }
    detailed?.let { entry ->
        SaveDetailsDialog(
            instance = instance,
            entry = entry,
            actions = actions,
            canEdit = canManage,
            onDismiss = onDismissDetails,
            onSaved = onRefresh,
        )
    }
}

private fun saveTrashMessage(
    strings: Strings,
    deleting: List<SaveEntry>,
): String =
    if (deleting.size == 1) {
        strings.t("pages.saves.trash.message", deleting.single().name)
    } else {
        strings.t("pages.saves.trash.batchMessage", deleting.size)
    }

private fun renameSave(
    actions: UiActions,
    instanceId: String,
    entry: SaveEntry,
    newName: String,
    onRefresh: () -> Unit,
) {
    actions.run {
        actions.controller.renameSave(instanceId, entry.name, newName)
        withContext(Dispatchers.Swing) { onRefresh() }
    }
}
