// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责嵌入依赖扫描结果的内存 LRU 缓存：不落盘，启用/禁用改名后仍命中。

package dev.molifox.core

import java.nio.file.Path

private const val CACHE_CAPACITY = 512
private const val LOAD_FACTOR = .75f
private const val DISABLED_SUFFIX = ".disabled"

/** 指纹命中条件：同一归一化路径 + 文件大小 + 修改时间。 */
internal class JarInJarCache(
    private val capacity: Int = CACHE_CAPACITY,
) {
    private class Entry(
        val size: Long,
        val modified: Long,
        val scan: JarInJarHostScan,
    )

    private val entries =
        object : LinkedHashMap<String, Entry>(capacity, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > capacity
        }

    @Synchronized
    fun get(
        path: Path,
        size: Long,
        modified: Long,
    ): JarInJarHostScan? {
        val entry = entries[key(path)] ?: return null
        return entry.scan.takeIf { entry.size == size && entry.modified == modified }
    }

    @Synchronized
    fun put(
        path: Path,
        size: Long,
        modified: Long,
        scan: JarInJarHostScan,
    ) {
        entries[key(path)] = Entry(size, modified, scan)
    }

    /** 安装、更新或删除某个模组后主动失效，不依赖文件时间戳精度。 */
    @Synchronized
    fun invalidate(path: Path) {
        entries.remove(key(path))
    }

    /** 整包导入/修复会一次写入大量文件，直接清空最省事。 */
    @Synchronized
    fun clear() {
        entries.clear()
    }

    /** 列表刷新后丢掉不再存在的模组。 */
    @Synchronized
    fun prune(keep: Collection<Path>) {
        val keys = keep.map(::key).toSet()
        entries.keys.retainAll(keys)
    }

    /** 归一化时去掉末尾 `.disabled`，所以启用/禁用改名不会让缓存失效。 */
    private fun key(path: Path): String {
        val absolute = path.toAbsolutePath().normalize()
        var name = absolute.fileName?.toString().orEmpty()
        while (name.endsWith(DISABLED_SUFFIX, ignoreCase = true)) {
            name = name.dropLast(DISABLED_SUFFIX.length)
        }
        val parent = absolute.parent ?: return name
        return parent.resolve(name).toString()
    }
}
