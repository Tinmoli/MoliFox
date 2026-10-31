// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证内嵌扫描的层级、节点/体积上限、不安全路径、取消与临时文件清理。

package dev.molifox.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JarInJarScannerTest {
    @TempDir lateinit var temporary: Path

    private val temporaryRoot: Path get() = temporary.resolve("staging")

    private fun scan(
        host: Path,
        limits: JarInJarLimits = JarInJarLimits(),
        preferred: JarInJarLoader = JarInJarLoader.Fabric,
        isCancelled: () -> Boolean = { false },
    ): JarInJarHostScan {
        val scanner = JarInJarScanner(temporaryRoot, limits)
        return scanner.scan(host, preferred, isCancelled, JarInJarScanBudget(limits))
    }

    private fun stagedFiles(): List<Path> {
        val root = temporaryRoot.resolve("jar-in-jar")
        if (!root.exists()) return emptyList()
        return Files.list(root).use { stream -> stream.toList() }
    }

    private fun hostWithEmbedded(bytes: ByteArray): Path =
        writeJar(
            temporary.resolve("host.jar"),
            mapOf(
                "fabric.mod.json" to fabricBytes("hosta", jars = listOf("inner.jar")),
                "inner.jar" to bytes,
            ),
        )

    @Test
    fun `nested jars are resolved up to the depth limit`() {
        val result = scan(hostWithEmbedded(layeredJar(5)))
        assertTrue(result.complete)
        assertEquals(5, countNodes(result.embedded))
        assertEquals(1, result.embedded.single().depth)
        assertTrue(
            result.embedded
                .single()
                .children
                .isNotEmpty(),
        )
        assertEquals(emptyList<Path>(), stagedFiles())
    }

    @Test
    fun `deeper nesting is cut off and marked incomplete`() {
        val result = scan(hostWithEmbedded(layeredJar(6)))
        assertFalse(result.complete)
        assertEquals(5, countNodes(result.embedded))
    }

    @Test
    fun `node limit stops the scan and marks incomplete`() {
        val result = scan(hostWithEmbedded(layeredJar(5)), JarInJarLimits(maxNodes = 2))
        assertFalse(result.complete)
        assertEquals(2, countNodes(result.embedded))
    }

    @Test
    fun `single entry byte budget rejects oversized embedded jars`() {
        val result =
            scan(
                hostWithEmbedded(layeredJar(2)),
                JarInJarLimits(maxEntryBytes = 64),
            )
        assertFalse(result.complete)
        assertTrue(result.embedded.isEmpty())
        assertEquals(emptyList<Path>(), stagedFiles())
    }

    @Test
    fun `declared but missing embedded jar marks the host incomplete`() {
        val host =
            writeJar(
                temporary.resolve("missing.jar"),
                mapOf("fabric.mod.json" to fabricBytes("hosta", jars = listOf("absent.jar"))),
            )
        val result = scan(host)
        assertFalse(result.complete)
        assertTrue(result.note.contains("不存在"))
        assertTrue(result.embedded.isEmpty())
    }

    @Test
    fun `unsafe declared path is rejected without touching the filesystem`() {
        val host =
            writeJar(
                temporary.resolve("unsafe.jar"),
                mapOf(
                    "fabric.mod.json" to fabricBytes("hosta", jars = listOf("../evil.jar")),
                    "../evil.jar" to layeredJar(1),
                ),
            )
        val result = scan(host)
        assertFalse(result.complete)
        assertTrue(result.embedded.isEmpty())
        assertEquals(emptyList<Path>(), stagedFiles())
    }

    @Test
    fun `cancelled scan stops and cleans temporary files`() {
        var calls = 0
        val result =
            scan(hostWithEmbedded(layeredJar(4)), isCancelled = { calls++ > 0 })
        assertFalse(result.complete)
        assertTrue(result.note.contains("取消"))
        assertTrue(result.embedded.isEmpty())
        assertEquals(emptyList<Path>(), stagedFiles())
    }

    @Test
    fun `invalid host archive is reported instead of thrown`() {
        val broken = temporary.resolve("broken.jar")
        broken.writeText("not a jar")
        val result = scan(broken)
        assertFalse(result.complete)
        assertTrue(result.note.isNotBlank())
    }

    @Test
    fun `archive entry count limit rejects bomb archives`() {
        val dense =
            jarBytes((0..40).associate { index -> "entry-$index.txt" to "x".toByteArray() })
        val result = scan(hostWithEmbedded(dense), JarInJarLimits(maxArchiveEntries = 16))
        assertFalse(result.complete)
        assertTrue(result.embedded.isEmpty())
        assertEquals(emptyList<Path>(), stagedFiles())
    }

    private fun countNodes(nodes: List<EmbeddedJar>): Int =
        nodes.sumOf { 1 + countNodes(it.children) }
}
