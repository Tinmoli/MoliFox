// 校验安装包解压：只写出普通文件，并拒绝目录穿越、链接与空包。
package dev.molifox.multiplayer

import dev.molifox.multiplayer.data.TarGzExtractor
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TarGzExtractorTest {
    @Test
    fun `extracts regular files and preserves content`() {
        val work = Files.createTempDirectory("tar-test-")
        try {
            val archive = work.resolve("pkg.tar.gz")
            Files.write(
                archive,
                TarTestSupport.archive(
                    listOf(
                        TarEntry("core.bin", "hello core".toByteArray()),
                        TarEntry("second.bin", ByteArray(700) { it.toByte() }),
                    ),
                ),
            )
            val output = work.resolve("out")
            TarGzExtractor.extract(archive, output)
            assertEquals("hello core", Files.readString(output.resolve("core.bin")))
            assertEquals(700, Files.size(output.resolve("second.bin")))
        } finally {
            work.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects path traversal names`() {
        val work = Files.createTempDirectory("tar-test-")
        try {
            val archive = work.resolve("pkg.tar.gz")
            Files.write(
                archive,
                TarTestSupport.archive(listOf(TarEntry("../escape.bin", "nope".toByteArray()))),
            )
            assertFailsWith<IllegalArgumentException> {
                TarGzExtractor.extract(archive, work.resolve("out"))
            }
        } finally {
            work.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects links and unknown entry types`() {
        val work = Files.createTempDirectory("tar-test-")
        try {
            val archive = work.resolve("pkg.tar.gz")
            Files.write(
                archive,
                TarTestSupport.archive(listOf(TarEntry("link.bin", ByteArray(0), type = '2'))),
            )
            assertFailsWith<IllegalStateException> {
                TarGzExtractor.extract(archive, work.resolve("out"))
            }
        } finally {
            work.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects empty archives`() {
        val work = Files.createTempDirectory("tar-test-")
        try {
            val archive = work.resolve("pkg.tar.gz")
            Files.write(archive, TarTestSupport.archive(emptyList()))
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    TarGzExtractor.extract(archive, work.resolve("out"))
                }
            assertTrue(failure.message.orEmpty().contains("为空"))
        } finally {
            work.toFile().deleteRecursively()
        }
    }
}
