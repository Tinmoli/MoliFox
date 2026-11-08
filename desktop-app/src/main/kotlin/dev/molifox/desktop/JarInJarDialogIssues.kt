// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责「嵌入依赖」弹窗的三个分区：待处理依赖、冲突、依赖关系。只展示结论与
// 搜索关键词，不提供安装、忽略或删除操作。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import dev.molifox.core.JarInJarDependencyRow
import dev.molifox.core.JarInJarStatus
import dev.molifox.core.JarInJarWarning

@Composable
internal fun JarInJarWarningSection(warnings: List<JarInJarWarning>) {
    val strings = LocalStrings.current
    Text(strings.t("dialogs.jar.pendingTitle"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    warnings.forEach { warning -> JarInJarWarningRow(warning) }
}

@Composable
private fun JarInJarWarningRow(warning: JarInJarWarning) {
    val strings = LocalStrings.current
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
            MutedText(strings.t("dialogs.jar.declaredIn", warning.hostFilename, warning.declarer))
            val provided = warning.providerVersions.filter { it.isNotBlank() }
            if (provided.isNotEmpty()) MutedText(strings.t("dialogs.jar.existingVersions", provided.joinToString("、")))
            // 只读弹窗不安装依赖，只把到内容页搜索时可直接使用的关键词告诉用户。
            if (warning.status == JarInJarStatus.Missing && warning.searchTerms.isNotEmpty()) {
                MutedText(strings.t("dialogs.jar.searchTerms", warning.searchTerms.joinToString("、")))
            }
        }
    }
}

@Composable
internal fun JarInJarConflictSection(conflicts: List<JarInJarConflictPair>) {
    val strings = LocalStrings.current
    Text(strings.t("dialogs.jar.conflict"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    conflicts.forEach { conflict -> JarInJarConflictRow(conflict) }
}

@Composable
private fun JarInJarConflictRow(conflict: JarInJarConflictPair) {
    val strings = LocalStrings.current
    val tagColor =
        if (conflict.hard) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Tag(if (conflict.hard) strings.t("dialogs.jar.conflict") else strings.t("dialogs.jar.advice"), tagColor)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                strings.t("dialogs.jar.conflictBetween", conflict.declarer, conflict.target),
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
internal fun JarInJarDependencyLine(row: JarInJarDependencyRow) {
    val strings = LocalStrings.current
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Tag(row.status.title, statusColor(row.status))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                dependencyText(row.dependency.id, row.dependency.constraint) + optionalSuffix(row),
                fontSize = 12.sp,
            )
            val provided = row.providerVersions.filter { it.isNotBlank() }
            if (provided.isNotEmpty()) MutedText(strings.t("dialogs.jar.existingVersions", provided.joinToString("、")))
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

private fun optionalSuffix(row: JarInJarDependencyRow): String =
    if (row.dependency.optional) tr("dialogs.jar.optionalSuffix") else ""

private fun constraintSuffix(constraint: String): String =
    if (constraint.isBlank()) "" else tr("dialogs.jar.rangeSuffix", constraint)
