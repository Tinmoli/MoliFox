package dev.molifox.core

import java.awt.image.BufferedImage
import kotlin.math.*

/** A small, CPU-rendered textured model; no GPU-specific runtime or external skin service. */
object SkinModelRenderer {
    private data class Point(val x: Float, val y: Float, val z: Float)

    private data class Projected(
        val x: Float,
        val y: Float,
        val z: Float,
        val u: Float,
        val v: Float,
    )

    private data class Region(val u: Int, val v: Int, val w: Int, val h: Int)

    private data class Box(
        val x: Float,
        val y: Float,
        val w: Int,
        val h: Int,
        val d: Int,
        val u: Int,
        val v: Int,
        val inflate: Float = 0f,
        val mirror: Boolean = false,
    )

    fun render(
        textureWidth: Int,
        textureHeight: Int,
        texture: IntArray,
        variant: SkinVariant,
        overlays: Boolean = true,
        yaw: Float = -25f,
        pitch: Float = 12f,
        headOnly: Boolean = false,
        width: Int = 240,
        height: Int = 320,
    ): BufferedImage {
        require(
            textureWidth in 64..4096 &&
                textureWidth % 64 == 0 &&
                (textureHeight == textureWidth || textureHeight * 2 == textureWidth) &&
                texture.size == textureWidth * textureHeight
        )
        require(width in 16..1024 && height in 16..1024 && yaw.isFinite() && pitch.isFinite())
        val modern = textureHeight == textureWidth
        val arm = if (modern && variant == SkinVariant.Slim) 3 else 4
        val boxes = mutableListOf(Box(0f, 28f, 8, 8, 8, 0, 0))
        if (!headOnly)
            boxes +=
                listOf(
                    Box(0f, 18f, 8, 12, 4, 16, 16),
                    Box(-4f - arm / 2f, 18f, arm, 12, 4, 40, 16),
                    if (modern) Box(4f + arm / 2f, 18f, arm, 12, 4, 32, 48)
                    else Box(6f, 18f, 4, 12, 4, 40, 16, mirror = true),
                    Box(-2f, 6f, 4, 12, 4, 0, 16),
                    if (modern) Box(2f, 6f, 4, 12, 4, 16, 48)
                    else Box(2f, 6f, 4, 12, 4, 0, 16, mirror = true),
                )
        if (overlays) {
            boxes += Box(0f, 28f, 8, 8, 8, 32, 0, .5f)
            if (modern && !headOnly)
                boxes +=
                    listOf(
                        Box(0f, 18f, 8, 12, 4, 16, 32, .25f),
                        Box(-4f - arm / 2f, 18f, arm, 12, 4, 40, 32, .25f),
                        Box(4f + arm / 2f, 18f, arm, 12, 4, 48, 48, .25f),
                        Box(-2f, 6f, 4, 12, 4, 0, 32, .25f),
                        Box(2f, 6f, 4, 12, 4, 0, 48, .25f),
                    )
        }
        val pixels = IntArray(width * height)
        val depth = FloatArray(pixels.size) { Float.NEGATIVE_INFINITY }
        val angle = Math.toRadians((yaw % 360f).toDouble())
        val tilt = Math.toRadians(pitch.coerceIn(-80f, 80f).toDouble())
        val cy = cos(angle).toFloat()
        val sy = sin(angle).toFloat()
        val cp = cos(tilt).toFloat()
        val sp = sin(tilt).toFloat()
        val pivot = if (headOnly) 28f else 16f
        val scale = if (headOnly) min(width, height) / 12f else min(width / 24f, height / 39f)
        fun rotate(p: Point): Point {
            val x = p.x * cy + p.z * sy
            val z = -p.x * sy + p.z * cy
            return Point(x, p.y * cp - z * sp, p.y * sp + z * cp)
        }
        fun face(
            points: List<Point>,
            region: Region,
            normal: Point,
            shade: Float,
            mirror: Boolean,
        ) {
            if (rotate(normal).z <= .0001f) return
            val uv = listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f)
            val vertices = points.mapIndexed { index, p ->
                val r = rotate(Point(p.x, p.y - pivot, p.z))
                val (u, v) = uv[index]
                Projected(
                    width / 2f + r.x * scale,
                    height / 2f - r.y * scale,
                    r.z,
                    region.u + (if (mirror) 1f - u else u) * region.w,
                    region.v + v * region.h,
                )
            }
            fun triangle(a: Projected, b: Projected, c: Projected) {
                val denominator = (b.y - c.y) * (a.x - c.x) + (c.x - b.x) * (a.y - c.y)
                if (abs(denominator) < .0001f) return
                val x0 = floor(minOf(a.x, b.x, c.x)).toInt().coerceIn(0, width - 1)
                val x1 = ceil(maxOf(a.x, b.x, c.x)).toInt().coerceIn(0, width - 1)
                val y0 = floor(minOf(a.y, b.y, c.y)).toInt().coerceIn(0, height - 1)
                val y1 = ceil(maxOf(a.y, b.y, c.y)).toInt().coerceIn(0, height - 1)
                for (y in y0..y1) for (x in x0..x1) {
                    val px = x + .5f
                    val py = y + .5f
                    val wa = ((b.y - c.y) * (px - c.x) + (c.x - b.x) * (py - c.y)) / denominator
                    val wb = ((c.y - a.y) * (px - c.x) + (a.x - c.x) * (py - c.y)) / denominator
                    val wc = 1f - wa - wb
                    if (wa < -.0001f || wb < -.0001f || wc < -.0001f) continue
                    val z = wa * a.z + wb * b.z + wc * c.z
                    val offset = y * width + x
                    if (z <= depth[offset] + .0001f) continue
                    val u =
                        (wa * a.u + wb * b.u + wc * c.u).coerceIn(
                            region.u.toFloat(),
                            region.u + region.w - .001f,
                        )
                    val v =
                        (wa * a.v + wb * b.v + wc * c.v).coerceIn(
                            region.v.toFloat(),
                            region.v + region.h - .001f,
                        )
                    val tx = (u * textureWidth / 64f).toInt().coerceIn(0, textureWidth - 1)
                    val ty = (v * textureWidth / 64f).toInt().coerceIn(0, textureHeight - 1)
                    val color = texture[ty * textureWidth + tx]
                    val alpha = color ushr 24
                    if (alpha == 0) continue
                    val backgroundAlpha = pixels[offset] ushr 24
                    val outAlpha = alpha + (backgroundAlpha * (255 - alpha)) / 255
                    fun channel(shift: Int): Int {
                        val foreground =
                            (((color ushr shift) and 255) * shade).toInt().coerceIn(0, 255)
                        val background = (pixels[offset] ushr shift) and 255
                        return (foreground * alpha * 255 +
                            background * backgroundAlpha * (255 - alpha)) / (outAlpha * 255)
                    }
                    pixels[offset] =
                        (outAlpha shl 24) or
                            (channel(16) shl 16) or
                            (channel(8) shl 8) or
                            channel(0)
                    depth[offset] = z
                }
            }
            triangle(vertices[0], vertices[1], vertices[2])
            triangle(vertices[0], vertices[2], vertices[3])
        }
        for (box in boxes) {
            val x0 = box.x - box.w / 2f - box.inflate
            val x1 = box.x + box.w / 2f + box.inflate
            val y0 = box.y - box.h / 2f - box.inflate
            val y1 = box.y + box.h / 2f + box.inflate
            val z0 = -box.d / 2f - box.inflate
            val z1 = box.d / 2f + box.inflate
            val u = box.u
            val v = box.v
            val w = box.w
            val h = box.h
            val d = box.d
            fun p(x: Float, y: Float, z: Float) = Point(x, y, z)
            face(
                listOf(p(x0, y1, z1), p(x1, y1, z1), p(x1, y0, z1), p(x0, y0, z1)),
                Region(u + d, v + d, w, h),
                p(0f, 0f, 1f),
                1f,
                box.mirror,
            )
            face(
                listOf(p(x1, y1, z0), p(x0, y1, z0), p(x0, y0, z0), p(x1, y0, z0)),
                Region(u + 2 * d + w, v + d, w, h),
                p(0f, 0f, -1f),
                .84f,
                box.mirror,
            )
            face(
                listOf(p(x0, y1, z0), p(x0, y1, z1), p(x0, y0, z1), p(x0, y0, z0)),
                Region(if (box.mirror) u + d + w else u, v + d, d, h),
                p(-1f, 0f, 0f),
                .78f,
                box.mirror,
            )
            face(
                listOf(p(x1, y1, z1), p(x1, y1, z0), p(x1, y0, z0), p(x1, y0, z1)),
                Region(if (box.mirror) u else u + d + w, v + d, d, h),
                p(1f, 0f, 0f),
                .9f,
                box.mirror,
            )
            face(
                listOf(p(x0, y1, z0), p(x1, y1, z0), p(x1, y1, z1), p(x0, y1, z1)),
                Region(u + d, v, w, d),
                p(0f, 1f, 0f),
                1f,
                box.mirror,
            )
            face(
                listOf(p(x0, y0, z1), p(x1, y0, z1), p(x1, y0, z0), p(x0, y0, z0)),
                Region(u + d + w, v, w, d),
                p(0f, -1f, 0f),
                .65f,
                box.mirror,
            )
        }
        return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
            setRGB(0, 0, width, height, pixels, 0, width)
        }
    }
}
