// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责导出页面的内容与底部按钮：只做渲染，读写由调用方提供。

package dev.molifox.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
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
internal fun ExportMrpackPageBody(
    instance: GameInstance,
    actions: UiActions,
    format: PackExportFormat,
    stem: String,
    target: String,
    packName: String,
    version: String,
    loading: Boolean,
    exporting: Boolean,
    error: String?,
    scan: PackExportScan?,
    scanError: String?,
    selectableEntries: List<PackExportEntry>,
    selectedEntries: Set<String>,
    excludedPaths: Set<String>,
    prepared: MrpackExportPlan?,
    view: ExportMrpackView,
    localPaths: Set<String>,
    selection: Set<String>,
    includeLogs: Boolean,
    options: PackExportOptions,
    canConfirm: Boolean,
    onTargetChange: (String) -> Unit,
    onPackNameChange: (String) -> Unit,
    onVersionChange: (String) -> Unit,
    onFormatChosen: (String) -> Unit,
    onRetryScan: () -> Unit,
    onRetryPreview: () -> Unit,
    onToggleEntry: (String, Boolean) -> Unit,
    onToggleNode: (String, Boolean) -> Unit,
    onOptionsChange: (PackExportOptions) -> Unit,
    onExportStart: () -> Unit,
    onExportFailure: (String?) -> Unit,
    onStarted: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HeaderIconButton(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        strings.t("pages.export.back"),
                        onClick = onDismiss,
                    )
                    Text(
                        strings.t("pages.export.title"),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                ExportMrpackInputs(
                    format = format,
                    stem = stem,
                    target = target,
                    packName = packName,
                    version = version,
                    exporting = exporting,
                    fileName = view.fileName,
                    options = options,
                    actions = actions,
                    onTargetChange = onTargetChange,
                    onPackNameChange = onPackNameChange,
                    onVersionChange = onVersionChange,
                    onFormatChosen = onFormatChosen,
                    onOptionsChange = onOptionsChange,
                )
                ExportEntriesSection(
                    ExportEntriesState(
                        loaded = scan != null,
                        scanError = scanError,
                        selectableEntries = selectableEntries,
                        selectedEntries = selectedEntries,
                        excludedPaths = excludedPaths,
                        exporting = exporting,
                    ),
                    onRetry = onRetryScan,
                    onToggle = onToggleEntry,
                    onToggleNode = onToggleNode,
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
                    ),
                    onRetry = onRetryPreview,
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            ExportMrpackActions(
                actions = actions,
                instance = instance,
                prepared = prepared,
                view = view,
                format = format,
                options = options,
                selectedEntries = selectedEntries,
                excludedPaths = excludedPaths,
                includeLogs = includeLogs,
                loading = loading,
                exporting = exporting,
                canConfirm = canConfirm,
                onExportStart = onExportStart,
                onExportFailure = onExportFailure,
                onStarted = onStarted,
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun ExportMrpackInputs(
    format: PackExportFormat,
    stem: String,
    target: String,
    packName: String,
    version: String,
    exporting: Boolean,
    fileName: String,
    options: PackExportOptions,
    actions: UiActions,
    onTargetChange: (String) -> Unit,
    onPackNameChange: (String) -> Unit,
    onVersionChange: (String) -> Unit,
    onFormatChosen: (String) -> Unit,
    onOptionsChange: (PackExportOptions) -> Unit,
) {
    val strings = LocalStrings.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        CompactTextField(
            packName,
            { onPackNameChange(it.take(200)) },
            Modifier.weight(1f),
            label = strings.t("pages.export.name"),
            enabled = !exporting,
        )
        CompactTextField(
            version,
            { onVersionChange(it.take(100)) },
            Modifier.width(150.dp),
            label = strings.t("pages.export.version"),
            placeholder = "1.0.0",
            enabled = !exporting,
        )
        SimpleDropdown(
            format.name,
            PackExportFormat.offeredForExport.map { it.name to it.title },
            onFormatChosen,
            Modifier.width(240.dp),
            enabled = !exporting,
        )
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        CompactTextField(
            target,
            onTargetChange,
            Modifier.weight(1f),
            label = strings.t("pages.export.target"),
            placeholder = "$stem.${format.extension}",
            enabled = !exporting,
        )
        SecondaryButton(
            {
                actions.run {
                    actions.choose(strings.t("pages.export.chooseDir"), directory = true).firstOrNull()?.let {
                        val name = fileName.ifBlank { "$stem.${format.extension}" }
                        onTargetChange(it.resolve(name).toString())
                    }
                }
            },
            Modifier.width(150.dp),
            enabled = !exporting,
        ) {
            Text(strings.t("pages.export.chooseFolder"), fontSize = 12.sp)
        }
        if (format == PackExportFormat.Modrinth) {
            Column(
                Modifier.width(240.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                ExportCompactToggle(
                    title = strings.t("pages.export.remote"),
                    checked = options.remoteResources,
                    enabled = !exporting,
                    onCheckedChange = { checked ->
                        onOptionsChange(
                            options.copy(
                                remoteResources = checked,
                                // 关闭远程查询后，所有勾选内容直接由本地文件写入。
                                includeLocalResources = true,
                            ),
                        )
                    },
                )
            }
        }
    }
}

/** 高级选项和选择文件夹同一行：只保留标题，点整行切换。 */
@Composable
private fun ExportCompactToggle(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(Metrics.innerRadius))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked,
            { onCheckedChange(it) },
            enabled = enabled,
            modifier = Modifier.padding(end = 2.dp),
        )
        Text(
            title,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color =
                if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
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
    excludedPaths: Set<String>,
    includeLogs: Boolean,
    loading: Boolean,
    exporting: Boolean,
    canConfirm: Boolean,
    onExportStart: () -> Unit,
    onExportFailure: (String?) -> Unit,
    onStarted: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
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
                    excludedPaths = excludedPaths,
                    includeLogs = includeLogs,
                    onStarted = onStarted,
                    onDismiss = onDismiss,
                    onFailure = { failure ->
                        onExportFailure(failure.message ?: strings.t("pages.export.failed"))
                    },
                )
            }
        },
        hint = if (loading) strings.t("pages.export.loading") else "",
    )
}
