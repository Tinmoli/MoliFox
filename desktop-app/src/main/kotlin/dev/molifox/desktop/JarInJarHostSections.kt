// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 嵌入依赖的「依赖关系」分区：宿主模组可折叠，展开后列出声明与内嵌节点。

package dev.molifox.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.JarInJarDependencyRow
import dev.molifox.core.JarInJarHost

/** 依赖关系：每个模组一行，默认折叠；展开后列出它自己声明的依赖与内嵌节点。 */
@Composable
internal fun JarInJarDependencySection(
    hosts: List<JarInJarHost>,
    rows: List<JarInJarDependencyRow>,
    query: String,
) {
    val strings = LocalStrings.current
    Text(strings.t("dialogs.jar.dependencies"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    hosts.forEach { host ->
        val hostRows = rows.filter { it.hostFilename == host.filename }
        // 搜索时直接展开命中的模组，避免用户还要自己点开逐条找。
        val open = expanded[host.filename] ?: query.isNotBlank()
        JarInJarHostHeader(host, hostRows.size, open) { expanded[host.filename] = !open }
        if (open) JarInJarHostBody(host, hostRows)
    }
}

@Composable
private fun JarInJarHostHeader(
    host: JarInJarHost,
    rowCount: Int,
    open: Boolean,
    onToggle: () -> Unit,
) {
    val strings = LocalStrings.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = if (open) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,
            contentDescription =
                strings.t(
                    if (open) "dialogs.jar.collapseGroup" else "dialogs.jar.expandGroup",
                    host.displayName,
                ),
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            host.displayName,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        if (!host.enabled) Tag(strings.t("dialogs.jar.disabled"), MaterialTheme.colorScheme.onSurfaceVariant)
        MutedText(hostSummary(host, rowCount))
    }
}

/** 折叠状态下也给出规模提示：几条依赖、几个内嵌。 */
private fun hostSummary(
    host: JarInJarHost,
    rowCount: Int,
): String =
    buildList {
        if (rowCount > 0) add(tr("dialogs.jar.hostDependencyCount", rowCount))
        if (host.embeddedCount > 0) add(tr("dialogs.jar.embeddedCount", host.embeddedCount))
        if (isEmpty()) add(tr("dialogs.jar.noDependencies"))
    }.joinToString(" · ")

@Composable
private fun JarInJarHostBody(
    host: JarInJarHost,
    rows: List<JarInJarDependencyRow>,
) {
    Column(Modifier.padding(start = 22.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.groupBy { it.declarer }.forEach { (declarer, declarerRows) ->
            if (declarer != host.filename) {
                MutedText(tr("dialogs.jar.embeddedFrom", declarer))
            }
            declarerRows.forEach { row -> JarInJarDependencyLine(row) }
        }
    }
}
