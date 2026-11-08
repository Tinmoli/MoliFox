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
    val strings = LocalStrings.current
    Modal(strings.t("dialogs.tidy.title"), instanceName, onDismiss = onDismiss) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSection(strings.t("dialogs.tidy.result"), strings.t("dialogs.tidy.resultHint")) {
                SettingRow(strings.t("dialogs.tidy.emptyDirs"), strings.t("dialogs.tidy.emptyDirsHint")) {
                    Text(strings.t("dialogs.tidy.count", report.removedEmptyDirs), fontSize = 13.sp)
                }
                SettingRow(strings.t("dialogs.tidy.disabledMods"), strings.t("dialogs.tidy.disabledModsHint")) {
                    Text(strings.t("dialogs.tidy.disabledCount", report.movedDisabledMods), fontSize = 13.sp)
                }
                SettingRow(strings.t("dialogs.tidy.duplicateMods"), strings.t("dialogs.tidy.duplicateModsHint")) {
                    Text(byteSizeText(report.totalBytes), fontSize = 13.sp)
                }
            }
            SettingsSection(
                strings.t("dialogs.tidy.duplicateTitle", report.duplicateMods.size),
                strings.t("dialogs.tidy.duplicateHint"),
            ) {
                if (report.duplicateMods.isEmpty()) {
                    MutedText(strings.t("dialogs.tidy.noDuplicates"))
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
                                Tag(
                                    strings.t("dialogs.tidy.copyCount", group.size),
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                )
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
                strings.t("dialogs.tidy.orphanTitle", report.orphanFiles.size),
                strings.t("dialogs.tidy.orphanHint"),
            ) {
                val orphans = report.orphanFiles.visible(50)
                if (orphans.isEmpty()) {
                    MutedText(strings.t("dialogs.tidy.noOrphans"))
                } else {
                    orphans.forEach { path ->
                        ReportFileRow(
                            path,
                            strings.t("dialogs.tidy.reportOnly"),
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (report.orphanFiles.size > orphans.size) {
                    MutedText(strings.t("dialogs.tidy.moreHidden", report.orphanFiles.size - orphans.size))
                }
            }
        }
        Text(
            strings.t("dialogs.tidy.footnote"),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DialogActions(confirmText = strings.t("dialogs.common.gotIt"), onConfirm = onDismiss, onCancel = null)
    }
}
