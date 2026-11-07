// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core

import com.materialkolor.hct.Hct
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.score.Score
import io.github.yuroyami.imagekodec.ImageDecodeException
import io.github.yuroyami.imagekodec.ImageKodec
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.logging.Logger
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.max

private const val MIN_SAMPLE_ALPHA = 128
private const val OPAQUE_ALPHA = -0x1000000
private const val RGB_MASK = 0xFFFFFF
private const val ALPHA_SHIFT = 24

/** 默认种子为樱花粉（与预设 sakura 同色，保持适度彩度）；无彩色图片不会让整套主题退化成灰阶。 */
const val DEFAULT_THEME_SEED: Int = -0x46B185 // #B94E7B
private const val SAMPLE_EDGE = 128
private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
private const val MAX_IMAGE_PIXELS = 100_000_000L
private const val MAX_REPRESENTATIVE_COLORS = 128

/**
 * 图片字节 → 不透明 ARGB 种子。无 Compose/窗口/设置依赖，可独立测试。
 *
 * 先在解码器中降采样，避免为取色分配整张大图；再将最多约 128×128 个像素交给
 * Material 的 Celebi 量化和 Score 排序。排序考虑感知彩度及相近色相的实际占比，
 * 而不是找最亮像素、平均整幅图或手写色相桶。tone 边界排除极暗/极亮噪声。
 *
 * 同步 CPU/IO 函数，调用端必须调度到后台；损坏、无彩色和全透明输入都返回默认种子。
 */
fun extractBackgroundSeed(bytes: ByteArray): Int {
    if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return DEFAULT_THEME_SEED
    return try {
        seedFromPixels(sampleBackgroundPixels(bytes))
    } catch (failure: IOException) {
        backgroundSeedFailure(failure)
    } catch (failure: IllegalArgumentException) {
        backgroundSeedFailure(failure)
    } catch (failure: IllegalStateException) {
        // ImageIO 解码器也可能抛运行时异常；坏图不能使外观页或启动流程退出。
        backgroundSeedFailure(failure)
    }
}

/**
 * 已解码 ARGB 像素 → 主题种子。视频首帧由 FramePreview 给出 ARGB_8888，
 * 不再重新编码成图片；与图片路径共用同一套量化和评分，颜色口径保持一致。
 *
 * 同步 CPU 函数，调用端必须调度到后台；空输入、全透明和异常都返回默认种子。
 */
fun extractBackgroundSeed(pixels: IntArray): Int {
    if (pixels.isEmpty() || pixels.size.toLong() > MAX_IMAGE_PIXELS) return DEFAULT_THEME_SEED
    return try {
        seedFromPixels(pixels)
    } catch (failure: IllegalArgumentException) {
        backgroundSeedFailure(failure)
    } catch (failure: IllegalStateException) {
        backgroundSeedFailure(failure)
    }
}

private fun seedFromPixels(pixels: IntArray): Int {
    // 隐藏的透明像素可能保存任意 RGB，不能让它们参与主题选色。
    val opaque =
        pixels
            .asSequence()
            .filter { (it ushr ALPHA_SHIFT) >= MIN_SAMPLE_ALPHA }
            .map { it or OPAQUE_ALPHA }
            .toList()
    if (opaque.isEmpty()) return DEFAULT_THEME_SEED
    // 少色图片已经满足代表色上限，保留精确 RGB 及次数；不做有损分桶或自定义评分。
    val exact = opaque.groupingBy { it }.eachCount()
    val quantized =
        if (exact.size <= MAX_REPRESENTATIVE_COLORS) {
            exact
        } else {
            QuantizerCelebi.quantize(opaque.toIntArray(), MAX_REPRESENTATIVE_COLORS)
        }
    val representatives = quantized.filterKeys { Hct.fromInt(it).tone in 5.0..98.0 }
    return Score.score(representatives, desired = 1, fallbackColorArgb = DEFAULT_THEME_SEED).first()
}

private fun sampleBackgroundPixels(bytes: ByteArray): IntArray {
    val imageIoPixels = sampleWithImageIo(bytes)
    if (imageIoPixels.isNotEmpty()) return imageIoPixels
    // JDK 不自带 WebP 解码器；ImageIO 无 reader 时交给项目已有的 ImageKodec 解首帧。
    return sampleWithImageKodec(bytes)
}

private fun sampleWithImageIo(bytes: ByteArray): IntArray =
    ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
        if (input == null) return@use IntArray(0)
        val readers = ImageIO.getImageReaders(input)
        if (!readers.hasNext()) return@use IntArray(0)
        val reader = readers.next()
        try {
            reader.input = input
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            if (width <= 0 || height <= 0 || width.toLong() * height > MAX_IMAGE_PIXELS) {
                return@use IntArray(0)
            }
            val stride = max(1, ceil(max(width, height).toDouble() / SAMPLE_EDGE).toInt())
            val parameters = reader.defaultReadParam.apply { setSourceSubsampling(stride, stride, 0, 0) }
            val image = reader.read(0, parameters) ?: return@use IntArray(0)
            try {
                val pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
                // 隐藏的透明像素可能保存任意 RGB，不能让它们参与主题选色。
                pixels.filter { (it ushr ALPHA_SHIFT) >= MIN_SAMPLE_ALPHA }.map { it or OPAQUE_ALPHA }.toIntArray()
            } finally {
                image.flush()
            }
        } finally {
            reader.dispose()
        }
    }

private fun sampleWithImageKodec(bytes: ByteArray): IntArray {
    val info =
        try {
            ImageKodec.probe(bytes)
        } catch (failure: ImageDecodeException) {
            backgroundSeedFailure(failure)
            return IntArray(0)
        } catch (failure: IllegalArgumentException) {
            backgroundSeedFailure(failure)
            return IntArray(0)
        }
    if (!info.isDecodable || info.width <= 0 || info.height <= 0) return IntArray(0)
    if (info.width.toLong() * info.height > MAX_IMAGE_PIXELS) return IntArray(0)
    val bitmap =
        try {
            ImageKodec.decodeScaled(bytes, SAMPLE_EDGE, SAMPLE_EDGE)
        } catch (failure: ImageDecodeException) {
            backgroundSeedFailure(failure)
            return IntArray(0)
        } catch (failure: IllegalArgumentException) {
            backgroundSeedFailure(failure)
            return IntArray(0)
        }
    return bitmap.argb
        .filter { (it ushr ALPHA_SHIFT) >= MIN_SAMPLE_ALPHA }
        .map { it or OPAQUE_ALPHA }
        .toIntArray()
}

private fun backgroundSeedFailure(failure: Exception): Int {
    Logger.getLogger("MoliFox.appearance").fine("Image seed fallback: ${failure.javaClass.simpleName}")
    return DEFAULT_THEME_SEED
}

/** 兼容现有资源服务的十六进制边界；这里返回的是种子，绝不是最终按钮颜色。 */
internal fun extractBackgroundAccent(bytes: ByteArray): String =
    "#%06X".format(extractBackgroundSeed(bytes) and RGB_MASK)
