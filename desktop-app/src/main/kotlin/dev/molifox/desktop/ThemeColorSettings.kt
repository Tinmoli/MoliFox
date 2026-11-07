// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import dev.molifox.core.LauncherSettings

private val HEX_COLOR = Regex("^#[0-9A-Fa-f]{6}$")

/** 来源切换只保存来源，手动种子独立保留；旧自动字段同步写入以保持旧读取方兼容。 */
@Composable
internal fun AccentColorSection(
    settings: LauncherSettings,
    apply: AppearanceChange,
) {
    val strings = LocalStrings.current
    SettingsSection(strings.t("settings.appearance.accent.title"), strings.t("settings.appearance.accent.detail")) {
        SettingRow(strings.t("settings.appearance.accent.source")) {
            SimpleDropdown(
                settings.themeColorSource,
                listOf(
                    "background",
                    "manual",
                ).map { it to strings.t("settings.appearance.accent.source.$it") },
                { source -> apply { it.copy(themeColorSource = source, automaticAccent = source == "background") } },
                Modifier.width(260.dp),
            )
        }
        SeedColorStatus(settings)
        if (settings.themeColorSource == "manual") ManualSeedControls(settings, apply)
    }
}

/** 把种子和最终角色分别展示，避免把“原图 RGB 必须等于按钮 RGB”当成取色成功条件。 */
@Composable
private fun SeedColorStatus(settings: LauncherSettings) {
    val strings = LocalStrings.current
    val image = LocalBackgroundAccent.current
    val seed = "#%06X".format(themeSeed(settings, image.hex).toArgb() and 0xFFFFFF)
    val primary = "#%06X".format(MaterialTheme.colorScheme.primary.toArgb() and 0xFFFFFF)
    when {
        settings.themeColorSource == "background" && image.loading ->
            MutedText(strings.t("settings.appearance.accent.loading"))

        settings.themeColorSource == "background" && image.hex == null ->
            MutedText(strings.t("settings.appearance.accent.unavailable"))

        else -> MutedText(strings.t("settings.appearance.accent.seedAndPrimary", seed, primary))
    }
}

@Composable
private fun ManualSeedControls(
    settings: LauncherSettings,
    apply: AppearanceChange,
) {
    val strings = LocalStrings.current
    val duration = if (LocalReducedMotion.current) 0 else Motion.FAST
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            accentPresets.forEach { (id, _) ->
                AccentChip(
                    id,
                    strings.t("settings.appearance.accent.$id"),
                    isDarkScheme(),
                    settings.accentColor == id,
                    duration,
                ) {
                    apply { it.copy(accentColor = id) }
                }
            }
        }
        var customHex by remember(settings.accentColor) {
            mutableStateOf(settings.accentColor.takeIf { it.startsWith("#") }.orEmpty())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(200.dp)) {
                CompactTextField(
                    customHex,
                    { value ->
                        customHex = value
                        if (value.matches(HEX_COLOR)) {
                            apply { it.copy(accentColor = value.uppercase()) }
                        }
                    },
                    placeholder = "#RRGGBB",
                )
            }
            if (customHex.isNotEmpty() && !customHex.matches(HEX_COLOR)) {
                Text(strings.t("settings.appearance.accent.invalidFormat"), color = MaterialTheme.colorScheme.error)
            } else {
                MutedText(strings.t("settings.appearance.accent.customHint"))
            }
        }
    }
}
