// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 高级启动参数里的 GC 策略行：只保存选择，不改写 JVM 参数文本。
package dev.molifox.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.molifox.core.GcPreset

@Composable
internal fun GcPresetRow(
    preset: GcPreset,
    onPresetChange: (GcPreset) -> Unit,
) {
    val strings = LocalStrings.current
    SettingRow(
        strings.t("settings.game.advanced.gc"),
        strings.t("settings.game.advanced.gc.detail"),
    ) {
        Box(Modifier.width(190.dp)) {
            SimpleDropdown(
                preset.value,
                GcPreset.entries.map { it.value to strings.t(it.titleKey) },
                { value ->
                    GcPreset.entries
                        .firstOrNull { it.value == value }
                        ?.let(onPresetChange)
                },
                label = strings.t("settings.game.advanced.gc"),
            )
        }
    }
}
