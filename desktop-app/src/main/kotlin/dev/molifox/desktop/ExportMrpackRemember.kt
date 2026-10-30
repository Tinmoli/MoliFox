// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责导出对话框的记忆派生值：重复渲染不重算选择与本地资源。

package dev.molifox.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.molifox.core.MrpackExportPlan
import dev.molifox.core.PackExportEntry
import dev.molifox.core.PackExportFormat
import dev.molifox.core.PackExportOptions
import dev.molifox.core.PackExportScan
import dev.molifox.core.exportLocalResourcePaths
import dev.molifox.core.exportSelectedPaths

@Composable
internal fun rememberSelectableExportEntries(
    scan: PackExportScan?,
    format: PackExportFormat,
): List<PackExportEntry> =
    remember(scan?.entries, format) {
        scan?.entries.orEmpty().filter {
            it.selectable && (format == PackExportFormat.Backup || !it.backupOnly)
        }
    }

@Composable
internal fun rememberSelectableExportKeys(entries: List<PackExportEntry>): Set<String> =
    remember(entries) { entries.mapTo(mutableSetOf()) { it.key } }

@Composable
internal fun rememberExportLocalPaths(
    prepared: MrpackExportPlan?,
    format: PackExportFormat,
): Set<String> =
    remember(prepared, format) { prepared?.let { exportLocalResourcePaths(it, format) }.orEmpty() }

@Composable
internal fun rememberExportSelection(
    prepared: MrpackExportPlan?,
    format: PackExportFormat,
    options: PackExportOptions,
    selectedEntries: Set<String>,
): Set<String> =
    remember(prepared, format, options, selectedEntries) {
        prepared?.let { exportSelectedPaths(it, format, options, selectedEntries) }.orEmpty()
    }
