// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.desktop

import androidx.compose.runtime.Composable
import dev.molifox.core.GameInstance
import dev.molifox.core.LocalPackEntry
import dev.molifox.core.PackFolder
import dev.molifox.core.PackUpdateItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.nio.file.Path

internal suspend fun loadPackEntries(
    actions: UiActions,
    instanceId: String,
    folder: PackFolder,
): Result<List<LocalPackEntry>> =
    withContext(Dispatchers.IO) {
        try {
            Result.success(actions.controller.listPacks(instanceId, folder))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IllegalStateException) {
            Result.failure(failure)
        } catch (failure: IllegalArgumentException) {
            Result.failure(failure)
        }
    }

internal fun startPackImport(
    actions: UiActions,
    instanceId: String,
    folder: PackFolder,
    chosen: List<Path>,
    onStart: (String) -> Unit,
    onError: (String?) -> Unit,
) {
    onError(null)
    if (chosen.isEmpty()) return
    runCatching { actions.controller.importPacks(instanceId, folder, chosen) }
        .onSuccess(onStart)
        .onFailure { failure -> onError(failure.message ?: tr("deskC.import.failed")) }
}

@Composable
internal fun PackDialogs(
    deleting: List<LocalPackEntry>,
    renaming: LocalPackEntry?,
    detailed: LocalPackEntry?,
    folder: PackFolder,
    instance: GameInstance,
    actions: UiActions,
    updates: InstanceUpdateCenter,
    itemsByFile: Map<String, PackUpdateItem>,
    onDismissDelete: () -> Unit,
    onDismissRename: () -> Unit,
    onDismissDetail: () -> Unit,
    onError: (String?) -> Unit,
    onRefresh: () -> Unit,
) {
    val strings = LocalStrings.current
    if (deleting.isNotEmpty()) {
        PackDeleteDialog(
            entries = deleting,
            folder = folder,
            instanceId = instance.id,
            actions = actions,
            updates = updates,
            onError = onError,
            onDismiss = onDismissDelete,
            onRefresh = onRefresh,
        )
    }
    renaming?.let { entry ->
        ContentRenameDialog(
            title = strings.t("content.rename.packTitle"),
            currentName = entry.filename,
            onConfirm = { newName ->
                onDismissRename()
                renamePack(actions, instance.id, folder, entry, newName, onRefresh)
            },
            onDismiss = onDismissRename,
        )
    }
    detailed?.let { entry ->
        PackDetailsDialog(entry, folder, itemsByFile[entry.filename], onDismiss = onDismissDetail)
    }
}

private fun renamePack(
    actions: UiActions,
    instanceId: String,
    folder: PackFolder,
    entry: LocalPackEntry,
    newName: String,
    onRefresh: () -> Unit,
) {
    actions.run {
        actions.controller.requireIdle(instanceId)
        actions.controller.content.renamePack(instanceId, folder, entry.filename, newName)
        withContext(Dispatchers.Swing) { onRefresh() }
    }
}

internal fun updateSelectedPacks(
    actions: UiActions,
    instanceId: String,
    actionable: List<PackUpdateItem>,
    targets: List<LocalPackEntry>,
): String? {
    val names = targets.mapTo(HashSet()) { it.filename }
    val items = actionable.filter { it.filename in names }
    return items.takeIf { it.isNotEmpty() }?.let { actions.controller.updatePacks(instanceId, it) }
}
