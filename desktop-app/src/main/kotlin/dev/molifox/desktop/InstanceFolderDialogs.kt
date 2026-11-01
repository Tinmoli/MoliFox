// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 原理图条目的重命名与删除确认对话框。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.GameInstance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.io.path.name
import dev.molifox.core.data.instance.InstanceDirectoryEntry as FolderEntry

/** 原理图目录的重命名/删除入口：对话框只负责输入，真正写盘仍走控制器。 */
@Composable
internal fun SchematicEntryDialogs(
    instance: GameInstance,
    actions: UiActions,
    subPath: List<String>,
    deletingEntry: FolderEntry?,
    renamingEntry: FolderEntry?,
    onDismissDelete: () -> Unit,
    onDismissRename: () -> Unit,
    onChanged: () -> Unit,
) {
    val strings = LocalStrings.current
    deletingEntry?.let { entry ->
        SchematicDeleteDialog(
            entryName = entry.path.name,
            directory = entry.isDirectory,
            onDismiss = onDismissDelete,
            onConfirm = {
                onDismissDelete()
                actions.run {
                    actions.controller.requireIdle(instance.id)
                    actions.controller.content.deleteSchematicEntry(
                        instance.id,
                        schematicRelativePath(entry, subPath),
                    )
                    withContext(Dispatchers.Swing) { onChanged() }
                }
            },
        )
    }
    renamingEntry?.let { entry ->
        var error by remember(entry.path) { mutableStateOf<String?>(null) }
        var running by remember(entry.path) { mutableStateOf(false) }
        SchematicRenameDialog(
            currentName = entry.path.name,
            error = error,
            busy = running,
            onDismiss = { if (!running) onDismissRename() },
            onConfirm = { newName ->
                if (!running) {
                    running = true
                    error = null
                    actions.run {
                        try {
                            actions.controller.requireIdle(instance.id)
                            actions.controller.content.renameSchematicEntry(
                                instance.id,
                                schematicRelativePath(entry, subPath),
                                newName,
                            )
                            withContext(Dispatchers.Swing) {
                                onDismissRename()
                                onChanged()
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: IOException) {
                            withContext(Dispatchers.Swing) {
                                error = failure.message ?: strings.t("dialogs.folder.renameFailed")
                            }
                        } catch (failure: IllegalArgumentException) {
                            withContext(Dispatchers.Swing) {
                                error = failure.message ?: strings.t("dialogs.folder.renameFailed")
                            }
                        } finally {
                            withContext(Dispatchers.Swing) { running = false }
                        }
                    }
                }
            },
        )
    }
}

private fun schematicRelativePath(
    entry: FolderEntry,
    subPath: List<String>,
): String = (subPath + entry.path.name).joinToString("/")

/** 重命名当前目录里的文件或文件夹；重名与非法名称在确认前就给出提示。 */
@Composable
internal fun SchematicRenameDialog(
    currentName: String,
    error: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val strings = LocalStrings.current
    var name by remember(currentName) { mutableStateOf(currentName) }
    val trimmed = name.trim()
    val problem =
        when {
            trimmed.isEmpty() -> strings.t("dialogs.folder.nameEmpty")
            '/' in trimmed || '\\' in trimmed -> strings.t("dialogs.folder.nameHasSeparator")
            trimmed == currentName -> strings.t("dialogs.folder.nameUnchanged")
            else -> error
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.t("dialogs.folder.rename")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactTextField(
                    name,
                    { name = it.take(120) },
                    label = strings.t("dialogs.folder.newName"),
                    enabled = !busy,
                )
                problem?.let {
                    Text(
                        it,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmed) },
                enabled = problem == null && !busy,
            ) {
                Text(strings.t("dialogs.common.save"))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(strings.t("dialogs.common.cancel")) } },
    )
}

/** 删除原理图目录里的文件或整个文件夹；两者都移入回收站。 */
@Composable
internal fun SchematicDeleteDialog(
    entryName: String,
    directory: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val strings = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.t("dialogs.folder.trashTitle")) },
        text = {
            Text(
                if (directory) {
                    strings.t("dialogs.folder.trashFolderMessage", entryName)
                } else {
                    strings.t("dialogs.folder.trashFileMessage", entryName)
                },
            )
        },
        confirmButton = {
            TextButton(onConfirm) {
                Text(strings.t("dialogs.folder.trashConfirm"), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(strings.t("dialogs.common.cancel")) } },
    )
}
