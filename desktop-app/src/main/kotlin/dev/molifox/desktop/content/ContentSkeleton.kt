// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 首屏骨架：加载中的占位环与灰条，避免整页在加载与结果之间跳动。

package dev.molifox.desktop.content

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.desktop.LocalAnimationsEnabled

/** 骨架：28dp 环 + 3 行灰条，稳定占位，避免整页在加载与结果之间跳动。 */
@Composable
internal fun SkeletonResults() {
    val alpha =
        if (LocalAnimationsEnabled.current) {
            val transition = rememberInfiniteTransition(label = "skeleton")
            val value by
                transition.animateFloat(
                    0.45f,
                    0.9f,
                    infiniteRepeatable(tween(760), RepeatMode.Reverse),
                    label = "skeleton-alpha",
                )
            value
        } else {
            0.6f
        }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                "正在检索资源…",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        repeat(3) { SkeletonRow(alpha) }
    }
}

@Composable
private fun SkeletonRow(alpha: Float) {
    val bar = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .18f * alpha)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(bar))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(
                Modifier
                    .fillMaxWidth(.45f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(bar),
            )
            Box(
                Modifier
                    .fillMaxWidth(.8f)
                    .height(9.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(bar),
            )
        }
    }
}
