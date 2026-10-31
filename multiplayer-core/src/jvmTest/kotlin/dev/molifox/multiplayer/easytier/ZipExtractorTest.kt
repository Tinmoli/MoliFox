// 校验 zip 解包：剥离根前缀、拒绝穿越/重复/超限条目，失败不留下越界文件。
package dev.molifox.multiplayer.easytier

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZipExtractorTest {
    @Test
    fun `extracts files under root prefix and strips it`() =
        runBlocking {
            withWork { work ->
                val archive = work.resolve("pkg.zip")
                Files.write(
                    archive,
                    ZipTestSupport.zip(
                        listOf(
                            ZipTestEntry(
                                "easytier-linux-x86_64/easytier-core",
                                "core".toByteArray(),
                            ),
                            ZipTestEntry("easytier-linux-x86_64/easytier-cli", "cli".toByteArray()),
                            ZipTestEntry("easytier-linux-x86_64/doc/notes.txt", "hi".toByteArray()),
                        ),
                    ),
                )
                val output = work.resolve("out")
                val result =
                    ZipExtractor.extract(archive, output, "easytier-linux-x86_64")
                assertEquals(3, result.files)
                assertEquals(9L, result.bytes)
                assertEquals("core", Files.readString(output.resolve("easytier-core")))
                assertEquals("hi", Files.readString(output.resolve("doc/notes.txt")))
                assertFalse(Files.exists(output.resolve("easytier-linux-x86_64")))
            }
        }

    @Test
    fun `ignores entries outside the root prefix`() =
        runBlocking {
            withWork { work ->
                val archive = work.resolve("pkg.zip")
                Files.write(
                    archive,
                    ZipTestSupport.zip(
                        listOf(
                            ZipTestEntry("README.md", "readme".toByteArray()),
                            ZipTestEntry("pkg/core.bin", "core".toByteArray()),
                        ),
                    ),
                )
                val output = work.resolve("out")
                val result = ZipExtractor.extract(archive, output, "pkg")
                assertEquals(1, result.files)
                assertEquals("core", Files.readString(output.resolve("core.bin")))
                assertFalse(Files.exists(output.resolve("README.md")))
            }
        }

    @Test
    fun `extracts archives without a prefix`() =
        runBlocking {
            withWork { work ->
                val archive = work.resolve("pkg.zip")
                Files.write(
                    archive,
                    ZipTestSupport.zip(listOf(ZipTestEntry("bin/core", "x".toByteArray()))),
                )
                val output = work.resolve("out")
                val result = ZipExtractor.extract(archive, output)
                assertEquals(1, result.files)
                assertEquals("x", Files.readString(output.resolve("bin/core")))
            }
        }

    @Test
    fun `rejects path traversal`() =
        runBlocking {
            withWork { work ->
                val cases = listOf("../escape.bin", "pkg/../../escape.bin", "a\\..\\escape.bin")
                cases.forEach { name ->
                    val archive = work.resolve("pkg.zip")
                    Files.write(
                        archive,
                        ZipTestSupport.zip(listOf(ZipTestEntry(name, ByteArray(1)))),
                    )
                    val failure =
                        assertFailsWith<IllegalArgumentException> {
                            ZipExtractor.extract(archive, work.resolve("out"), "pkg")
                        }
                    assertTrue(failure.message.orEmpty().contains("穿越"), "未拒绝：$name")
                    assertFalse(Files.exists(work.resolve("escape.bin")))
                }
            }
        }

    @Test
    fun `rejects absolute paths`() =
        runBlocking {
            withWork { work ->
                val cases =
                    listOf("/etc/passwd", "C:/Windows/system32/x.dll", "C:\\temp\\x.dll")
                cases.forEach { name ->
                    val archive = work.resolve("pkg.zip")
                    Files.write(
                        archive,
                        ZipTestSupport.zip(listOf(ZipTestEntry(name, ByteArray(1)))),
                    )
                    val failure =
                        assertFailsWith<IllegalArgumentException> {
                            ZipExtractor.extract(archive, work.resolve("out"), "pkg")
                        }
                    assertTrue(failure.message.orEmpty().contains("绝对路径"), "未拒绝：$name")
                }
            }
        }

    @Test
    fun `rejects duplicate entries`() =
        runBlocking {
            withWork { work ->
                val archive = work.resolve("pkg.zip")
                Files.write(
                    archive,
                    ZipTestSupport.zip(
                        listOf(
                            ZipTestEntry("pkg/core.bin", "a".toByteArray()),
                            ZipTestEntry("pkg/core.bin", "b".toByteArray()),
                        ),
                    ),
                )
                val failure =
                    assertFailsWith<IllegalArgumentException> {
                        ZipExtractor.extract(archive, work.resolve("out"), "pkg")
                    }
                assertTrue(failure.message.orEmpty().contains("重复条目"))
            }
        }

    @Test
    fun `enforces entry count limit`() =
        runBlocking {
            withWork { work ->
                val archive = work.resolve("pkg.zip")
                Files.write(
                    archive,
                    ZipTestSupport.zip(
                        listOf(
                            ZipTestEntry("pkg/a.bin", ByteArray(1)),
                            ZipTestEntry("pkg/b.bin", ByteArray(1)),
                        ),
                    ),
                )
                val failure =
                    assertFailsWith<IllegalArgumentException> {
                        ZipExtractor.extract(
                            archive,
                            work.resolve("out"),
                            "pkg",
                            ZipLimits(maxEntries = 1),
                        )
                    }
                assertTrue(failure.message.orEmpty().contains("条目过多"))
            }
        }

    @Test
    fun `enforces per-file size limit`() =
        runBlocking {
            withWork { work ->
                val archive = work.resolve("pkg.zip")
                Files.write(
                    archive,
                    ZipTestSupport.zip(listOf(ZipTestEntry("pkg/big.bin", ByteArray(8)))),
                )
                val failure =
                    assertFailsWith<IllegalArgumentException> {
                        ZipExtractor.extract(
                            archive,
                            work.resolve("out"),
                            "pkg",
                            ZipLimits(maxFileBytes = 4),
                        )
                    }
                assertTrue(failure.message.orEmpty().contains("过大"))
            }
        }

    @Test
    fun `rejects archives without usable entries`() =
        runBlocking {
            withWork { work ->
                val archive = work.resolve("pkg.zip")
                Files.write(archive, ZipTestSupport.zip(emptyList()))
                val failure =
                    assertFailsWith<IllegalArgumentException> {
                        ZipExtractor.extract(archive, work.resolve("out"), "pkg")
                    }
                assertTrue(failure.message.orEmpty().contains("为空"))
            }
        }

    private suspend fun withWork(block: suspend (Path) -> Unit) {
        val work = Files.createTempDirectory("easytier-zip-")
        try {
            block(work)
        } finally {
            work.toFile().deleteRecursively()
        }
    }
}
