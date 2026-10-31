// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证扫描缓存的大小/时间指纹、启用禁用改名、失效与 LRU 容量。

package dev.molifox.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JarInJarCacheTest {
    @TempDir lateinit var temporary: Path

    private fun scan(): JarInJarHostScan =
        JarInJarHostScan(
            declaration = JarDeclaration(JarInJarLoader.Fabric),
            embedded = emptyList(),
            complete = true,
            note = "",
        )

    @Test
    fun `fingerprint mismatch drops the cached entry`() {
        val cache = JarInJarCache()
        val path = temporary.resolve("a.jar")
        cache.put(path, 128, 1_000, scan())
        assertNotNull(cache.get(path, 128, 1_000))
        assertNull(cache.get(path, 129, 1_000))
        assertNull(cache.get(path, 128, 2_000))
    }

    @Test
    fun `disabled suffix shares the cache key`() {
        val cache = JarInJarCache()
        cache.put(temporary.resolve("a.jar.disabled"), 64, 10, scan())
        assertNotNull(cache.get(temporary.resolve("a.jar"), 64, 10))
        assertNotNull(cache.get(temporary.resolve("a.jar.disabled"), 64, 10))
    }

    @Test
    fun `invalidate clear and prune drop entries`() {
        val cache = JarInJarCache()
        val first = temporary.resolve("a.jar")
        val second = temporary.resolve("b.jar")
        cache.put(first, 1, 1, scan())
        cache.put(second, 1, 1, scan())
        cache.invalidate(first)
        assertNull(cache.get(first, 1, 1))
        assertNotNull(cache.get(second, 1, 1))
        cache.prune(listOf(first))
        assertNull(cache.get(second, 1, 1))
        cache.put(first, 1, 1, scan())
        cache.clear()
        assertNull(cache.get(first, 1, 1))
    }

    @Test
    fun `capacity evicts the least recently used entry`() {
        val cache = JarInJarCache(capacity = 1)
        val first = temporary.resolve("a.jar")
        val second = temporary.resolve("b.jar")
        cache.put(first, 1, 1, scan())
        cache.put(second, 1, 1, scan())
        assertNull(cache.get(first, 1, 1))
        assertNotNull(cache.get(second, 1, 1))
    }
}
