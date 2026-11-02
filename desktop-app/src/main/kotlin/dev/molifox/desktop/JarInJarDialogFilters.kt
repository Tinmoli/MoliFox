// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责「嵌入依赖」弹窗的搜索匹配：四个分区共用同一套关键词规则，避免各自实现后
// 出现「某分区搜不到、另一分区却命中」的不一致。

package dev.molifox.desktop

import dev.molifox.core.JarInJarDependencyRow

/** 关键词为空视为全部命中；问题区、依赖关系与内嵌模组共用同一套匹配规则。 */
internal fun matches(
    query: String,
    vararg values: String,
): Boolean = query.isBlank() || values.any { it.contains(query, true) }

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
