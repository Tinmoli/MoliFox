// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.PlatformServices
import dev.molifox.core.automaticMemoryMb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val MEMORY_SAMPLE_INTERVAL_MILLIS = 1_000L

@Composable
internal fun MemoryAllocationBar(
    platform: PlatformServices,
    manualMb: Int,
    automatic: Boolean,
) {
    val total = platform.totalMemoryMb.coerceAtLeast(1)
    val available by produceState(total, platform) {
        while (true) {
            value = withContext(Dispatchers.IO) { platform.availableMemoryMb.coerceIn(0, total) }
            delay(MEMORY_SAMPLE_INTERVAL_MILLIS)
        }
    }
    val used = total - available
    val allocation = if (automatic) automaticMemoryMb(total, available) else manualMb
    val planned = allocation.coerceIn(0, available)
    Column {
        MemorySegments(used, planned, available - planned)
        MemoryAllocationLegend(used, allocation)
    }
}

@Composable
private fun MemorySegments(
    used: Int,
    planned: Int,
    remaining: Int,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .height(7.dp)
            .clip(CircleShape)
            .background(colors.outlineVariant),
    ) {
        if (used > 0) Box(Modifier.weight(used.toFloat()).fillMaxHeight().background(colors.secondary))
        if (planned > 0) Box(Modifier.weight(planned.toFloat()).fillMaxHeight().background(colors.primary))
        if (remaining > 0) Box(Modifier.weight(remaining.toFloat()).fillMaxHeight())
    }
}

@Composable
private fun MemoryAllocationLegend(
    used: Int,
    allocation: Int,
) {
    val strings = LocalStrings.current
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            strings.t("settings.game.memory.used", gigabytesFromMebibytes(used.toFloat())),
            fontSize = 11.sp,
            color = colors.secondary,
        )
        Text(
            strings.t("settings.game.memory.allocated", gigabytesFromMebibytes(allocation.toFloat())),
            fontSize = 11.sp,
            color = colors.primary,
        )
    }
}
