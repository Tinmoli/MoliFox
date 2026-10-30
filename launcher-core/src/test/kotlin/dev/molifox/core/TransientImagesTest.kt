// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验证模组图标与头像按需读取，不创建派生文件且保留图像校验。

package dev.molifox.core

import dev.molifox.core.data.skin.SkinTextureRepository
import dev.molifox.core.domain.skin.PlayerHeadReader
import dev.molifox.core.platform.files.readBoundedImage
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransientImagesTest {
    @TempDir lateinit var temporary: Path

    private fun texture(color: Int): ByteArray {
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, 64, 64, IntArray(64 * 64) { color }, 0, 64)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun mod(icon: ByteArray): Path {
        val file = temporary.resolve("fixture.jar")
        ZipOutputStream(Files.newOutputStream(file)).use { output ->
            for ((name, bytes) in mapOf(
                "fabric.mod.json" to
                    """{"icon":"icon.png"}""".toByteArray(),
                "icon.png" to icon,
            )) {
                output.putNextEntry(ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `mod icon is read from the archive without creating a separate file`() {
        val icon = texture(0xFFCA6489.toInt())
        val archive = mod(icon)
        assertContentEquals(icon, readModIcon(archive).getOrThrow())
        Files.list(temporary).use { assertEquals(listOf(archive), it.toList()) }
    }

    @Test
    fun `a replaced archive is read again instead of reusing a generated icon`() {
        val archive = mod(texture(0xFFCA6489.toInt()))
        val first = readModIcon(archive).getOrThrow()
        val replacement = texture(0xFF4567AB.toInt())
        mod(replacement)
        val second = readModIcon(archive).getOrThrow()
        assertFalse(first.contentEquals(second))
        assertContentEquals(replacement, second)
    }

    @Test
    fun `oversized entries are not loaded as mod icons`() {
        assertNull(readModIcon(mod(ByteArray(1024 * 1024 + 1))).getOrThrow())
    }

    @Test
    fun `archive errors remain visible and missing mods have no icon`() {
        val corrupt = Files.writeString(temporary.resolve("broken.jar"), "not a zip")
        assertTrue(readModIcon(corrupt).isFailure)
        assertNull(readModIcon(temporary.resolve("missing.jar")).getOrThrow())
    }

    @Test
    fun `head queries reread the original texture and do not write derived images`(): Unit =
        runBlocking {
            val path = temporary.resolve("skin.png")
            val original = texture(0xFFCA6489.toInt())
            Files.write(path, original)
            val reader = PlayerHeadReader()
            assertContentEquals(
                SkinModelRenderer.renderHeadFront(original, 64),
                reader.front(path, 64).getOrThrow(),
            )
            val replacement = texture(0xFF4567AB.toInt())
            Files.write(path, replacement)
            assertContentEquals(
                SkinModelRenderer.renderHeadFront(replacement, 64),
                reader.front(path, 64).getOrThrow(),
            )
            Files.list(temporary).use { assertEquals(listOf(path), it.toList()) }
        }

    @Test
    fun `head composition and mod icon reads leave an empty data directory empty`(): Unit =
        runBlocking {
            // 头像与模组图标都不允许再写派生文件：数据目录必须保持完全空（无 icons/heads、cache/mod-icons）。
            val dataDirectory = temporary.resolve("profile")
            Files.createDirectories(dataDirectory)
            val skin = temporary.resolve("source-skin.png")
            Files.write(skin, texture(0xFF6489CA.toInt()))
            val archive = mod(texture(0xFF89CA64.toInt()))
            PlayerHeadReader().front(skin, 32).getOrThrow()
            readModIcon(archive).getOrThrow()
            Files.walk(dataDirectory).use { entries ->
                // 目录本身之外没有任何生成文件（icons/heads、cache/mod-icons 都不出现）。
                assertEquals(listOf(dataDirectory), entries.toList())
            }
            assertFalse(Files.exists(dataDirectory.resolve("icons/heads")))
            assertFalse(Files.exists(dataDirectory.resolve("cache/mod-icons")))
        }

    @Test
    fun `bounded image reads propagate failures and reject oversized input`(): Unit =
        runBlocking {
            val file = Files.write(temporary.resolve("large.png"), byteArrayOf(1, 2, 3))
            assertTrue(readBoundedImage(file, 2).isFailure)
            val failure = java.io.IOException("fixture denied")
            val reader = PlayerHeadReader(SkinTextureRepository { _, _ -> Result.failure(failure) })
            assertEquals(failure, reader.front(file, 64).exceptionOrNull())
        }
}
