// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责导出对话框的内容与底部按钮：只做渲染，读写由调用方提供。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.GameInstance
import dev.molifox.core.MrpackExportMetadata
import dev.molifox.core.MrpackExportPlan
import dev.molifox.core.PackExportEntry
import dev.molifox.core.PackExportFormat
import dev.molifox.core.PackExportOptions
import dev.molifox.core.PackExportScan

@Composable
internal fun ExportMrpackDialogBody(
    instance: GameInstance,
    actions: UiActions,
    format: PackExportFormat,
    stem: String,
    target: String,
    version: String,
    loading: Boolean,
    exporting: Boolean,
    error: String?,
    scan: PackExportScan?,
    scanError: String?,
    selectableEntries: List<PackExportEntry>,
    selectedEntries: Set<String>,
    prepared: MrpackExportPlan?,
    view: ExportMrpackView,
    localPaths: Set<String>,
    selection: Set<String>,
    includeLogs: Boolean,
    options: PackExportOptions,
    canConfirm: Boolean,
    onTargetChange: (String) -> Unit,
    onVersionChange: (String) -> Unit,
    onFormatChosen: (String) -> Unit,
    onRetryScan: () -> Unit,
    onRetryPreview: () -> Unit,
    onToggleEntry: (String, Boolean) -> Unit,
    onOptionsChange: (PackExportOptions) -> Unit,
    onExportStart: () -> Unit,
    onExportFailure: (String?) -> Unit,
    onStarted: () -> Unit,
    onDismiss: () -> Unit,
) {
    Modal(
        "导出整合包 / 完整备份",
        "${instance.name} · ${format.title}",
        width = 640.dp,
        onDismiss = onDismiss,
    ) {
        ExportMrpackInputs(
            format = format,
            stem = stem,
            target = target,
            version = version,
            exporting = exporting,
            fileName = view.fileName,
            actions = actions,
            onTargetChange = onTargetChange,
            onVersionChange = onVersionChange,
            onFormatChosen = onFormatChosen,
        )
        // 中间区域单独滚动，底部按钮始终可见；顶层条目很多时也只在这里滚动。
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ExportEntriesSection(
                ExportEntriesState(
                    loaded = scan != null,
                    scanError = scanError,
                    selectableEntries = selectableEntries,
                    selectedEntries = selectedEntries,
                    exporting = exporting,
                    omitted = scan?.omitted.orEmpty(),
                ),
                onRetry = onRetryScan,
                onToggle = onToggleEntry,
            )
            ExportPreviewSection(
                ExportPreviewState(
                    loading = loading,
                    error = error,
                    totalFiles = prepared?.files?.size,
                    selectedFiles = selection.size,
                    format = format,
                    options = options,
                    localResources = localPaths.size,
                    downloads = view.downloads,
                    managedFiles = view.managedFiles,
                    disabledMods = view.disabledMods,
                    exporting = exporting,
                    warnings = prepared?.warnings.orEmpty(),
                ),
                onRetry = onRetryPreview,
                onOptionsChange = onOptionsChange,
            )
        }
        if (prepared != null) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        }
        ExportMrpackActions(
            actions = actions,
            instance = instance,
            prepared = prepared,
            view = view,
            format = format,
            options = options,
            selectedEntries = selectedEntries,
            includeLogs = includeLogs,
            selectionSize = selection.size,
            loading = loading,
            exporting = exporting,
            busy = actions.controller.busy,
            canConfirm = canConfirm,
            onExportStart = onExportStart,
            onExportFailure = onExportFailure,
            onStarted = onStarted,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun ExportMrpackInputs(
    format: PackExportFormat,
    stem: String,
    target: String,
    version: String,
    exporting: Boolean,
    fileName: String,
    actions: UiActions,
    onTargetChange: (String) -> Unit,
    onVersionChange: (String) -> Unit,
    onFormatChosen: (String) -> Unit,
) {
    SimpleDropdown(
        format.name,
        PackExportFormat.offeredForExport.map { it.name to it.title },
        onFormatChosen,
        Modifier.fillMaxWidth(),
        enabled = !exporting,
    )
    Text(
        format.purpose,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        CompactTextField(
            target,
            onTargetChange,
            Modifier.weight(1f),
            label = "目标文件",
            placeholder = "$stem.${format.extension}",
            enabled = !exporting,
        )
        SecondaryButton(
            {
                actions.run {
                    actions.choose("选择整合包导出目录", directory = true).firstOrNull()?.let {
                        val name = fileName.ifBlank { "$stem.${format.extension}" }
                        onTargetChange(it.resolve(name).toString())
                    }
                }
            },
            enabled = !exporting,
        ) {
            Text("选择目录…", fontSize = 12.sp)
        }
    }
    CompactTextField(
        version,
        { onVersionChange(it.take(100)) },
        Modifier.fillMaxWidth(),
        label = "整合包版本",
        placeholder = "1.0.0",
        enabled = !exporting,
    )
    Text(
        "导出文件需放在游戏目录与启动器数据目录之外；导出在后台任务中写入，进度在日志页。",
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ExportMrpackActions(
    actions: UiActions,
    instance: GameInstance,
    prepared: MrpackExportPlan?,
    view: ExportMrpackView,
    format: PackExportFormat,
    options: PackExportOptions,
    selectedEntries: Set<String>,
    includeLogs: Boolean,
    selectionSize: Int,
    loading: Boolean,
    exporting: Boolean,
    busy: Boolean,
    canConfirm: Boolean,
    onExportStart: () -> Unit,
    onExportFailure: (String?) -> Unit,
    onStarted: () -> Unit,
    onDismiss: () -> Unit,
) {
    DialogActions(
        confirmText = exportConfirmText(exporting, format),
        enabled = canConfirm,
        onCancel = onDismiss,
        onConfirm = {
            val current = prepared
            val destination = view.destination
            if (current != null && destination != null) {
                onExportStart()
                startMrpackExport(
                    actions = actions,
                    instance = instance,
                    current = current,
                    destination = destination,
                    metadata = MrpackExportMetadata(view.packName, view.versionText),
                    format = format,
                    options = options,
                    selectedEntries = selectedEntries,
                    includeLogs = includeLogs,
                    onStarted = onStarted,
                    onDismiss = onDismiss,
                    onFailure = { failure ->
                        onExportFailure(failure.message ?: "无法开始导出，请稍后重试")
                    },
                )
            }
        },
        hint =
            exportHint(
                loading,
                prepared,
                view.validTarget,
                view.validVersion,
                busy,
                selectionSize,
                selectedEntries.isNotEmpty(),
                format,
            ),
    )
}
