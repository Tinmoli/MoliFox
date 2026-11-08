// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 游戏异常退出后的原因弹窗：标题、建议、关键证据与查看日志入口。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.LaunchIssue

/** 启动失败/崩溃原因弹窗；内容可滚动，避免长堆栈把按钮挤出窗口。 */
@Composable
internal fun LaunchFailureDialog(
    issue: LaunchIssue,
    onViewLogs: () -> Unit,
    onExport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    AlertDialog(
        properties =
            androidx.compose.ui.window
                .DialogProperties(dismissOnClickOutside = false),
        onDismissRequest = onDismiss,
        title = { Text(issue.title, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            ) {
                Text(issue.suggestion, fontSize = 12.sp)
                if (issue.instance.isNotBlank()) {
                    Text(
                        strings.t("dialogs.launchFailure.instance", issue.instance),
                        modifier = Modifier.padding(top = 8.dp),
                        fontSize = 12.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    )
                }
                if (issue.evidence.isNotBlank()) {
                    Text(
                        strings.t("dialogs.launchFailure.evidence"),
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        fontSize = 12.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    )
                    Text(
                        issue.evidence,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = LocalContentColor.current.copy(alpha = 0.75f),
                    )
                }
                if (issue.directory.isNotBlank()) {
                    Text(
                        strings.t("dialogs.launchFailure.directory", issue.directory),
                        modifier = Modifier.padding(top = 10.dp),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            // 求助时给的是可复现的诊断包，而不是一张窗口截图。
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MoliTextButton(onClick = onExport) {
                    Text(strings.t("dialogs.launchFailure.export"))
                }
                MoliTextButton(onClick = onDismiss) {
                    Text(strings.t("dialogs.common.close"))
                }
            }
        },
        dismissButton = {
            MoliTextButton(
                onClick = {
                    onDismiss()
                    onViewLogs()
                },
            ) {
                Text(strings.t("pages.launch.viewLogs"))
            }
        },
    )
}
