// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责皮肤头部和完整模型的三维软件渲染。

package dev.molifox.core

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A small, CPU-rendered textured model; no GPU-specific runtime or external skin service. */
object SkinModelRenderer {
    private data class Point(
        val x: Float,
        val y: Float,
        val z: Float,
    )

    // 64 像素纹理坐标下的头部正面与帽子/外层正面区域；高清皮肤按 width / 64 等比放大。
    private const val HEAD_REGION_U = 8
    private const val HEAD_REGION_V = 8
    private const val HAT_REGION_U = 40
    private const val HAT_REGION_V = 8
    private const val HEAD_TILE = 8

    private data class Projected(
        val x: Float,
        val y: Float,
        val z: Float,
        val u: Float,
        val v: Float,
    )

    private data class Region(
        val u: Int,
        val v: Int,
        val w: Int,
        val h: Int,
    )

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
                texture.size == textureWidth * textureHeight,
        )
        require(width in 16..1024 && height in 16..1024 && yaw.isFinite() && pitch.isFinite())
        val modern = textureHeight == textureWidth
        val arm = if (modern && variant == SkinVariant.Slim) 3 else 4
        val boxes = mutableListOf(Box(0f, 28f, 8, 8, 8, 0, 0))
        if (!headOnly) {
            boxes +=
                listOf(
                    Box(0f, 18f, 8, 12, 4, 16, 16),
                    Box(-4f - arm / 2f, 18f, arm, 12, 4, 40, 16),
                    if (modern) {
                        Box(4f + arm / 2f, 18f, arm, 12, 4, 32, 48)
                    } else {
                        Box(6f, 18f, 4, 12, 4, 40, 16, mirror = true)
                    },
                    Box(-2f, 6f, 4, 12, 4, 0, 16),
                    if (modern) {
                        Box(2f, 6f, 4, 12, 4, 16, 48)
                    } else {
                        Box(2f, 6f, 4, 12, 4, 0, 16, mirror = true)
                    },
                )
        }
        if (overlays) {
            boxes += Box(0f, 28f, 8, 8, 8, 32, 0, .5f)
            if (modern && !headOnly) {
                boxes +=
                    listOf(
                        Box(0f, 18f, 8, 12, 4, 16, 32, .25f),
                        Box(-4f - arm / 2f, 18f, arm, 12, 4, 40, 32, .25f),
                        Box(4f + arm / 2f, 18f, arm, 12, 4, 48, 48, .25f),
                        Box(-2f, 6f, 4, 12, 4, 0, 32, .25f),
                        Box(2f, 6f, 4, 12, 4, 0, 48, .25f),
                    )
            }
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
            val vertices =
                points.mapIndexed { index, p ->
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

            fun triangle(
                a: Projected,
                b: Projected,
                c: Projected,
            ) {
                val denominator = (b.y - c.y) * (a.x - c.x) + (c.x - b.x) * (a.y - c.y)
                if (abs(denominator) < .0001f) return
                val x0 = floor(minOf(a.x, b.x, c.x)).toInt().coerceIn(0, width - 1)
                val x1 = ceil(maxOf(a.x, b.x, c.x)).toInt().coerceIn(0, width - 1)
                val y0 = floor(minOf(a.y, b.y, c.y)).toInt().coerceIn(0, height - 1)
                val y1 = ceil(maxOf(a.y, b.y, c.y)).toInt().coerceIn(0, height - 1)
                for (y in y0..y1) {
                    for (x in x0..x1) {
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
                            return (
                                foreground * alpha * 255 +
                                    background * backgroundAlpha * (255 - alpha)
                            ) / (outAlpha * 255)
                        }
                        pixels[offset] =
                            (outAlpha shl 24) or
                            (channel(16) shl 16) or
                            (channel(8) shl 8) or
                            channel(0)
                        depth[offset] = z
                    }
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

            fun p(
                x: Float,
                y: Float,
                z: Float,
            ) = Point(x, y, z)
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

    /**
     * 静态正面头像：把皮肤贴图的头部正面 (8,8)-(16,16) 与帽子/外层正面 (40,8)-(48,16) 合成为一张方图。 先画脸部，再按帽子层自身的 alpha
     * 叠加，所以透明帽子区域保持脸部原样、脸外区域也保持透明（不填黑底）。 旧版 64×32 皮肤同样使用这两个坐标，所有读取都在 y < 32 之内；最后按最近邻放大到
     * [size]×[size]。 贴图无法解析或尺寸不受支持时返回 null，由界面回退到占位头像，绝不抛异常。
     */
    fun renderHeadFront(
        skin: ByteArray,
        size: Int = 64,
    ): ByteArray? =
        runCatching {
            require(skin.isNotEmpty()) { "皮肤数据为空" }
            require(size in HEAD_TILE..1024) { "头像尺寸无效" }
            val image = ImageIO.read(ByteArrayInputStream(skin)) ?: error("皮肤不是可解析的图片")
            val width = image.width
            val height = image.height
            // 只接受 64 的整数倍宽度：64×64（含高清）或旧版 64×32；其它布局不猜位置。
            require(width in 64..4096 && width % 64 == 0) { "皮肤宽度无效" }
            require(height == width || height * 2 == width) { "皮肤高度无效" }
            val scale = width / 64

            fun faceAt(
                x: Int,
                y: Int,
            ): Int = pixelAt(image, (HEAD_REGION_U + x) * scale, (HEAD_REGION_V + y) * scale)

            fun hatAt(
                x: Int,
                y: Int,
            ): Int = pixelAt(image, (HAT_REGION_U + x) * scale, (HAT_REGION_V + y) * scale)
            // 正面直视的“立体皮肤层”：脸缩到 8/9、帽子层保持满幅，
            // 帽子比脸大出一圈，看起来像贴在脸前面的凸起层；再在帽子下缘压一道
            // 淡淡的投影，强化“浮起来”的层次。整图只做最近邻放大。
            val faceSide = (size * 8 + 4) / 9
            val faceOffset = (size - faceSide) / 2
            val shadeShift = max(1, size / 48)

            fun faceSample(
                x: Int,
                y: Int,
            ): Int =
                faceAt(
                    ((x - faceOffset) * HEAD_TILE / faceSide).coerceIn(0, HEAD_TILE - 1),
                    ((y - faceOffset) * HEAD_TILE / faceSide).coerceIn(0, HEAD_TILE - 1),
                )

            fun hatSample(
                x: Int,
                y: Int,
            ): Int =
                hatAt(
                    (x * HEAD_TILE / size).coerceIn(0, HEAD_TILE - 1),
                    (y * HEAD_TILE / size).coerceIn(0, HEAD_TILE - 1),
                )
            val pixels =
                IntArray(size * size) { index ->
                    val x = index % size
                    val y = index / size
                    val inFace =
                        x in faceOffset until faceOffset + faceSide &&
                            y in faceOffset until faceOffset + faceSide
                    val base = if (inFace) faceSample(x, y) else 0
                    // 帽子层在自己的下缘投下的半透明阴影
                    val shadowSource =
                        if (y - shadeShift in 0 until size) hatSample(x, y - shadeShift) else 0
                    val shadowAlpha = (shadowSource ushr 24) * 28 / 100
                    val shaded =
                        if (shadowAlpha > 0 && inFace) {
                            overlay((shadowAlpha shl 24) or 0x1A1014, base)
                        } else {
                            base
                        }
                    overlay(hatSample(x, y), shaded)
                }
            val output = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            output.setRGB(0, 0, size, size, pixels, 0, size)
            ByteArrayOutputStream().also { ImageIO.write(output, "png", it) }.toByteArray()
        }.getOrNull()

    /** 立体头像：复用 [render] 只渲染头部，正对镜头（水平不偏转，仅略微俯视露出顶面）， 输出 PNG 字节；皮肤不可解析时返回 null。 */
    fun renderHeadModel(
        skin: ByteArray,
        size: Int = 128,
        yaw: Float = 0f,
        pitch: Float = -20f,
    ): ByteArray? =
        runCatching {
            val image = ImageIO.read(ByteArrayInputStream(skin)) ?: error("皮肤不是可解析的图片")
            val width = image.width
            val height = image.height
            require(width in 64..4096 && width % 64 == 0) { "皮肤宽度无效" }
            require(height == width || height * 2 == width) { "皮肤高度无效" }
            val pixels =
                IntArray(width * height).also { image.getRGB(0, 0, width, height, it, 0, width) }
            val rendered =
                render(
                    width,
                    height,
                    pixels,
                    SkinVariant.Classic,
                    overlays = true,
                    yaw = yaw,
                    pitch = pitch,
                    headOnly = true,
                    width = size,
                    height = size,
                )
            ByteArrayOutputStream().also { ImageIO.write(rendered, "png", it) }.toByteArray()
        }.getOrNull()

    private fun pixelAt(
        image: BufferedImage,
        x: Int,
        y: Int,
    ): Int = if (x in 0 until image.width && y in 0 until image.height) image.getRGB(x, y) else 0

    /** 源覆盖混合：把帽子层按自身 alpha 叠到脸部之上；帽子全透明时保留脸部（含透明度）。 */
    private fun overlay(
        source: Int,
        destination: Int,
    ): Int {
        val sourceAlpha = source ushr 24
        if (sourceAlpha == 0) return destination
        if (sourceAlpha == 255) return source
        val destinationAlpha = destination ushr 24
        val alpha = sourceAlpha + destinationAlpha * (255 - sourceAlpha) / 255
        if (alpha == 0) return 0

        fun channel(shift: Int): Int {
            val foreground = ((source ushr shift) and 255) * sourceAlpha
            val background = ((destination ushr shift) and 255) * destinationAlpha
            return (foreground + background * (255 - sourceAlpha) / 255) / alpha
        }
        return (alpha shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
