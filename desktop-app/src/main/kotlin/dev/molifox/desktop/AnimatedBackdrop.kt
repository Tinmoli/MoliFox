// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import dev.molifox.core.BackgroundAnimationPixels
import dev.molifox.core.BackgroundFramePixels
import dev.molifox.core.decodeBackgroundAnimation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Image as SkiaImage

private const val BYTES_PER_PIXEL = 4
private const val BLUE_BYTE = 0
private const val GREEN_BYTE = 1
private const val RED_BYTE = 2
private const val ALPHA_BYTE = 3
private const val CHANNEL_MASK = 0xFF
private const val GREEN_SHIFT = 8
private const val BLUE_SHIFT = 16
private const val ALPHA_SHIFT = 24
private const val MIN_FRAME_DELAY_MILLIS = 16

/**
 * 在 IO 线程解码背景。动图会先把全部帧转换成 Compose 位图，避免播放时
 * 在渲染线程反复分配；帧数/像素预算在 `decodeBackgroundAnimation` 中已经限制，
 * 转换失败则回退到静态首帧。
 */
internal suspend fun loadBackdrop(bytes: ByteArray): LoadedBackdrop? =
    withContext(Dispatchers.IO) {
        val animation = decodeBackgroundAnimation(bytes)
        if (animation != null && animation.frames.size > 1) {
            runCatching { animation.toLoadedFrames() }
                .getOrNull()
                ?.let { return@withContext it }
        }
        runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }
            .getOrNull()
            ?.let { LoadedBackdrop.Static(it) }
    }

private fun BackgroundAnimationPixels.toLoadedFrames(): LoadedBackdrop.Animated =
    LoadedBackdrop.Animated(
        frames = frames.map { it.toComposeBitmap() },
        delaysMillis = frames.map { it.delayMillis.coerceAtLeast(MIN_FRAME_DELAY_MILLIS) }.toIntArray(),
    )

private fun BackgroundFramePixels.toComposeBitmap(): ImageBitmap {
    // Compose 的 BGRA_8888 位图在内存中的顺序是 B、G、R、A；这里从 ARGB 整数
    // 直接展开，避免经过 java.awt.image 或额外的 PNG 编码。
    val bytes = ByteArray(width * height * BYTES_PER_PIXEL)
    var offset = 0
    for (color in argb) {
        bytes[offset + BLUE_BYTE] = (color and CHANNEL_MASK).toByte()
        bytes[offset + GREEN_BYTE] = ((color ushr GREEN_SHIFT) and CHANNEL_MASK).toByte()
        bytes[offset + RED_BYTE] = ((color ushr BLUE_SHIFT) and CHANNEL_MASK).toByte()
        bytes[offset + ALPHA_BYTE] = ((color ushr ALPHA_SHIFT) and CHANNEL_MASK).toByte()
        offset += BYTES_PER_PIXEL
    }
    val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)
    return SkiaImage.makeRaster(info, bytes, width * BYTES_PER_PIXEL).toComposeImageBitmap()
}

/** 动图背景：按每帧延迟推进；游戏运行时由外层传入 paused 暂停画面。 */
@Composable
internal fun AnimatedBackdrop(
    animation: LoadedBackdrop.Animated,
    alpha: Float,
    contentScale: ContentScale,
    alignment: Alignment,
    paused: Boolean,
    modifier: Modifier = Modifier,
) {
    var frameIndex by remember(animation) { mutableIntStateOf(0) }
    val delays = animation.delaysMillis
    LaunchedEffect(animation, paused) {
        if (paused || delays.isEmpty()) return@LaunchedEffect
        var lastNanos = withFrameNanos { it }
        var elapsedMillis = 0L
        while (isActive) {
            withFrameNanos { now ->
                elapsedMillis += (now - lastNanos) / 1_000_000
                lastNanos = now
                var guard = 0
                while (elapsedMillis >= delays[frameIndex] && guard++ < delays.size) {
                    elapsedMillis -= delays[frameIndex]
                    frameIndex = (frameIndex + 1) % delays.size
                }
            }
        }
    }
    Image(
        bitmap = animation.frames[frameIndex.coerceIn(animation.frames.indices)],
        contentDescription = null,
        modifier = modifier.fillMaxSize().alpha(alpha),
        contentScale = contentScale,
        alignment = alignment,
    )
}
