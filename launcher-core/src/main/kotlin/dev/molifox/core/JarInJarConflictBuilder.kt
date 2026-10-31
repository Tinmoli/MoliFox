// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责把双方的冲突声明收敛成生效冲突对：只保留双方启用且版本命中的组合。

package dev.molifox.core

/** 一条生效冲突：同一无序对可能被多次声明，保留硬标记、首次出现的声明者与目标 id。 */
private class JarInJarConflictHit(
    val declarer: JarInJarSource,
    val target: JarInJarSource,
    val targetId: String,
    val hard: Boolean,
)

/** 只读计算冲突，不修改注册表。 */
internal class JarInJarConflictBuilder(
    private val hosts: List<JarInJarHost>,
    private val nodesByHost: Map<String, List<JarInJarNodeRef>>,
    private val registry: JarInJarRegistry,
) {
    fun build(): List<JarInJarConflictPair> {
        val pairs = LinkedHashMap<Pair<JarInJarSource, JarInJarSource>, JarInJarConflictHit>()
        hosts.filter { it.enabled }.forEach { host ->
            collectConflicts(
                host,
                JarInJarSource(host.filename),
                host.conflicts,
                host.loader,
                pairs,
            )
            nodesByHost[host.filename].orEmpty().forEach { ref ->
                collectConflicts(
                    host,
                    JarInJarSource(host.filename, ref.node.path),
                    ref.node.conflicts,
                    ref.node.loader,
                    pairs,
                )
            }
        }
        return pairs.entries
            .sortedWith(
                compareBy(
                    { registry.orderOf(it.key.first) },
                    { registry.orderOf(it.key.second) },
                ),
            ).map { (_, hit) -> conflictPair(hit) }
    }

    private fun collectConflicts(
        host: JarInJarHost,
        declarer: JarInJarSource,
        conflicts: List<JarInJarConflict>,
        loader: JarInJarLoader,
        pairs: MutableMap<Pair<JarInJarSource, JarInJarSource>, JarInJarConflictHit>,
    ) {
        conflicts.forEach { conflict ->
            if (conflict.id.isBlank() || conflict.id.lowercase() in jarInJarPlatformIds) {
                return@forEach
            }
            val declaringKey =
                registry.sourceKey(host.filename, declarer.nodePath, conflict.declarerId)
            val actual = registry.declaredSources[declaringKey] ?: declarer
            registry.providers[conflict.id.lowercase()]
                .orEmpty()
                .filter { it.source != actual && it.hostEnabled }
                .filter { versionInRange(conflict.constraint, it.version, loader) }
                .forEach { target ->
                    val pair = registry.orderedPair(actual, target.source)
                    val existing = pairs[pair]
                    pairs[pair] =
                        existing?.let {
                            JarInJarConflictHit(
                                it.declarer,
                                it.target,
                                it.targetId,
                                it.hard || conflict.hard,
                            )
                        } ?: JarInJarConflictHit(actual, target.source, conflict.id, conflict.hard)
                }
        }
    }

    private fun versionInRange(
        constraint: String,
        version: String,
        loader: JarInJarLoader,
    ): Boolean {
        if (constraint.isBlank()) return true
        val cleaned = JarInJarVersioning.stripVersionPrefix(version)
        if (cleaned.isEmpty() || '$' in cleaned) return false
        return JarInJarVersioning.satisfies(constraint, loader, cleaned)
    }

    private fun conflictPair(hit: JarInJarConflictHit): JarInJarConflictPair =
        JarInJarConflictPair(
            hard = hit.hard,
            declarer = hit.declarer.label,
            declarerHost = hit.declarer.hostFilename,
            targetId = hit.targetId,
            target = hit.target.label,
            targetHost = hit.target.hostFilename,
        )
}
