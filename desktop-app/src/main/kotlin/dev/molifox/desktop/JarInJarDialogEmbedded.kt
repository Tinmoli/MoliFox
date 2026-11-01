// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责「内嵌模组」区：按宿主分组、同一 modId 的多版本折叠成一行。只读展示候选
// 版本，不安装也不删除；最终加载哪个版本由加载器决定，弹窗只标出已参与索引的版本。

package dev.molifox.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.EmbeddedJar
import dev.molifox.core.JarInJarDependencyRow
import dev.molifox.core.JarInJarHost
import dev.molifox.core.JarInJarLoader

/** 依赖关系行里内嵌声明者的稳定键：`宿主文件名|节点路径`。 */
internal fun embeddedIndexKey(row: JarInJarDependencyRow): String = "${row.hostFilename}|${row.declarer}"

/** 一个候选版本及其是否进入依赖索引；索引内的版本才是本实例真正计算过的候选。 */
private class EmbeddedVersion(
    val jar: EmbeddedJar,
    val indexed: Boolean,
)

/** 同一宿主内按 modId 合并后的一组候选版本。 */
private class EmbeddedGroup(
    val key: String,
    val title: String,
    val loader: JarInJarLoader,
    val versions: List<EmbeddedVersion>,
)

@Composable
internal fun JarInJarEmbeddedSection(
    hosts: List<JarInJarHost>,
    query: String,
    indexedPaths: Set<String>,
) {
    val strings = LocalStrings.current
    Text(strings.t("dialogs.jar.embeddedTitle"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    MutedText(strings.t("dialogs.jar.embeddedHint"))
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val indexedByHost =
        indexedPaths.groupBy({ it.substringBefore('|') }, { it.substringAfter('|') })
    hosts.forEach { host ->
        val nodePaths = indexedByHost[host.filename].orEmpty().toSet()
        JarInJarHostBlock(host, query, nodePaths, expanded)
    }
}

@Composable
private fun JarInJarHostBlock(
    host: JarInJarHost,
    query: String,
    indexedNodePaths: Set<String>,
    expanded: SnapshotStateMap<String, Boolean>,
) {
    val strings = LocalStrings.current
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            host.filename,
            Modifier.weight(1f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!host.enabled) Tag(strings.t("dialogs.jar.disabled"), MaterialTheme.colorScheme.onSurfaceVariant)
        Tag(strings.t("dialogs.jar.embeddedCount", host.embeddedCount), MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (!host.complete && host.note.isNotBlank()) {
        MutedText(host.note, Modifier.padding(start = 22.dp))
    }
    groupsFor(host, query, indexedNodePaths).forEach { group ->
        val only = group.versions.singleOrNull()
        if (only != null) {
            JarInJarVersionLine(group.loader, only)
        } else {
            val open = expanded[group.key] == true
            JarInJarGroupLine(group, open) {
                expanded[group.key] = !open
            }
        }
    }
}

@Composable
private fun JarInJarGroupLine(
    group: EmbeddedGroup,
    open: Boolean,
    onToggle: () -> Unit,
) {
    val strings = LocalStrings.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 22.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            if (open) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,
            contentDescription =
                if (open) {
                    strings.t("dialogs.jar.collapseGroup", group.title)
                } else {
                    strings.t("dialogs.jar.expandGroup", group.title)
                },
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            group.title,
            Modifier.weight(1f),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Tag(strings.t("dialogs.jar.versionCount", group.versions.size), MaterialTheme.colorScheme.onSurfaceVariant)
        Tag(group.loader.title)
    }
    if (!open) return
    group.versions.forEach { version -> JarInJarVersionLine(group.loader, version) }
}

@Composable
private fun JarInJarVersionLine(
    loader: JarInJarLoader,
    version: EmbeddedVersion,
) {
    val strings = LocalStrings.current
    Row(
        Modifier.fillMaxWidth().padding(start = 44.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            version.jar.displayName,
            Modifier.weight(1f),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (version.indexed) Tag(strings.t("dialogs.jar.indexed"), MaterialTheme.colorScheme.primary)
        if (version.jar.version.isNotBlank()) {
            Tag(version.jar.version, MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Tag(loader.title)
    }
    MutedText(versionDetail(version.jar), Modifier.padding(start = 44.dp))
}

/** 按 modId（大小写不敏感）合并同宿主的全部层级节点，搜索时只保留命中的版本。 */
private fun groupsFor(
    host: JarInJarHost,
    query: String,
    indexedNodePaths: Set<String>,
): List<EmbeddedGroup> =
    flattenNodes(host.embedded)
        .groupBy { it.modId.lowercase().ifBlank { it.path.lowercase() } }
        .map { (modId, jars) ->
            EmbeddedGroup(
                key = "${host.filename}|$modId",
                title = jars.first().displayName,
                loader = jars.first().loader,
                versions =
                    jars
                        .map { jar -> EmbeddedVersion(jar, jar.path in indexedNodePaths) }
                        .filter { version ->
                            query.isBlank() || matchesVersion(version.jar, query)
                        },
            )
        }.filter { group -> group.versions.isNotEmpty() }
        .sortedBy { group -> group.title.lowercase() }

private fun matchesVersion(
    jar: EmbeddedJar,
    query: String,
): Boolean = matches(query, jar.path, jar.modId, jar.name, jar.version, jar.identifier)

private fun versionDetail(jar: EmbeddedJar): String =
    buildString {
        append(jar.path)
        append(tr("dialogs.jar.depthSuffix", jar.depth))
        if (jar.targetMc.isNotBlank()) append(" · MC ${jar.targetMc}")
        if (!jar.complete) append(tr("dialogs.jar.incompleteSuffix"))
        if (jar.dependencies.isEmpty()) append(tr("dialogs.jar.noDepsSuffix"))
    }
