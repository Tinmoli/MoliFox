// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责登记宿主、内嵌节点与兄弟 mod 提供的 id，并维护来源的稳定顺序。

package dev.molifox.core

/** 平台与加载器伪依赖：不参与缺失判定，也不产生提供者。 */
internal val jarInJarPlatformIds =
    setOf(
        "minecraft",
        "forge",
        "neoforge",
        "fabricloader",
        "quilt",
        "quilt_loader",
        "java",
        "mcp",
    )

/** 一个提供者来源：宿主自身、内嵌节点，或 Forge 多 mod JAR 里的兄弟 mod。 */
internal data class JarInJarSource(
    val hostFilename: String,
    /** 空串表示宿主本身。 */
    val nodePath: String = "",
    /** 兄弟 mod 或带独立版本的 provided id。 */
    val ownedId: String = "",
) {
    val label: String
        get() =
            when {
                nodePath.isNotEmpty() -> nodePath
                ownedId.isNotEmpty() -> "$hostFilename!/@$ownedId"
                else -> hostFilename
            }
}

internal data class JarInJarProvider(
    val source: JarInJarSource,
    val hostFilename: String,
    val hostEnabled: Boolean,
    val version: String,
)

/** 只负责登记，不做状态判断；缺失/版本判定在 [JarInJarIndex]。 */
internal class JarInJarRegistry(
    hosts: List<JarInJarHost>,
    nodesByHost: Map<String, List<JarInJarNodeRef>>,
) {
    val providers = LinkedHashMap<String, MutableList<JarInJarProvider>>()
    val selfBundled = LinkedHashMap<String, List<JarInJarProvided>>()
    val declaredSources = HashMap<String, JarInJarSource>()
    private val sourceOrder = LinkedHashMap<JarInJarSource, Int>()

    init {
        hosts.forEach { host -> registerHost(host, nodesByHost[host.filename].orEmpty()) }
    }

    fun orderOf(source: JarInJarSource): Int = sourceOrder.getOrPut(source) { sourceOrder.size }

    fun orderedPair(
        left: JarInJarSource,
        right: JarInJarSource,
    ): Pair<JarInJarSource, JarInJarSource> = if (orderOf(left) <= orderOf(right)) left to right else right to left

    fun sourceKey(
        hostFilename: String,
        nodePath: String,
        id: String,
    ): String = "$hostFilename\u0000$nodePath\u0000${id.lowercase()}"

    private fun registerHost(
        host: JarInJarHost,
        nodes: List<JarInJarNodeRef>,
    ) {
        val source = JarInJarSource(host.filename)
        val bundled = mutableListOf<JarInJarProvided>()
        addBundled(host.modId, host.version, bundled)
        host.provides.forEach { addBundled(it.id, it.version.ifBlank { host.version }, bundled) }
        nodes.forEach { ref -> addNodeBundled(ref.node, bundled) }
        selfBundled[host.filename] = bundled.distinct()
        registerSource(host.filename, "", host.modId, source)
        addProvider(host, source, host.modId, host.version)
        host.provides.forEach { provided ->
            val owned = providerSource(host.filename, "", provided.id, provided.version)
            registerSource(host.filename, "", provided.id, owned)
            addProvider(host, owned, provided.id, provided.version.ifBlank { host.version })
        }
        nodes.forEach { ref -> registerNode(host, ref.node) }
    }

    private fun registerNode(
        host: JarInJarHost,
        node: EmbeddedJar,
    ) {
        val source = JarInJarSource(host.filename, node.path)
        registerSource(host.filename, node.path, node.modId, source)
        addProvider(host, source, node.modId, node.version)
        node.provides.forEach { provided ->
            val owned = providerSource(host.filename, node.path, provided.id, provided.version)
            registerSource(host.filename, node.path, provided.id, owned)
            addProvider(host, owned, provided.id, provided.version.ifBlank { node.version })
        }
    }

    private fun addNodeBundled(
        node: EmbeddedJar,
        target: MutableList<JarInJarProvided>,
    ) {
        addBundled(node.modId, node.version, target)
        node.provides.forEach { addBundled(it.id, it.version.ifBlank { node.version }, target) }
    }

    private fun addBundled(
        id: String,
        version: String,
        target: MutableList<JarInJarProvided>,
    ) {
        if (id.isNotBlank()) target += JarInJarProvided(id, version)
    }

    private fun providerSource(
        hostFilename: String,
        nodePath: String,
        id: String,
        providedVersion: String,
    ): JarInJarSource =
        if (providedVersion.isBlank()) {
            JarInJarSource(hostFilename, nodePath)
        } else {
            JarInJarSource(hostFilename, nodePath, id.lowercase())
        }

    private fun registerSource(
        hostFilename: String,
        nodePath: String,
        id: String,
        source: JarInJarSource,
    ) {
        if (id.isNotBlank()) declaredSources[sourceKey(hostFilename, nodePath, id)] = source
    }

    private fun addProvider(
        host: JarInJarHost,
        source: JarInJarSource,
        id: String,
        version: String,
    ) {
        if (id.isBlank()) return
        val list = providers.getOrPut(id.lowercase()) { mutableListOf() }
        val duplicate =
            list.any {
                it.source == source && it.version == version && it.hostFilename == host.filename
            }
        if (!duplicate) {
            list += JarInJarProvider(source, host.filename, host.enabled, version)
        }
        orderOf(source)
    }
}
