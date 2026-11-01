// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责导出对话框的记忆派生值：重复渲染不重算选择与本地资源。

package dev.molifox.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.molifox.core.PackExportEntry
import dev.molifox.core.PackExportFormat
import dev.molifox.core.PackExportScan

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
