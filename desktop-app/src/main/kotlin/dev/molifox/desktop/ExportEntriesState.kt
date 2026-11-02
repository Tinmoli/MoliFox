// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 只呈现导出内容选择，通过回调交还选择和重试，不访问网络或磁盘。

package dev.molifox.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.PackExportEntry

internal data class ExportEntriesState(
    val loaded: Boolean,
    val scanError: String?,
    val selectableEntries: List<PackExportEntry>,
    val selectedEntries: Set<String>,
    val excludedPaths: Set<String>,
    val exporting: Boolean,
)

@Composable
internal fun ExportEntriesSection(
    state: ExportEntriesState,
    onRetry: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onToggleNode: (String, Boolean) -> Unit,
) {
    val strings = LocalStrings.current
    with(state) {
        when {
            !loaded && scanError != null ->
                Panel(Modifier.fillMaxWidth()) {
                    EmptyState(
                        strings.t("dialogs.export.readDirFailed"),
                        scanError.orEmpty(),
                        Icons.Outlined.ErrorOutline,
                    ) {
                        TextButton({ onRetry() }) { Text(strings.t("dialogs.common.retry")) }
                    }
                }

            !loaded ->
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    MutedText(strings.t("dialogs.export.readingDir"))
                }

            else -> {
                var expanded by remember(selectableEntries) { mutableStateOf(true) }
                SettingsSection(
                    strings.t("dialogs.export.contents"),
                    strings.t("dialogs.export.selectedCount", selectedEntries.size, selectableEntries.size),
                    action = {
                        Text(
                            if (expanded) strings.t("dialogs.export.collapse") else strings.t("dialogs.export.expand"),
                            modifier =
                                Modifier
                                    .clickable { expanded = !expanded }
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                    contentGap = 2.dp,
                    compact = true,
                ) {
                    if (expanded) {
                        selectableEntries.forEach { entry ->
                            ExportEntryRow(
                                entry = entry,
                                checked = entry.key in selectedEntries,
                                enabled = !exporting,
                                excludedPaths = excludedPaths,
                                onToggleNode = onToggleNode,
                            ) { onToggle(entry.key, it) }
                        }
                    }
                }
            }
        }
    }
}
