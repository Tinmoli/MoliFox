// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 集中实例整理结果展示，避免删除动作混入只读报告。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.visible
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.TidyReport

/** 报告里可读的字节数；只用于展示，不参与业务计算。 */
internal fun reportBytes(bytes: Long): String =
    when {
        bytes >= 1073741824L -> "%.1f GB".format(bytes / 1073741824f)
        bytes >= 1048576L -> "%.1f MB".format(bytes / 1048576f)
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024f)
        else -> bytes.toString() + " B"
    }

/** 报告行：44dp 高、路径省略、行尾状态标签；只读，不提供点击或删除。 */
@Composable
private fun ReportFileRow(
    title: String,
    tag: String,
    color: Color,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Metrics.rowHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            title,
            Modifier.weight(1f),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Tag(tag, color)
    }
}

/** 列表过长时先只渲染前 [limit] 条，其余用一行说明；只影响展示。 */
internal fun List<String>.visible(limit: Int) = take(limit)

/** 整理结果：报告删除了多少空目录、统计到多少 `.jar.disabled`、重复模组分组、 残留文件与可回收字节。全部只报告——对话框内不提供任何删除按钮。 */
@Composable
fun TidyReportDialog(
    instanceName: String,
    report: TidyReport,
    onDismiss: () -> Unit,
) {
    Modal("整理实例目录", instanceName, onDismiss = onDismiss) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSection("整理结果", "只清理实例目录内的空目录，模组与存档一律不动。") {
                SettingRow("删除空目录", "实例目录中不再使用的空文件夹") {
                    Text("${report.removedEmptyDirs} 个", fontSize = 13.sp)
                }
                SettingRow("已停用模组", "mods/ 中以 .jar.disabled 结尾的文件，仅统计") {
                    Text("${report.movedDisabledMods} 个", fontSize = 13.sp)
                }
                SettingRow("重复模组可回收", "内容完全相同（同 SHA-1）的多余副本") {
                    Text(reportBytes(report.totalBytes), fontSize = 13.sp)
                }
            }
            SettingsSection(
                "重复模组（${report.duplicateMods.size} 组）",
                "每组内容相同，只报告，不会自动清理或删除。",
            ) {
                if (report.duplicateMods.isEmpty()) {
                    MutedText("没有发现内容重复的模组。")
                } else {
                    report.duplicateMods.forEach { group ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = Metrics.rowHeight)
                                .padding(vertical = 3.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    group.first(),
                                    Modifier.weight(1f),
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Tag("${group.size} 份", MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (group.size > 1) {
                                Text(
                                    group.drop(1).joinToString(" · "),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            SettingsSection(
                "残留文件（${report.orphanFiles.size} 个）",
                "mods/ 中既不是 .jar 也不是 .jar.disabled 的文件，只报告。",
            ) {
                val orphans = report.orphanFiles.visible(50)
                if (orphans.isEmpty()) {
                    MutedText("没有发现残留文件。")
                } else {
                    orphans.forEach { path ->
                        ReportFileRow(
                            path,
                            "只报告",
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (report.orphanFiles.size > orphans.size) {
                    MutedText("还有 ${report.orphanFiles.size - orphans.size} 条未显示。")
                }
            }
        }
        Text(
            "整理不会删除模组或存档。需要清理重复模组时，请自己确认后在文件管理器中处理。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DialogActions(confirmText = "知道了", onConfirm = onDismiss, onCancel = null)
    }
}
