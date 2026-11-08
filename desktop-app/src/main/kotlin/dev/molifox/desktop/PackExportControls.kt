// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 共享整合包导出的选项与文件行，避免大弹窗重复构造控件。

package dev.molifox.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.PackExportEntry
import dev.molifox.core.PackExportNode
import dev.molifox.core.exportPathExcluded

/** 实例名转成安全的导出文件名：替换 Windows 不允许的字符，避免导出时才失败。 */
internal fun exportFileStem(name: String): String =
    name
        .map { if (it < ' ' || it in "<>:\"/\\|?*") '_' else it }
        .joinToString("")
        .trim()
        .trim('.')
        .ifBlank { "instance" }

/** 导出内容的中文名称；未知目录保留原名，避免凭空翻译。 */
internal fun exportEntryLabel(name: String): String =
    when (name.lowercase()) {
        "mods" -> tr("dialogs.pack.mods")
        "resourcepacks" -> tr("dialogs.pack.resourcePacks")
        "shaderpacks" -> tr("dialogs.pack.shaderPacks")
        "saves" -> tr("dialogs.pack.saves")
        "screenshots" -> tr("dialogs.pack.screenshots")
        "schematics" -> tr("dialogs.pack.schematics")
        "config" -> tr("dialogs.pack.config")
        "defaultconfigs" -> tr("dialogs.pack.defaultConfigs")
        "datapacks" -> tr("dialogs.pack.datapacks")
        "servers.dat" -> tr("dialogs.pack.serverList")
        else -> name
    }

/** 顶层内容一行：展开箭头 + 复选框 + 文件夹名/中文名 + 文件数与磁盘体积。 */
@Composable
internal fun ExportEntryRow(
    entry: PackExportEntry,
    checked: Boolean,
    enabled: Boolean,
    excludedPaths: Set<String>,
    onToggleNode: (String, Boolean) -> Unit,
    onCheckedChange: (Boolean) -> Unit,
) {
    val strings = LocalStrings.current
    var expanded by remember(entry.key) { mutableStateOf(false) }
    val size =
        when {
            entry.scanning -> "…"
            entry.directory -> strings.t("dialogs.pack.fileSummary", entry.fileCount, byteSizeText(entry.totalBytes))
            else -> byteSizeText(entry.totalBytes)
        }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Metrics.innerRadius))
                .clickable(enabled = enabled) { onCheckedChange(!checked) }
                .padding(vertical = 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ExportExpandArrow(
                expanded = expanded,
                visible = entry.children.isNotEmpty(),
                onClick = { expanded = !expanded },
            )
            CompactCheckbox(checked, enabled) { onCheckedChange(it) }
            Column(Modifier.weight(1f)) {
                Text(
                    exportEntryTitle(entry.name),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color =
                        if (enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                if (!enabled) {
                    Text(
                        if (entry.unavailable) {
                            strings.t("dialogs.pack.sharedDir")
                        } else {
                            strings.t("dialogs.pack.logsOnlyFull")
                        },
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(size, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) {
            entry.children.forEach {
                ExportNodeRow(
                    node = it,
                    depth = 1,
                    parentChecked = checked,
                    excludedPaths = excludedPaths,
                    onToggle = onToggleNode,
                )
            }
        }
    }
}

/** 文件夹名在前，中文说明在后；未知名称只显示原名。 */
private fun exportEntryTitle(name: String): String {
    val label = exportEntryLabel(name)
    return if (label == name) name else "$name  $label"
}

@Composable
private fun ExportNodeRow(
    node: PackExportNode,
    depth: Int,
    parentChecked: Boolean,
    excludedPaths: Set<String>,
    onToggle: (String, Boolean) -> Unit,
) {
    var expanded by remember(node.path) { mutableStateOf(false) }
    val excluded = exportPathExcluded(node.path, excludedPaths)
    val checked = parentChecked && !excluded
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = (depth * 16).dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ExportExpandArrow(
                expanded = expanded,
                visible = node.children.isNotEmpty(),
                onClick = { expanded = !expanded },
            )
            CompactCheckbox(checked, parentChecked) { onToggle(node.path, it) }
            Text(
                node.name,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        if (expanded) {
            node.children.forEach {
                ExportNodeRow(
                    node = it,
                    depth = depth + 1,
                    parentChecked = checked,
                    excludedPaths = excludedPaths,
                    onToggle = onToggle,
                )
            }
        }
    }
}

@Composable
private fun CompactCheckbox(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Checkbox(
            checked,
            onCheckedChange,
            enabled = enabled,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ExportExpandArrow(
    expanded: Boolean,
    visible: Boolean,
    onClick: () -> Unit,
) {
    if (!visible) {
        Spacer(Modifier.size(18.dp))
        return
    }
    val strings = LocalStrings.current
    val icon =
        if (expanded) {
            Icons.Filled.KeyboardArrowDown
        } else {
            Icons.AutoMirrored.Filled.KeyboardArrowRight
        }
    Icon(
        icon,
        contentDescription = if (expanded) strings.t("dialogs.export.collapse") else strings.t("dialogs.export.expand"),
        modifier =
            Modifier
                .size(18.dp)
                .clickable(onClick = onClick),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
