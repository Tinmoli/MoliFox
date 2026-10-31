// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 只呈现导出内容与预览统计，通过回调交还选择和重试，不访问网络或磁盘。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.LinearProgressIndicator
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
import dev.molifox.core.PackExportFormat
import dev.molifox.core.PackExportOptions

internal data class ExportEntriesState(
    val loaded: Boolean,
    val scanError: String?,
    val selectableEntries: List<PackExportEntry>,
    val selectedEntries: Set<String>,
    val excludedPaths: Set<String>,
    val exporting: Boolean,
)

internal data class ExportPreviewState(
    val loading: Boolean,
    val error: String?,
    val totalFiles: Int?,
    val selectedFiles: Int,
    val format: PackExportFormat,
    val options: PackExportOptions,
    val localResources: Int,
    val downloads: Int,
)

@Composable
internal fun ExportEntriesSection(
    state: ExportEntriesState,
    onRetry: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onToggleNode: (String, Boolean) -> Unit,
) {
    with(state) {
        when {
            !loaded && scanError != null ->
                Panel(Modifier.fillMaxWidth()) {
                    EmptyState(
                        "无法读取实例目录",
                        scanError.orEmpty(),
                        Icons.Outlined.ErrorOutline,
                    ) {
                        TextButton({ onRetry() }) { Text("重试") }
                    }
                }
            !loaded ->
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    MutedText("正在读取实例目录…")
                }
            else -> {
                var expanded by remember(selectableEntries) { mutableStateOf(true) }
                SettingsSection(
                    "打包内容",
                    "已选 ${selectedEntries.size}/${selectableEntries.size}",
                    action = {
                        TextButton({ expanded = !expanded }) {
                            Text(if (expanded) "收起" else "展开")
                        }
                    },
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

@Composable
internal fun ExportPreviewSection(
    state: ExportPreviewState,
    onRetry: () -> Unit,
) {
    with(state) {
        when {
            loading -> ExportLoadingSection()
            totalFiles == null ->
                Panel(Modifier.fillMaxWidth()) {
                    EmptyState(
                        "无法预览这次导出",
                        error.orEmpty(),
                        Icons.Outlined.ErrorOutline,
                    ) {
                        TextButton({ onRetry() }) { Text("重试") }
                    }
                }
            totalFiles == 0 ->
                Panel(Modifier.fillMaxWidth()) {
                    EmptyState(
                        "实例目录没有可导出的文件",
                        "",
                        Icons.Outlined.FolderOpen,
                    )
                }
            else -> {
                ExportPreviewStatistics(state)
            }
        }
    }
}

@Composable
private fun ExportLoadingSection() {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        MutedText("正在读取实例文件…")
    }
}

@Composable
private fun ExportPreviewStatistics(state: ExportPreviewState) {
    with(state) {
        SettingsSection("预览", "") {
            SettingRow("包含文件", "") {
                Text("$selectedFiles / $totalFiles", fontSize = 13.sp)
            }
            if (format == PackExportFormat.Modrinth && options.remoteResources) {
                SettingRow("联网下载", "") {
                    Text("$downloads", fontSize = 13.sp)
                }
            }
            SettingRow("本地打包", "") {
                Text("$localResources", fontSize = 13.sp)
            }
        }
    }
}
