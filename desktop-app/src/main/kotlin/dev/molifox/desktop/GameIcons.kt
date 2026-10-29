// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责游戏和加载器图标的资源映射。

package dev.molifox.desktop

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 草方块图标：使用随包内置的 MC 风格方块图片，最近邻缩放保持像素硬边。 图片在不同尺寸下都只显示一次，不额外绘制回退图；资源缺失时只留空白占位。 */
@Composable
fun GrassBlockIcon(modifier: Modifier = Modifier, size: Dp = 20.dp) {
    BundledBlockIcon("icons/blocks/grass.png", "草方块", modifier.size(size))
}
