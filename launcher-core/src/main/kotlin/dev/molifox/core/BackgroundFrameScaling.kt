// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core

import kotlin.math.max
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val CHANNEL_MASK = 0xFF
private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8

/** 帧像素超过播放目标时返回按面积比例的缩小系数，否则原样播放。 */
internal fun frameDownscale(
    width: Int,
    height: Int,
): Double {
    val pixels = width.toLong() * height.toLong()
    if (pixels <= BackgroundAnimationPolicy.FRAME_TARGET_PIXELS) return 1.0
    return sqrt(BackgroundAnimationPolicy.FRAME_TARGET_PIXELS.toDouble() / pixels)
}

internal fun scaledEdge(
    edge: Int,
    scale: Double,
): Int = max(1, round(edge * scale).toInt())

/**
 * 双线性缩放到目标尺寸。背景是全屏图像且以不透明像素为主，非预乘插值在
 * 全透明像素处的底色混入不可感知；插值在 IO 线程一次完成，播放时不再缩放。
 */
internal fun bilinearResize(
    source: IntArray,
    width: Int,
    height: Int,
    targetWidth: Int,
    targetHeight: Int,
): IntArray {
    val out = IntArray(targetWidth * targetHeight)
    val scaleX = width.toDouble() / targetWidth
    val scaleY = height.toDouble() / targetHeight
    val lastX = width - 1
    val lastY = height - 1
    for (y in 0 until targetHeight) {
        val fy = (y + 0.5) * scaleY - 0.5
        val y0 = fy.toInt().coerceIn(0, lastY)
        val y1 = (y0 + 1).coerceIn(0, lastY)
        val wy = (fy - y0).toFloat().coerceIn(0f, 1f)
        val row0 = y0 * width
        val row1 = y1 * width
        for (x in 0 until targetWidth) {
            val fx = (x + 0.5) * scaleX - 0.5
            val x0 = fx.toInt().coerceIn(0, lastX)
            val x1 = (x0 + 1).coerceIn(0, lastX)
            val wx = (fx - x0).toFloat().coerceIn(0f, 1f)
            val top = mixChannels(source[row0 + x0], source[row0 + x1], wx)
            val bottom = mixChannels(source[row1 + x0], source[row1 + x1], wx)
            out[y * targetWidth + x] = mixChannels(top, bottom, wy)
        }
    }
    return out
}

/** 对打包的 ARGB 四个通道各自做同权重线性插值，保持像素格式不变。 */
private fun mixChannels(
    first: Int,
    second: Int,
    weight: Float,
): Int {
    val alpha = mixChannel(first ushr ALPHA_SHIFT, second ushr ALPHA_SHIFT, weight)
    val red = mixChannel((first shr RED_SHIFT) and CHANNEL_MASK, (second shr RED_SHIFT) and CHANNEL_MASK, weight)
    val green = mixChannel((first shr GREEN_SHIFT) and CHANNEL_MASK, (second shr GREEN_SHIFT) and CHANNEL_MASK, weight)
    val blue = mixChannel(first and CHANNEL_MASK, second and CHANNEL_MASK, weight)
    return (alpha shl ALPHA_SHIFT) or (red shl RED_SHIFT) or (green shl GREEN_SHIFT) or blue
}

private fun mixChannel(
    first: Int,
    second: Int,
    weight: Float,
): Int = (first + (second - first) * weight).roundToInt()
