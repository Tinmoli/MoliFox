// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证NBT 二进制标签的安全读取的行为与边界。

package dev.molifox.core

import kotlin.io.path.*
import kotlin.test.*
import org.junit.jupiter.api.Test

class NbtTest {
    /** 一个覆盖 1–12 号标签的合成复合标签，供压缩与未压缩两种读取路径复用。 */
    private fun sampleBytes(): ByteArray =
        TestNbtWriter().root {
            byte("byte", 7)
            short("short", 300)
            int("int", -42)
            long("long", 9_000_000_000L)
            float("float", 1.5f)
            double("double", 2.25)
            string("name", "MoliFox 服务器")
            byteArray("bytes", byteArrayOf(1, 2, 3))
            intArray("ints", intArrayOf(-1, 0, 1))
            longArray("longs", longArrayOf(1L, -2L))
            nested("nested") { string("value", "深一层") }
            list("list", 10, 2) {
                compoundElement { int("id", 1) }
                compoundElement { int("id", 2) }
            }
            list("empty", 0, 0) {}
        }

    @Test
    fun `gzipped compound round trip keeps every supported tag kind`() =
        withTempDirectory("molifox-nbt-") { directory ->
            val file = directory.resolve("level.dat")
            file.writeBytes(gzipBytes(sampleBytes()))

            val root = assertNotNull(readCompound(file))
            assertEquals(7, (root["byte"] as NbtByte).value.toInt())
            assertEquals(300.toShort(), (root["short"] as NbtShort).value)
            assertEquals(-42, (root["int"] as NbtInt).value)
            assertEquals(9_000_000_000L, (root["long"] as NbtLong).value)
            assertEquals(1.5f, (root["float"] as NbtFloat).value)
            assertEquals(2.25, (root["double"] as NbtDouble).value)
            assertEquals("MoliFox 服务器", root.string("name"))
            assertContentEquals(byteArrayOf(1, 2, 3), (root["bytes"] as NbtByteArray).value)
            assertContentEquals(intArrayOf(-1, 0, 1), (root["ints"] as NbtIntArray).value)
            assertContentEquals(longArrayOf(1L, -2L), (root["longs"] as NbtLongArray).value)
            assertEquals("深一层", root.compound("nested")?.string("value"))

            val list = assertNotNull(root.list("list"))
            assertEquals(2, list.elements.size)
            assertEquals(2, list.compounds().size)
            assertEquals(1, (list.compounds()[0]["id"] as NbtInt).value)
            assertEquals(2, (list.compounds()[1]["id"] as NbtInt).value)

            val empty = assertNotNull(root.list("empty"))
            assertTrue(empty.elements.isEmpty())
            assertEquals(0, empty.elementType)
        }

    @Test
    fun `uncompressed compound is accepted too`() =
        withTempDirectory("molifox-nbt-") { directory ->
            val file = directory.resolve("servers.dat")
            file.writeBytes(sampleBytes())

            val root = assertNotNull(readCompound(file))
            assertEquals("MoliFox 服务器", root.string("name"))
            assertEquals(2, root.list("list")?.elements?.size)
        }

    @Test
    fun `malformed or missing input returns null without throwing`() =
        withTempDirectory("molifox-nbt-") { directory ->
            assertNull(readCompound(directory.resolve("missing.dat")))

            val truncated = directory.resolve("truncated.dat")
            truncated.writeBytes(byteArrayOf(10, 0, 0))
            assertNull(readCompound(truncated))

            val wrongRoot = directory.resolve("wrong-root.dat")
            wrongRoot.writeBytes(byteArrayOf(9, 0, 0, 0, 0, 0))
            assertNull(readCompound(wrongRoot))

            val text = directory.resolve("text.dat")
            text.writeText("这不是 NBT")
            assertNull(readCompound(text))
        }

    @Test
    fun `decompressed payload over eight megabytes is rejected`() =
        withTempDirectory("molifox-nbt-") { directory ->
            val file = directory.resolve("oversized.dat")
            file.writeBytes(gzipBytes(ByteArray(8 * 1024 * 1024 + 1)))
            assertNull(readCompound(file))
        }
}
