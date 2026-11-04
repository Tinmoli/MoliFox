// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责游戏启动计划和参数预览。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import dev.molifox.desktop.MoliTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.molifox.core.LaunchPlan
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@Composable
fun LaunchPreviewDialog(
    plan: LaunchPlan,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    val text =
        buildString {
            appendLine("Java：${plan.executable}")
            appendLine(strings.t("dialogs.launchPreview.workingDir", plan.gameDirectory))
            appendLine(strings.t("dialogs.launchPreview.arguments"))
            plan.arguments.forEach { appendLine(it) }
            if (plan.environment.isNotEmpty()) {
                appendLine(strings.t("dialogs.launchPreview.env"))
                plan.environment.forEach { (name, value) -> appendLine("$name=$value") }
            }
        }
    Modal(
        strings.t("dialogs.launchPreview.title"),
        strings.t("dialogs.launchPreview.subtitle"),
        onDismiss = onDismiss,
    ) {
        SelectionContainer {
            Text(
                text,
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                fontFamily = LocalLauncherFont.current,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton({
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
            }) {
                Text(strings.t("dialogs.launchPreview.copy"))
            }
            TextButton(onDismiss) { Text(strings.t("dialogs.launchPreview.close")) }
        }
    }
}
