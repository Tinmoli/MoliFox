// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.desktop

import androidx.compose.ui.graphics.ImageBitmap

/** 背景加载结果：静态图保持首帧，动图预转换成一帧一张 ImageBitmap。 */
internal sealed interface LoadedBackdrop {
    data class Static(
        val bitmap: ImageBitmap,
    ) : LoadedBackdrop

    data class Animated(
        val frames: List<ImageBitmap>,
        val delaysMillis: IntArray,
    ) : LoadedBackdrop
}
