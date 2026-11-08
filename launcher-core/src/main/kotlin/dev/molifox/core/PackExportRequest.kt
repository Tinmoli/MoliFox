// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core

import java.nio.file.Path

/** 点击导出时固定格式、目标和勾选；目录读取与来源查询均由后台任务执行。 */
data class PackExportRequest(
    val instanceId: String,
    val metadata: MrpackExportMetadata,
    val destination: Path,
    val selectedEntries: Set<String>,
    val excludedPaths: Set<String> = emptySet(),
    val format: PackExportFormat = PackExportFormat.Modrinth,
    val options: PackExportOptions = PackExportOptions(),
)

internal class ExportPreparation(
    val request: PackExportRequest,
    val context: JobContext?,
)

internal suspend fun ContentService.prepareRequestedExport(
    request: PackExportRequest,
    context: JobContext? = null,
): MrpackExportPlan =
    MrpackExporter(core.store, core.network, core.api, core.curseForge).preview(
        request.instanceId,
        request.metadata,
        resolveDownloads = request.format == PackExportFormat.Modrinth && request.options.remoteResources,
        includeLogs = request.format == PackExportFormat.Backup && request.options.includeLogs,
        preparation = ExportPreparation(request, context),
    )
