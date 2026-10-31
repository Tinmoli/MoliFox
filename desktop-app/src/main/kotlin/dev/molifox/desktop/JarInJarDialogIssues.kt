// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责「嵌入依赖」弹窗的待处理依赖与冲突区：只展示，不提供安装、忽略或删除操作。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.JarInJarConflictPair
import dev.molifox.core.JarInJarStatus
import dev.molifox.core.JarInJarWarning

@Composable
internal fun JarInJarWarningSection(warnings: List<JarInJarWarning>) {
    Text("待处理依赖", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    if (warnings.isEmpty()) {
        MutedText("没有匹配的依赖。")
        return
    }
    warnings.forEach { warning -> JarInJarWarningRow(warning) }
}

@Composable
private fun JarInJarWarningRow(warning: JarInJarWarning) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Tag(warning.status.title, statusColor(warning.status))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                dependencyText(warning.dependencyId, warning.constraint),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            MutedText("${warning.hostFilename} 内的 ${warning.declarer} 声明")
            val provided = warning.providerVersions.filter { it.isNotBlank() }
            if (provided.isNotEmpty()) MutedText("现有版本：${provided.joinToString("、")}")
        }
    }
}

@Composable
internal fun JarInJarConflictSection(conflicts: List<JarInJarConflictPair>) {
    Text("冲突", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    if (conflicts.isEmpty()) {
        MutedText("没有匹配的冲突。")
        return
    }
    conflicts.forEach { conflict -> JarInJarConflictRow(conflict) }
}

@Composable
private fun JarInJarConflictRow(conflict: JarInJarConflictPair) {
    val tagColor =
        if (conflict.hard) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Tag(if (conflict.hard) "冲突" else "建议", tagColor)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "${conflict.declarer} 与 ${conflict.target}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            MutedText(
                "${conflict.declarerHost} ↔ ${conflict.targetHost}" +
                    constraintSuffix(conflict.constraint),
            )
        }
    }
}

@Composable
private fun statusColor(status: JarInJarStatus): Color =
    when (status) {
        JarInJarStatus.Missing -> MaterialTheme.colorScheme.error
        JarInJarStatus.VersionMismatch -> MaterialTheme.colorScheme.tertiary
        JarInJarStatus.Disabled -> MaterialTheme.colorScheme.onSurfaceVariant
        JarInJarStatus.Installed, JarInJarStatus.Bundled -> MaterialTheme.colorScheme.primary
    }

private fun dependencyText(
    id: String,
    constraint: String,
): String = if (constraint.isBlank()) id else "$id $constraint"

private fun constraintSuffix(constraint: String): String =
    if (constraint.isBlank()) "" else " · 范围 $constraint"
