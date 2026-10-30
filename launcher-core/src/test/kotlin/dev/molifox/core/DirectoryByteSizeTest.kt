// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验证目录统计每次读取当前文件，并允许任务中断遍历。

package dev.molifox.core

import dev.molifox.core.platform.files.directoryByteSize
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DirectoryByteSizeTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `each query reflects changed files without reusing an earlier total`() {
        val file = Files.write(temporary.resolve("fixture.bin"), byteArrayOf(1))
        assertEquals(1L, directoryByteSize(temporary) {}.getOrThrow())
        Files.write(file, byteArrayOf(1, 2, 3))
        assertEquals(3L, directoryByteSize(temporary) {}.getOrThrow())
        Files.delete(file)
        assertEquals(0L, directoryByteSize(temporary) {}.getOrThrow())
        assertNull(directoryByteSize(temporary.resolve("missing")) {}.getOrThrow())
    }

    @Test
    fun `cancellation stops traversal instead of being returned as an unreadable size`() {
        assertFailsWith<CancellationException> {
            directoryByteSize(temporary) { throw CancellationException("Fixture cancelled") }
        }
    }
}
