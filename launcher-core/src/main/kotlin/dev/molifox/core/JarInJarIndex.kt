// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责把宿主与内嵌节点组织成提供者索引，并算出依赖状态、警告与生效冲突。

package dev.molifox.core

/** 只读索引：构造时由 [JarInJarRegistry] 登记提供者，行/警告/冲突按需计算。 */
internal class JarInJarIndex(
    private val hosts: List<JarInJarHost>,
    private val nodesByHost: Map<String, List<JarInJarNodeRef>>,
    private val incomplete: Boolean,
) {
    private val registry = JarInJarRegistry(hosts, nodesByHost)
    private val conflictBuilder = JarInJarConflictBuilder(hosts, nodesByHost, registry)

    /** 声明者自己声明的依赖（未上浮内嵌节点），供关系区按宿主/节点分卡。 */
    val rows: List<JarInJarDependencyRow> by lazy { buildRows() }

    /** 需要用户处理的依赖项；报告不完整时抑制缺失与版本不符，避免假警报。 */
    val warnings: List<JarInJarWarning> by lazy { buildWarnings() }

    /** 双方宿主都启用、且对方版本命中冲突范围的冲突；同一无序对只保留一条并取硬。 */
    val conflicts: List<JarInJarConflictPair> by lazy { conflictBuilder.build() }

    private fun buildRows(): List<JarInJarDependencyRow> {
        val rows = mutableListOf<JarInJarDependencyRow>()
        val seen = HashSet<String>()

        fun add(row: JarInJarDependencyRow) {
            val key =
                "${row.hostFilename}\u0000${row.declarer}\u0000${row.dependency.id.lowercase()}" +
                    "\u0000${row.dependency.constraint}\u0000${row.dependency.optional}"
            if (seen.add(key)) rows += row
        }
        hosts.forEach { host ->
            val hostSource = JarInJarSource(host.filename)
            host.dependencies.forEach { dependency ->
                add(dependencyRow(host, hostSource, embedded = false, host.loader, dependency))
            }
            nodesByHost[host.filename].orEmpty().forEach { ref ->
                val source = JarInJarSource(host.filename, ref.node.path)
                ref.node.dependencies.forEach { dependency ->
                    add(dependencyRow(host, source, embedded = true, ref.node.loader, dependency))
                }
            }
        }
        return rows
    }

    private fun dependencyRow(
        host: JarInJarHost,
        declarer: JarInJarSource,
        embedded: Boolean,
        loader: JarInJarLoader,
        dependency: JarInJarRequirement,
    ): JarInJarDependencyRow =
        JarInJarDependencyRow(
            hostFilename = host.filename,
            declarer = declarer.label,
            embedded = embedded,
            loader = loader,
            dependency = dependency,
            status = status(host, declarer, dependency, loader),
            providerVersions = providerVersions(declarer, dependency.id),
        )

    private fun status(
        host: JarInJarHost,
        declarer: JarInJarSource,
        dependency: JarInJarRequirement,
        loader: JarInJarLoader,
    ): JarInJarStatus {
        // 平台伪依赖由加载器/游戏自己提供，不当作可安装依赖检查。
        if (dependency.id.lowercase() in jarInJarPlatformIds) return JarInJarStatus.Installed
        val bundled = registry.selfBundled[host.filename].orEmpty()
        val bundledHit =
            bundled.any {
                it.id.equals(dependency.id, true) &&
                    versionSatisfies(dependency, it.version, loader)
            }
        if (bundledHit) return JarInJarStatus.Bundled
        val candidates =
            registry.providers[dependency.id.lowercase()]
                .orEmpty()
                .filter { it.source != declarer }
        val satisfying = candidates.filter { versionSatisfies(dependency, it.version, loader) }
        return when {
            satisfying.any { it.hostEnabled } -> JarInJarStatus.Installed
            satisfying.isNotEmpty() -> JarInJarStatus.Disabled
            candidates.isNotEmpty() -> JarInJarStatus.VersionMismatch
            else -> JarInJarStatus.Missing
        }
    }

    private fun versionSatisfies(
        dependency: JarInJarRequirement,
        version: String,
        loader: JarInJarLoader,
    ): Boolean {
        if (dependency.constraint.isBlank()) return true
        val cleaned = JarInJarVersioning.stripVersionPrefix(version)
        // 提供者版本为空或含未展开占位符时不做版本否决，避免把可加载的依赖报成版本不符。
        if (cleaned.isEmpty() || '$' in cleaned) return true
        val satisfied = JarInJarVersioning.satisfies(dependency.constraint, loader, cleaned)
        if (satisfied || loader != JarInJarLoader.Unknown) return satisfied
        return !JarInJarVersioning.hasComparableBound(dependency.constraint)
    }

    private fun providerVersions(
        declarer: JarInJarSource,
        id: String,
    ): List<String> =
        registry.providers[id.lowercase()]
            .orEmpty()
            .filter { it.source != declarer }
            .map { it.version }
            .filter { it.isNotBlank() }
            .distinct()

    private fun buildWarnings(): List<JarInJarWarning> =
        rows.mapNotNull { row ->
            if (row.dependency.optional) return@mapNotNull null
            val actionable =
                row.status == JarInJarStatus.Missing ||
                    row.status == JarInJarStatus.VersionMismatch ||
                    row.status == JarInJarStatus.Disabled
            if (!actionable) return@mapNotNull null
            val uncertain =
                row.status == JarInJarStatus.Missing ||
                    row.status == JarInJarStatus.VersionMismatch
            if (uncertain && incomplete) return@mapNotNull null
            val key =
                "${row.hostFilename}|${row.dependency.id}|${row.dependency.constraint}"
                    .lowercase()
            JarInJarWarning(
                key = key,
                hostFilename = row.hostFilename,
                declarer = row.declarer,
                dependencyId = row.dependency.id,
                constraint = row.dependency.constraint,
                optional = row.dependency.optional,
                status = row.status,
                searchTerms = listOf(row.dependency.id),
                providerVersions = row.providerVersions,
            )
        }
}
