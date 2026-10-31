// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 共享整合包导出的选项与文件行，避免大弹窗重复构造控件。

package dev.molifox.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.PackExportEntry

/** 实例名转成安全的导出文件名：替换 Windows 不允许的字符，避免导出时才失败。 */
internal fun exportFileStem(name: String): String =
    name
        .map { if (it < ' ' || it in "<>:\"/\\|?*") '_' else it }
        .joinToString("")
        .trim()
        .trim('.')
        .ifBlank { "instance" }

/** 「选择内容」的一行：复选框 + 顶层条目名 + 文件数与磁盘体积；扫描中显示 `…`。 */
@Composable
internal fun ExportEntryRow(
    entry: PackExportEntry,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val size =
        when {
            entry.scanning -> "…"
            entry.directory -> "${entry.fileCount} 个文件 · ${reportBytes(entry.totalBytes)}"
            else -> reportBytes(entry.totalBytes)
        }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Metrics.innerRadius))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked, { onCheckedChange(it) }, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
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
                    if (entry.unavailable) "共用游戏目录，不写入导出包" else "仅完整备份可导出日志",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(size, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
