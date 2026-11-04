// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责皮肤头部和完整模型的三维软件渲染。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
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

// 贴图与画布的合法区间，以及渲染参数的默认值。
private const val SKIN_TEXTURE_MIN_SIZE = 64
private const val SKIN_TEXTURE_MAX_SIZE = 4096
private const val SKIN_TEXTURE_TILE = 64
private const val MIN_RENDER_SIZE = 16
private const val MAX_RENDER_SIZE = 1024
private const val DEFAULT_YAW_DEGREES = -25f
private const val DEFAULT_PITCH_DEGREES = 12f
private const val DEFAULT_RENDER_WIDTH = 240
private const val DEFAULT_RENDER_HEIGHT = 320
private const val DEFAULT_HEAD_FRONT_SIZE = 64
private const val DEFAULT_HEAD_MODEL_SIZE = 128
private const val MAX_HEAD_FRONT_SIZE = 1024
private const val DEFAULT_HEAD_MODEL_PITCH = -20f

// 64 像素纹理坐标下的部件尺寸与高度（y 轴向上，躯干中心为原点）。
private const val HEAD_SIZE = 8
private const val HEAD_Y = 28f
private const val BODY_W = 8
private const val BODY_H = 12
private const val BODY_D = 4
private const val BODY_Y = 18f
private const val LIMB_W = 4
private const val LIMB_H = 12
private const val LIMB_D = 4
private const val SLIM_ARM_W = 3
private const val ARM_Y = 18f
private const val LEG_Y = 6f
private const val ARM_X_OFFSET = 4f
private const val LEGACY_ARM_X = 6f
private const val LEFT_LEG_X = -2f
private const val RIGHT_LEG_X = 2f

// 各部件在贴图中的起始坐标，U 为横坐标、V 为纵坐标；OV 表示外层（帽子/护甲）区域。
private const val BODY_U = 16
private const val BODY_V = 16
private const val ARM_U = 40
private const val ARM_V = 16
private const val R_ARM_U = 32
private const val R_ARM_V = 48
private const val LEG_V = 16
private const val R_LEG_U = 16
private const val R_LEG_V = 48
private const val HAT_U = 32
private const val BODY_OV_V = 32
private const val ARM_OV_V = 32
private const val R_ARM_OV_U = 48
private const val R_ARM_OV_V = 48
private const val LEG_OV_V = 32
private const val R_LEG_OV_V = 48
private const val HAT_INFLATE = 0.5f
private const val OVERLAY_INFLATE = 0.25f

// 相机、投影与光照参数。
private const val FULL_TURN_DEGREES = 360f
private const val MAX_PITCH_DEGREES = 80f
private const val HEAD_ONLY_PIVOT = 28f
private const val BODY_PIVOT = 16f
private const val HEAD_ONLY_SCALE_DIVISOR = 12f
private const val MODEL_SCALE_WIDTH_DIVISOR = 24f
private const val MODEL_SCALE_HEIGHT_DIVISOR = 39f
private const val BACK_FACE_SHADE = 0.84f
private const val LEFT_FACE_SHADE = 0.78f
private const val RIGHT_FACE_SHADE = 0.9f
private const val BOTTOM_FACE_SHADE = 0.65f

// 光栅化容差、像素采样与颜色通道位移。
private const val BACKFACE_MIN_DEPTH = 0.0001f
private const val DEGENERATE_TRIANGLE_EPSILON = 0.0001f
private const val TRIANGLE_EDGE_EPSILON = 0.0001f
private const val DEPTH_BUFFER_BIAS = 0.0001f
private const val PIXEL_CENTER_OFFSET = 0.5f
private const val REGION_UV_INSET = 0.001f
private const val TEXTURE_ATLAS_TILE = 64f
private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val CHANNEL_MAX = 255
private const val OPAQUE_ALPHA = 255
private const val QUAD_LAST_VERTEX_INDEX = 3

/** 皮肤宽度必须是 64 的整数倍且在支持范围内，高度另按 64×64 或旧版 64×32 校验。 */
private fun hasSupportedSkinWidth(width: Int): Boolean =
    width in SKIN_TEXTURE_MIN_SIZE..SKIN_TEXTURE_MAX_SIZE && width % SKIN_TEXTURE_TILE == 0

private data class Point(
    val x: Float,
    val y: Float,
    val z: Float,
)

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
    val z: Float = 0f,
)

/** 立方体在模型坐标里的六个边界，按面取四角时使用。 */
private class BoxBounds(
    box: Box,
) {
    val x0: Float = box.x - box.w / 2f - box.inflate
    val x1: Float = box.x + box.w / 2f + box.inflate
    val y0: Float = box.y - box.h / 2f - box.inflate
    val y1: Float = box.y + box.h / 2f + box.inflate
    val z0: Float = box.z - box.d / 2f - box.inflate
    val z1: Float = box.z + box.d / 2f + box.inflate
}

/** 正交投影相机：先绕 y 轴偏转，再按俯仰倾斜，最后按画布尺寸缩放。 */
private class SkinCamera(
    val cy: Float,
    val sy: Float,
    val cp: Float,
    val sp: Float,
    val pivot: Float,
    val scale: Float,
) {
    companion object {
        fun of(
            yaw: Float,
            pitch: Float,
            headOnly: Boolean,
            width: Int,
            height: Int,
        ): SkinCamera {
            val angle = Math.toRadians((yaw % FULL_TURN_DEGREES).toDouble())
            val tiltDegrees = pitch.coerceIn(-MAX_PITCH_DEGREES, MAX_PITCH_DEGREES)
            val tilt = Math.toRadians(tiltDegrees.toDouble())
            val pivot = if (headOnly) HEAD_ONLY_PIVOT else BODY_PIVOT
            val scale =
                if (headOnly) {
                    min(width, height) / HEAD_ONLY_SCALE_DIVISOR
                } else {
                    min(width / MODEL_SCALE_WIDTH_DIVISOR, height / MODEL_SCALE_HEIGHT_DIVISOR)
                }
            return SkinCamera(
                cos(angle).toFloat(),
                sin(angle).toFloat(),
                cos(tilt).toFloat(),
                sin(tilt).toFloat(),
                pivot,
                scale,
            )
        }
    }
}

/** 带深度缓冲的软件光栅化器：把一组立方体按面投影、贴图并混合到画布。 */
private class SkinRasterizer(
    private var texture: IntArray,
    private var textureWidth: Int,
    private var textureHeight: Int,
    private val width: Int,
    private val height: Int,
    private val camera: SkinCamera,
) {
    private val pixels = IntArray(width * height)
    private val depth = FloatArray(pixels.size) { Float.NEGATIVE_INFINITY }

    fun cape(image: BufferedImage) {
        textureWidth = image.width
        textureHeight = image.height
        texture = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        rasterize(listOf(Box(0f, BODY_Y - 2f, 10, 16, 1, 0, 0, z = -3f)))
    }

    fun rasterize(boxes: List<Box>): IntArray {
        for (box in boxes) {
            val bounds = BoxBounds(box)
            drawFrontAndBackFaces(box, bounds)
            drawSideFaces(box, bounds)
            drawTopAndBottomFaces(box, bounds)
        }
        return pixels
    }

    private fun drawFrontAndBackFaces(
        box: Box,
        bounds: BoxBounds,
    ) {
        drawFace(
            listOf(
                Point(bounds.x0, bounds.y1, bounds.z1),
                Point(bounds.x1, bounds.y1, bounds.z1),
                Point(bounds.x1, bounds.y0, bounds.z1),
                Point(bounds.x0, bounds.y0, bounds.z1),
            ),
            Region(box.u + box.d, box.v + box.d, box.w, box.h),
            Point(0f, 0f, 1f),
            1f,
            box.mirror,
        )
        drawFace(
            listOf(
                Point(bounds.x1, bounds.y1, bounds.z0),
                Point(bounds.x0, bounds.y1, bounds.z0),
                Point(bounds.x0, bounds.y0, bounds.z0),
                Point(bounds.x1, bounds.y0, bounds.z0),
            ),
            Region(box.u + 2 * box.d + box.w, box.v + box.d, box.w, box.h),
            Point(0f, 0f, -1f),
            BACK_FACE_SHADE,
            box.mirror,
        )
    }

    private fun drawSideFaces(
        box: Box,
        bounds: BoxBounds,
    ) {
        val leftU = if (box.mirror) box.u + box.d + box.w else box.u
        val rightU = if (box.mirror) box.u else box.u + box.d + box.w
        drawFace(
            listOf(
                Point(bounds.x0, bounds.y1, bounds.z0),
                Point(bounds.x0, bounds.y1, bounds.z1),
                Point(bounds.x0, bounds.y0, bounds.z1),
                Point(bounds.x0, bounds.y0, bounds.z0),
            ),
            Region(leftU, box.v + box.d, box.d, box.h),
            Point(-1f, 0f, 0f),
            LEFT_FACE_SHADE,
            box.mirror,
        )
        drawFace(
            listOf(
                Point(bounds.x1, bounds.y1, bounds.z1),
                Point(bounds.x1, bounds.y1, bounds.z0),
                Point(bounds.x1, bounds.y0, bounds.z0),
                Point(bounds.x1, bounds.y0, bounds.z1),
            ),
            Region(rightU, box.v + box.d, box.d, box.h),
            Point(1f, 0f, 0f),
            RIGHT_FACE_SHADE,
            box.mirror,
        )
    }

    private fun drawTopAndBottomFaces(
        box: Box,
        bounds: BoxBounds,
    ) {
        drawFace(
            listOf(
                Point(bounds.x0, bounds.y1, bounds.z0),
                Point(bounds.x1, bounds.y1, bounds.z0),
                Point(bounds.x1, bounds.y1, bounds.z1),
                Point(bounds.x0, bounds.y1, bounds.z1),
            ),
            Region(box.u + box.d, box.v, box.w, box.d),
            Point(0f, 1f, 0f),
            1f,
            box.mirror,
        )
        drawFace(
            listOf(
                Point(bounds.x0, bounds.y0, bounds.z1),
                Point(bounds.x1, bounds.y0, bounds.z1),
                Point(bounds.x1, bounds.y0, bounds.z0),
                Point(bounds.x0, bounds.y0, bounds.z0),
            ),
            Region(box.u + box.d + box.w, box.v, box.w, box.d),
            Point(0f, -1f, 0f),
            BOTTOM_FACE_SHADE,
            box.mirror,
        )
    }

    private fun drawFace(
        points: List<Point>,
        region: Region,
        normal: Point,
        shade: Float,
        mirror: Boolean,
    ) {
        if (rotate(normal).z <= BACKFACE_MIN_DEPTH) return
        val vertices = project(points, region, mirror)
        drawTriangle(vertices[0], vertices[1], vertices[2], region, shade)
        drawTriangle(vertices[0], vertices[2], vertices[QUAD_LAST_VERTEX_INDEX], region, shade)
    }

    private fun project(
        points: List<Point>,
        region: Region,
        mirror: Boolean,
    ): List<Projected> {
        val uv = listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f)
        return points.mapIndexed { index, point ->
            val rotated = rotate(Point(point.x, point.y - camera.pivot, point.z))
            val (u, v) = uv[index]
            Projected(
                width / 2f + rotated.x * camera.scale,
                height / 2f - rotated.y * camera.scale,
                rotated.z,
                region.u + (if (mirror) 1f - u else u) * region.w,
                region.v + v * region.h,
            )
        }
    }

    private fun drawTriangle(
        a: Projected,
        b: Projected,
        c: Projected,
        region: Region,
        shade: Float,
    ) {
        val denominator = (b.y - c.y) * (a.x - c.x) + (c.x - b.x) * (a.y - c.y)
        if (abs(denominator) < DEGENERATE_TRIANGLE_EPSILON) return
        val x0 = floor(minOf(a.x, b.x, c.x)).toInt().coerceIn(0, width - 1)
        val x1 = ceil(maxOf(a.x, b.x, c.x)).toInt().coerceIn(0, width - 1)
        val y0 = floor(minOf(a.y, b.y, c.y)).toInt().coerceIn(0, height - 1)
        val y1 = ceil(maxOf(a.y, b.y, c.y)).toInt().coerceIn(0, height - 1)
        val triangle = Triangle(a, b, c, region, shade, denominator)
        for (y in y0..y1) {
            for (x in x0..x1) shadePixel(triangle, x, y)
        }
    }

    /** 三角面内单个像素：权重越界或未通过深度、透明测试时不写缓冲。 */
    private fun shadePixel(
        triangle: Triangle,
        x: Int,
        y: Int,
    ) {
        val a = triangle.a
        val b = triangle.b
        val c = triangle.c
        val region = triangle.region
        val px = x + PIXEL_CENTER_OFFSET
        val py = y + PIXEL_CENTER_OFFSET
        val wa = ((b.y - c.y) * (px - c.x) + (c.x - b.x) * (py - c.y)) / triangle.denominator
        val wb = ((c.y - a.y) * (px - c.x) + (a.x - c.x) * (py - c.y)) / triangle.denominator
        val wc = 1f - wa - wb
        val outsideEdge =
            wa < -TRIANGLE_EDGE_EPSILON ||
                wb < -TRIANGLE_EDGE_EPSILON ||
                wc < -TRIANGLE_EDGE_EPSILON
        if (outsideEdge) return
        val z = wa * a.z + wb * b.z + wc * c.z
        val offset = y * width + x
        val u =
            (wa * a.u + wb * b.u + wc * c.u).coerceIn(
                region.u.toFloat(),
                region.u + region.w - REGION_UV_INSET,
            )
        val v =
            (wa * a.v + wb * b.v + wc * c.v).coerceIn(
                region.v.toFloat(),
                region.v + region.h - REGION_UV_INSET,
            )
        val tx = (u * textureWidth / TEXTURE_ATLAS_TILE).toInt().coerceIn(0, textureWidth - 1)
        val ty = (v * textureWidth / TEXTURE_ATLAS_TILE).toInt().coerceIn(0, textureHeight - 1)
        val color = texture[ty * textureWidth + tx]
        val alpha = color ushr ALPHA_SHIFT
        if (z <= depth[offset] + DEPTH_BUFFER_BIAS || alpha == 0) return
        blendPixel(offset, color, alpha, z, triangle.shade)
    }

    /** 把单个像素按覆盖权重混合进颜色与深度缓冲。 */
    private fun blendPixel(
        offset: Int,
        color: Int,
        alpha: Int,
        z: Float,
        shade: Float,
    ) {
        val backgroundAlpha = pixels[offset] ushr ALPHA_SHIFT
        val outAlpha = alpha + (backgroundAlpha * (CHANNEL_MAX - alpha)) / CHANNEL_MAX

        fun channel(shift: Int): Int {
            val foreground =
                (((color ushr shift) and CHANNEL_MAX) * shade)
                    .toInt()
                    .coerceIn(0, CHANNEL_MAX)
            val background = (pixels[offset] ushr shift) and CHANNEL_MAX
            return (
                foreground * alpha * CHANNEL_MAX +
                    background * backgroundAlpha * (CHANNEL_MAX - alpha)
            ) / (outAlpha * CHANNEL_MAX)
        }
        pixels[offset] =
            (outAlpha shl ALPHA_SHIFT) or
            (channel(RED_SHIFT) shl RED_SHIFT) or
            (channel(GREEN_SHIFT) shl GREEN_SHIFT) or
            channel(0)
        depth[offset] = z
    }

    private fun rotate(p: Point): Point {
        val x = p.x * camera.cy + p.z * camera.sy
        val z = -p.x * camera.sy + p.z * camera.cy
        return Point(x, p.y * camera.cp - z * camera.sp, p.y * camera.sp + z * camera.cp)
    }
}

/** 三角面在屏幕空间的数据，供逐像素着色复用。 */
private data class Triangle(
    val a: Projected,
    val b: Projected,
    val c: Projected,
    val region: Region,
    val shade: Float,
    val denominator: Float,
)

/** A small, CPU-rendered textured model; no GPU-specific runtime or external skin service. */
object SkinModelRenderer {
    // 64 像素纹理坐标下的头部正面与帽子/外层正面区域；高清皮肤按 width / 64 等比放大。
    private const val HEAD_REGION_U = 8
    private const val HEAD_REGION_V = 8
    private const val HAT_REGION_U = 40
    private const val HAT_REGION_V = 8
    private const val HEAD_TILE = 8

    fun render(
        textureWidth: Int,
        textureHeight: Int,
        texture: IntArray,
        variant: SkinVariant,
        overlays: Boolean = true,
        yaw: Float = DEFAULT_YAW_DEGREES,
        pitch: Float = DEFAULT_PITCH_DEGREES,
        headOnly: Boolean = false,
        width: Int = DEFAULT_RENDER_WIDTH,
        height: Int = DEFAULT_RENDER_HEIGHT,
        cape: BufferedImage? = null,
    ): BufferedImage {
        require(
            hasSupportedSkinWidth(textureWidth) &&
                (textureHeight == textureWidth || textureHeight * 2 == textureWidth) &&
                texture.size == textureWidth * textureHeight,
        )
        require(
            width in MIN_RENDER_SIZE..MAX_RENDER_SIZE &&
                height in MIN_RENDER_SIZE..MAX_RENDER_SIZE &&
                yaw.isFinite() &&
                pitch.isFinite(),
        )
        val boxes = buildBoxes(variant, textureHeight == textureWidth, headOnly, overlays)
        val rasterizer =
            SkinRasterizer(
                texture,
                textureWidth,
                textureHeight,
                width,
                height,
                SkinCamera.of(yaw, pitch, headOnly, width, height),
            )
        val pixels = rasterizer.rasterize(boxes)
        if (!headOnly && cape != null && cape.width == 64 && cape.height == 32) rasterizer.cape(cape)
        return BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
            setRGB(0, 0, width, height, pixels, 0, width)
        }
    }

    private fun buildBoxes(
        variant: SkinVariant,
        modern: Boolean,
        headOnly: Boolean,
        overlays: Boolean,
    ): List<Box> {
        val arm = if (modern && variant == SkinVariant.Slim) SLIM_ARM_W else LIMB_W
        val boxes = mutableListOf(Box(0f, HEAD_Y, HEAD_SIZE, HEAD_SIZE, HEAD_SIZE, 0, 0))
        if (!headOnly) boxes += baseBoxes(arm, modern)
        if (overlays) boxes += overlayBoxes(arm, modern, headOnly)
        return boxes
    }

    private fun baseBoxes(
        arm: Int,
        modern: Boolean,
    ): List<Box> {
        val armHalfOffset = ARM_X_OFFSET + arm / 2f
        val rightArm =
            if (modern) {
                Box(armHalfOffset, ARM_Y, arm, LIMB_H, LIMB_D, R_ARM_U, R_ARM_V)
            } else {
                Box(LEGACY_ARM_X, ARM_Y, LIMB_W, LIMB_H, LIMB_D, ARM_U, ARM_V, mirror = true)
            }
        val rightLeg =
            if (modern) {
                Box(RIGHT_LEG_X, LEG_Y, LIMB_W, LIMB_H, LIMB_D, R_LEG_U, R_LEG_V)
            } else {
                Box(RIGHT_LEG_X, LEG_Y, LIMB_W, LIMB_H, LIMB_D, 0, LEG_V, mirror = true)
            }
        return listOf(
            Box(0f, BODY_Y, BODY_W, BODY_H, BODY_D, BODY_U, BODY_V),
            Box(-armHalfOffset, ARM_Y, arm, LIMB_H, LIMB_D, ARM_U, ARM_V),
            rightArm,
            Box(LEFT_LEG_X, LEG_Y, LIMB_W, LIMB_H, LIMB_D, 0, LEG_V),
            rightLeg,
        )
    }

    private fun overlayBoxes(
        arm: Int,
        modern: Boolean,
        headOnly: Boolean,
    ): List<Box> {
        val armOffset = ARM_X_OFFSET + arm / 2f
        val boxes =
            mutableListOf(
                Box(0f, HEAD_Y, HEAD_SIZE, HEAD_SIZE, HEAD_SIZE, HAT_U, 0, HAT_INFLATE),
            )
        if (!modern || headOnly) return boxes
        boxes += Box(0f, BODY_Y, BODY_W, BODY_H, BODY_D, BODY_U, BODY_OV_V, OVERLAY_INFLATE)
        boxes += Box(-armOffset, ARM_Y, arm, LIMB_H, LIMB_D, ARM_U, ARM_OV_V, OVERLAY_INFLATE)
        boxes += Box(armOffset, ARM_Y, arm, LIMB_H, LIMB_D, R_ARM_OV_U, R_ARM_OV_V, OVERLAY_INFLATE)
        boxes += Box(LEFT_LEG_X, LEG_Y, LIMB_W, LIMB_H, LIMB_D, 0, LEG_OV_V, OVERLAY_INFLATE)
        boxes += Box(RIGHT_LEG_X, LEG_Y, LIMB_W, LIMB_H, LIMB_D, 0, R_LEG_OV_V, OVERLAY_INFLATE)
        return boxes
    }

    /**
     * 静态正面头像：把皮肤贴图的头部正面 (8,8)-(16,16) 与帽子/外层正面 (40,8)-(48,16) 合成为一张方图。 先画脸部，再按帽子层自身的 alpha
     * 叠加，所以透明帽子区域保持脸部原样、脸外区域也保持透明（不填黑底）。 旧版 64×32 皮肤同样使用这两个坐标，所有读取都在 y < 32 之内；最后按最近邻放大到
     * [size]×[size]。 贴图无法解析或尺寸不受支持时返回 null，由界面回退到占位头像，绝不抛异常。
     */
    fun renderHeadFront(
        skin: ByteArray,
        size: Int = DEFAULT_HEAD_FRONT_SIZE,
    ): ByteArray? =
        runCatching {
            require(skin.isNotEmpty()) { I18n.t("miscA2.skinRenderer.emptyData") }
            require(size in HEAD_TILE..MAX_HEAD_FRONT_SIZE) {
                I18n.t("miscA2.skinRenderer.invalidHeadSize")
            }
            val image =
                ImageIO.read(ByteArrayInputStream(skin))
                    ?: error(I18n.t("miscA2.skinRenderer.unreadableImage"))
            val width = image.width
            val height = image.height
            // 只接受 64 的整数倍宽度：64×64（含高清）或旧版 64×32；其它布局不猜位置。
            require(hasSupportedSkinWidth(width)) { I18n.t("miscA2.skinRenderer.invalidWidth") }
            require(height == width || height * 2 == width) {
                I18n.t("miscA2.skinRenderer.invalidHeight")
            }
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
                headFrontPixels(size, faceSide, faceOffset, shadeShift, ::faceSample, ::hatSample)
            val output = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            output.setRGB(0, 0, size, size, pixels, 0, size)
            ByteArrayOutputStream().also { ImageIO.write(output, "png", it) }.toByteArray()
        }.getOrNull()

    /** 正面头像像素合成：脸缩到 8/9 居中，帽子满幅叠加并在下缘压半透明投影。 */
    private fun headFrontPixels(
        size: Int,
        faceSide: Int,
        faceOffset: Int,
        shadeShift: Int,
        faceSample: (Int, Int) -> Int,
        hatSample: (Int, Int) -> Int,
    ): IntArray =
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

    /** 立体头像：复用 [render] 只渲染头部，正对镜头（水平不偏转，仅略微俯视露出顶面）， 输出 PNG 字节；皮肤不可解析时返回 null。 */
    fun renderHeadModel(
        skin: ByteArray,
        size: Int = DEFAULT_HEAD_MODEL_SIZE,
        yaw: Float = 0f,
        pitch: Float = DEFAULT_HEAD_MODEL_PITCH,
    ): ByteArray? =
        runCatching {
            val image =
                ImageIO.read(ByteArrayInputStream(skin))
                    ?: error(I18n.t("miscA2.skinRenderer.unreadableImage"))
            val width = image.width
            val height = image.height
            require(hasSupportedSkinWidth(width)) { I18n.t("miscA2.skinRenderer.invalidWidth") }
            require(height == width || height * 2 == width) {
                I18n.t("miscA2.skinRenderer.invalidHeight")
            }
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
        val sourceAlpha = source ushr ALPHA_SHIFT
        if (sourceAlpha == 0) return destination
        if (sourceAlpha == OPAQUE_ALPHA) return source
        val destinationAlpha = destination ushr ALPHA_SHIFT
        val alpha = sourceAlpha + destinationAlpha * (CHANNEL_MAX - sourceAlpha) / CHANNEL_MAX
        if (alpha == 0) return 0

        fun channel(shift: Int): Int {
            val foreground = ((source ushr shift) and CHANNEL_MAX) * sourceAlpha
            val background = ((destination ushr shift) and CHANNEL_MAX) * destinationAlpha
            return (foreground + background * (CHANNEL_MAX - sourceAlpha) / CHANNEL_MAX) / alpha
        }
        return (alpha shl ALPHA_SHIFT) or
            (channel(RED_SHIFT) shl RED_SHIFT) or
            (channel(GREEN_SHIFT) shl GREEN_SHIFT) or
            channel(0)
    }
}
