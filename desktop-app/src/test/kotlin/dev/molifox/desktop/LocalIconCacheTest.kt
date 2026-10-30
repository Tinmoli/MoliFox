// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验证内容图标内存缓存有 256 项上限，不会随浏览持续增长。

package dev.molifox.desktop

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LocalIconCacheTest {
    @TempDir lateinit var temporary: Path

    /** 每个条目是一个带 `icon.png` 的世界目录，保证缓存里保存的是真正解码出的缩略图。 */
    private fun thumbnail(index: Int): Path {
        val folder = temporary.resolve("thumb-$index").createDirectories()
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, 0xFFCA6489.toInt())
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        folder.resolve("icon.png").writeBytes(bytes)
        return folder
    }

    @Test
    fun `the 257th thumbnail evicts instead of growing the cache`() {
        val thumbnails = (0 until 257).map(::thumbnail)
        thumbnails.forEach {
            assertNotNull(LocalIconCache.load(it, world = true, shader = false))
        }
        assertEquals(256, LocalIconCache.entryCount())
        // 最早的一张被挤出，最近的一张仍在，说明是 256 项上限而不是无界增长。
        assertEquals(null, LocalIconCache.peek(thumbnails.first(), world = true, shader = false))
        assertNotNull(LocalIconCache.peek(thumbnails.last(), world = true, shader = false))
    }
}
