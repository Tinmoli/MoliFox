// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import io.github.yuroyami.imagekodec.ImageFormat
import io.github.yuroyami.imagekodec.ImageInfo
import io.github.yuroyami.imagekodec.ImageKodec
import io.github.yuroyami.imagekodec.KiteAnimation
import io.github.yuroyami.imagekodec.KiteFrame
import java.util.logging.Logger

/**
 * 动图背景的解码结果。帧像素使用 ARGB_8888 的 `IntArray`，格式与 Compose 的
 * `ImageBitmap` 转换层解耦；界面层只在本机渲染时再转换成 Skia 位图。
 */
data class BackgroundFramePixels(
    val width: Int,
    val height: Int,
    val argb: IntArray,
    val delayMillis: Int,
)

/** 动图背景的一轮播放数据；`loopCount == 0` 表示无限循环。 */
data class BackgroundAnimationPixels(
    val width: Int,
    val height: Int,
    val frames: List<BackgroundFramePixels>,
    val loopCount: Long,
)

/** 动图背景的资源上限：先检查预算再分配，避免一次性解码超大 GIF/APNG。 */
object BackgroundAnimationPolicy {
    const val MAX_FRAMES = 180
    const val MAX_PIXELS = 4_000_000
    const val MAX_RAW_BYTES = 192L * 1024 * 1024

    /**
     * 播放帧的目标像素上限。窗口合成实际最多显示约 2 MP；超过它的源帧在解码后
     * 统一降采样，避免逐帧位图转换、显存与模糊开销随 2K/4K 源成倍增长，而肉眼
     * 并无可见差别。源文件保持原样，只在播放数据上生效。
     */
    const val FRAME_TARGET_PIXELS = 2_000_000

    /** 解码后一帧占用的原始像素字节数，不含 Skia/Compose 的副本。 */
    fun rawBytes(
        width: Int,
        height: Int,
        frameCount: Int,
    ): Long = width.toLong() * height.toLong() * BYTES_PER_PIXEL * frameCount.toLong()
}

private const val MAX_STATIC_BACKGROUND_PIXELS = 20_000_000L
private const val BYTES_PER_PIXEL = 4L
private val supportedFormats = setOf(ImageFormat.PNG, ImageFormat.JPEG, ImageFormat.GIF, ImageFormat.WEBP)
private val logger = Logger.getLogger("MoliFox.background")

private fun maxPixelsFor(animated: Boolean): Long =
    if (animated) BackgroundAnimationPolicy.MAX_PIXELS.toLong() else MAX_STATIC_BACKGROUND_PIXELS

/**
 * 把背景文件解码成静态或动态帧。返回 null 表示文件损坏、格式不在支持范围，
 * 或超出动图预算；调用方应回退到静态背景而不是让界面崩溃。
 *
 * PNG 同时承载静态图和 APNG，GIF/WebP 也由同一解码器处理；因此界面不需要
 * 根据扩展名分支，只看返回的帧数即可。
 */
fun decodeBackgroundAnimation(bytes: ByteArray): BackgroundAnimationPixels? {
    val info = probeBackground(bytes) ?: return null
    if (!withinAnimationBudget(info)) return null
    val animation = decodeAnimation(bytes) ?: return null
    return animation.toPixels()
}

private fun probeBackground(bytes: ByteArray): ImageInfo? =
    runCatching { ImageKodec.probe(bytes) }
        .onFailure { logger.fine("Background probe failed: ${it.message}") }
        .getOrNull()

private fun withinAnimationBudget(info: ImageInfo): Boolean {
    if (!info.isDecodable || info.format !in supportedFormats) return false
    if (info.width <= 0 || info.height <= 0) return false
    if (info.width.toLong() * info.height > maxPixelsFor(info.isAnimated)) return false
    val frameCount = info.frameCount.coerceAtLeast(1)
    if (frameCount > BackgroundAnimationPolicy.MAX_FRAMES) return false
    return BackgroundAnimationPolicy.rawBytes(info.width, info.height, frameCount) <=
        BackgroundAnimationPolicy.MAX_RAW_BYTES
}

private fun decodeAnimation(bytes: ByteArray): KiteAnimation? =
    runCatching { ImageKodec.decodeAnimation(bytes) }
        .onFailure { logger.fine("Background decode failed: ${it.message}") }
        .getOrNull()

private fun KiteAnimation.toPixels(): BackgroundAnimationPixels? {
    if (frames.isEmpty()) return null
    val scale = frameDownscale(width, height)
    return BackgroundAnimationPixels(
        width = scaledEdge(width, scale),
        height = scaledEdge(height, scale),
        frames = frames.map { frame -> frame.toPixels(scale) },
        loopCount = loopCount,
    )
}

private fun KiteFrame.toPixels(scale: Double): BackgroundFramePixels {
    val targetWidth = scaledEdge(bitmap.width, scale)
    val targetHeight = scaledEdge(bitmap.height, scale)
    return BackgroundFramePixels(
        width = targetWidth,
        height = targetHeight,
        argb =
            if (scale < 1.0) {
                bilinearResize(bitmap.argb, bitmap.width, bitmap.height, targetWidth, targetHeight)
            } else {
                bitmap.argb
            },
        delayMillis = delayMillis,
    )
}

/** 检查导入文件是否是支持的静态/动态背景；只在导入时读头部，不解码全部帧。 */
internal fun validateAppearanceBackgroundBytes(bytes: ByteArray) {
    val info =
        runCatching { ImageKodec.probe(bytes) }
            .getOrNull()
            ?: throw IllegalArgumentException(I18n.t("core.appearanceAssets.5"))
    require(info.isDecodable) { I18n.t("core.appearanceAssets.5") }
    require(info.format in supportedFormats) { I18n.t("core.appearanceAssets.3") }
    require(info.width > 0 && info.height > 0) { I18n.t("core.appearanceAssets.4") }
    require(info.width.toLong() * info.height <= maxPixelsFor(info.isAnimated)) {
        I18n.t("core.appearanceAssets.4")
    }
    if (info.isAnimated) {
        require(info.frameCount <= BackgroundAnimationPolicy.MAX_FRAMES) {
            I18n.t("core.appearanceAssets.8")
        }
        require(
            BackgroundAnimationPolicy.rawBytes(info.width, info.height, info.frameCount) <=
                BackgroundAnimationPolicy.MAX_RAW_BYTES,
        ) {
            I18n.t("core.appearanceAssets.9")
        }
    }
}
