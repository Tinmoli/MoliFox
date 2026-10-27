package dev.molifox.core

import kotlin.test.*
import org.junit.jupiter.api.Test

class SkinModelRendererTest {
    private fun fill(pixels: IntArray, x: Int, y: Int, w: Int, h: Int, color: Int) {
        for (row in y until y + h) for (column in x until x + w) pixels[row * 64 + column] = color
    }

    @Test
    fun `orbit shows actual front back and side texture regions`() {
        val skin = IntArray(64 * 64)
        fill(skin, 8, 8, 8, 8, 0xffff0000.toInt())
        fill(skin, 24, 8, 8, 8, 0xff00ff00.toInt())
        fill(skin, 0, 8, 8, 8, 0xff0000ff.toInt())
        fun face(yaw: Float) =
            SkinModelRenderer.render(
                    64,
                    64,
                    skin,
                    SkinVariant.Classic,
                    false,
                    yaw,
                    0f,
                    true,
                    96,
                    96,
                )
                .getRGB(48, 48)
        assertEquals(0xff0000, face(0f) and 0xffffff)
        assertTrue((face(180f) ushr 8 and 255) > 180)
        assertTrue((face(90f) and 255) > 180)
        assertEquals(0, face(90f) ushr 16 and 255)
    }

    @Test
    fun `outer layer blends once without a diagonal seam and can be disabled`() {
        val skin = IntArray(64 * 64)
        fill(skin, 8, 8, 8, 8, 0xffff0000.toInt())
        fill(skin, 40, 8, 8, 8, 0x800000ff.toInt())
        val plain =
            SkinModelRenderer.render(64, 64, skin, SkinVariant.Classic, false, 0f, 0f, true, 96, 96)
        val overlay =
            SkinModelRenderer.render(64, 64, skin, SkinVariant.Classic, true, 0f, 0f, true, 96, 96)
        assertEquals(0xff0000, plain.getRGB(48, 48) and 0xffffff)
        val pixel = overlay.getRGB(48, 48)
        assertEquals(255, pixel ushr 24)
        assertTrue((pixel ushr 16 and 255) in 120..136)
        assertTrue((pixel and 255) in 120..136)
    }

    @Test
    fun `slim arms change the silhouette and rendering never mutates the skin`() {
        val skin = IntArray(64 * 64) { 0xff5599aa.toInt() }
        val original = skin.copyOf()
        fun size(variant: SkinVariant): Int {
            val frame = SkinModelRenderer.render(64, 64, skin, variant, false, 0f, 0f)
            return frame.getRGB(0, 0, frame.width, frame.height, null, 0, frame.width).count {
                it ushr 24 > 0
            }
        }
        assertTrue(size(SkinVariant.Classic) > size(SkinVariant.Slim))
        assertContentEquals(original, skin)
    }

    @Test
    fun `legacy skins have mirrored limbs and do not use nonexistent modern UV regions`() {
        val skin = IntArray(64 * 32) { 0xffaa7755.toInt() }
        val classic = SkinModelRenderer.render(64, 32, skin, SkinVariant.Classic, false, 40f, 15f)
        val slim = SkinModelRenderer.render(64, 32, skin, SkinVariant.Slim, false, 40f, 15f)
        assertContentEquals(
            classic.getRGB(0, 0, 240, 320, null, 0, 240),
            slim.getRGB(0, 0, 240, 320, null, 0, 240),
        )
        assertTrue(classic.getRGB(120, 160) ushr 24 > 0)
    }

    @Test
    fun `invalid dimensions and nonfinite angles are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            SkinModelRenderer.render(64, 48, IntArray(64 * 48), SkinVariant.Classic)
        }
        assertFailsWith<IllegalArgumentException> {
            SkinModelRenderer.render(
                64,
                64,
                IntArray(64 * 64),
                SkinVariant.Classic,
                yaw = Float.NaN,
            )
        }
    }
}
