// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责按实例游戏版本与 Forge JarJar 浮动版本规则筛选可加载的内嵌节点。

package dev.molifox.core

import java.util.Comparator

/** 可加载的内嵌节点：宿主启用状态决定提供者算「已安装」还是「已禁用」。 */
internal data class JarInJarNodeRef(
    val hostFilename: String,
    val hostEnabled: Boolean,
    val node: EmbeddedJar,
)

/** 只做筛选，不修改树；排序与去重留给索引层。 */
internal object JarInJarSelection {
    /** 返回每个宿主的可加载节点（含层级展开）；禁用宿主只做 MC 过滤，不参与浮动版本选择。 */
    fun loadableByHost(
        hosts: List<JarInJarHost>,
        instanceMc: String,
    ): Map<String, List<JarInJarNodeRef>> {
        val enabledNodes = hosts.filter { it.enabled }.flatMap { collectLoadable(it, instanceMc) }
        val selections = forgeSelections(enabledNodes)
        val selectedEnabled = enabledNodes.filter { forgeSelected(it.node, selections) }
        val enabledByHost = selectedEnabled.groupBy { it.hostFilename }
        return hosts.associate { host ->
            val nodes =
                if (host.enabled) {
                    enabledByHost[host.filename].orEmpty()
                } else {
                    collectLoadable(host, instanceMc)
                }
            host.filename to nodes
        }
    }

    private fun collectLoadable(
        host: JarInJarHost,
        instanceMc: String,
    ): List<JarInJarNodeRef> {
        val nodes = mutableListOf<JarInJarNodeRef>()

        fun walk(list: List<EmbeddedJar>) {
            for (node in list) {
                if (!nodeLoads(node, instanceMc)) continue
                nodes += JarInJarNodeRef(host.filename, host.enabled, node)
                walk(node.children)
            }
        }
        walk(host.embedded)
        return nodes
    }

    /**
     * 节点在当前实例会不会被加载：
     * 必选且带约束的 minecraft 依赖全满足 → 加载；只声明了可选 → 加载；自身目标版本满足或
     * 文件名/版本里出现完整版本词 → 加载；否则不加载。
     */
    private fun nodeLoads(
        node: EmbeddedJar,
        instanceMc: String,
    ): Boolean {
        if (instanceMc.isBlank()) return true
        val minecraft =
            node.dependencies.filter { it.id.equals("minecraft", ignoreCase = true) }
        val required = minecraft.filter { !it.optional }
        return if (required.isNotEmpty()) {
            val constrained = required.filter { it.constraint.isNotBlank() }
            val satisfied =
                constrained.isEmpty() ||
                    constrained.all {
                        JarInJarVersioning.satisfies(it.constraint, node.loader, instanceMc)
                    }
            satisfied || containsInstanceVersion(node, instanceMc)
        } else {
            minecraft.isNotEmpty() ||
                node.targetMc.isBlank() ||
                JarInJarVersioning.satisfies(node.targetMc, node.loader, instanceMc) ||
                containsInstanceVersion(node, instanceMc)
        }
    }

    private fun containsInstanceVersion(
        node: EmbeddedJar,
        instanceMc: String,
    ): Boolean =
        JarInJarVersioning.containsVersionToken(node.path, instanceMc) ||
            JarInJarVersioning.containsVersionToken(node.version, instanceMc)

    /** 同组（group:artifact）选出让组内所有节点都满足约束的最高版本；选不出时整组排除。 */
    private fun forgeSelections(nodes: List<JarInJarNodeRef>): Map<String, String> {
        val selections = LinkedHashMap<String, String>()
        nodes
            .filter { it.node.identifier.isNotBlank() }
            .groupBy { it.node.identifier }
            .forEach { (identifier, group) ->
                val versions =
                    group
                        .map { artifactVersion(it.node) }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .sortedWith(
                            Comparator { left, right ->
                                JarInJarVersioning.compare(right, left)
                            },
                        )
                selections[identifier] =
                    versions
                        .firstOrNull { candidate ->
                            group.all { rangeContains(it.node.versionRange, candidate) }
                        }.orEmpty()
            }
        return selections
    }

    private fun forgeSelected(
        node: EmbeddedJar,
        selections: Map<String, String>,
    ): Boolean {
        if (node.identifier.isBlank()) return true
        val selected = selections[node.identifier] ?: return false
        if (selected.isBlank()) return false
        return JarInJarVersioning.compare(artifactVersion(node), selected) == 0
    }

    private fun artifactVersion(node: EmbeddedJar): String = node.artifactVersion.ifBlank { node.version }

    /** 纯版本范围按 Maven 相等比较，区间范围走约束求值。 */
    private fun rangeContains(
        range: String,
        candidate: String,
    ): Boolean {
        val trimmed = range.trim()
        if (trimmed.isEmpty()) return true
        return if (trimmed[0] != '[' && trimmed[0] != '(') {
            JarInJarVersioning.compare(candidate, trimmed) == 0
        } else {
            JarInJarVersioning.satisfies(trimmed, JarInJarLoader.Forge, candidate)
        }
    }
}
