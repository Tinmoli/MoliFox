// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责「嵌入依赖」弹窗的搜索匹配：四个分区共用同一套关键词规则，避免各自实现后
// 出现「某分区搜不到、另一分区却命中」的不一致。

package dev.molifox.desktop

import dev.molifox.core.EmbeddedJar
import dev.molifox.core.JarInJarDependencyRow
import dev.molifox.core.JarInJarHost

/** 关键词为空视为全部命中；问题区、依赖关系与内嵌模组共用同一套匹配规则。 */
internal fun matches(
    query: String,
    vararg values: String,
): Boolean = query.isBlank() || values.any { it.contains(query, true) }

internal fun flattenNodes(nodes: List<EmbeddedJar>): List<EmbeddedJar> =
    nodes.flatMap { node -> listOf(node) + flattenNodes(node.children) }

internal fun matchesHostOrNode(
    host: JarInJarHost,
    query: String,
): Boolean {
    if (query.isBlank()) return true
    if (matches(query, host.filename, host.modId, host.name)) return true
    return flattenNodes(host.embedded).any { node ->
        matches(query, node.path, node.modId, node.name, node.version, node.identifier)
    }
}

internal fun matchesDependencyRow(
    row: JarInJarDependencyRow,
    query: String,
): Boolean =
    matches(
        query,
        row.hostFilename,
        row.declarer,
        row.dependency.id,
        row.dependency.constraint,
        row.status.title,
    )
