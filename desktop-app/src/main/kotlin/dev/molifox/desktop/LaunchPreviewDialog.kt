// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责游戏启动计划和参数预览。

package dev.molifox.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.molifox.core.LaunchPlan
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@Composable
fun LaunchPreviewDialog(plan: LaunchPlan, onDismiss: () -> Unit) {
    val text = buildString {
        appendLine("Java：${plan.executable}")
        appendLine("工作目录：${plan.gameDirectory}")
        appendLine("\n参数（每行一项）：")
        plan.arguments.forEach { appendLine(it) }
        if (plan.environment.isNotEmpty()) {
            appendLine("\n附加环境变量：")
            plan.environment.forEach { (name, value) -> appendLine("$name=$value") }
        }
    }
    Modal("启动参数预览", "显示已保存配置；登录令牌使用占位符，自动 Java 在实际启动时检测。", onDismiss = onDismiss) {
        SelectionContainer {
            Text(
                text,
                Modifier.fillMaxWidth()
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
                Text("复制预览")
            }
            TextButton(onDismiss) { Text("关闭预览") }
        }
    }
}
