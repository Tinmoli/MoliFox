// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.desktop

import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec
import dev.molifox.core.DEFAULT_THEME_SEED
import dev.molifox.core.LauncherSettings

/** 每个预设只保存一个种子。浅/深色角色均由 Material 生成，不再各写一套按钮色。 */
val accentPresets: List<Pair<String, Color>> =
    listOf(
        "sakura" to Color(0xFFB94E7B),
        "ocean" to Color(0xFF2C6E9E),
        "mint" to Color(0xFF2E7D63),
        "amber" to Color(0xFFB27427),
        "lavender" to Color(0xFF6E5AA0),
        "graphite" to Color(0xFF565B66),
    )

private const val HEX_RADIX = 16
private const val OPAQUE = 0xFF000000L

internal val DefaultThemeSeed = Color(DEFAULT_THEME_SEED)

/** 严格读取 RGB，避免错误输入变成透明色；找不到的值明确回退默认种子。 */
internal fun manualThemeSeed(value: String): Color =
    accentPresets.firstOrNull { it.first == value }?.second
        ?: value
            .takeIf { it.matches(Regex("^#[0-9A-Fa-f]{6}$")) }
            ?.substring(1)
            ?.toLongOrNull(HEX_RADIX)
            ?.let { Color(OPAQUE or it) }
        ?: DefaultThemeSeed

/** 两档来源只决定 seed；else 兜底损坏/未知值，与无背景图的回退一致。不允许这里越过 Material 去设置 primary 或 surface。 */
internal fun themeSeed(
    settings: LauncherSettings,
    imageSeed: String?,
): Color =
    when (settings.themeColorSource) {
        "background" -> imageSeed?.let(::manualThemeSeed) ?: DefaultThemeSeed
        "manual" -> manualThemeSeed(settings.accentColor)
        else -> DefaultThemeSeed
    }

/** 非 Compose 入口供预览及对比度测试使用，与正式主题采用完全相同的规则。 */
internal fun themeColorScheme(
    seed: Color,
    dark: Boolean,
) = dynamicColorScheme(
    seedColor = seed,
    isDark = dark,
    style = PaletteStyle.TonalSpot,
    specVersion = ColorSpec.SpecVersion.SPEC_2021,
)

/** 保留既有预览调用边界；返回 Material 角色，不再直接返回原始 RGB。 */
fun resolveAccent(
    setting: String,
    dark: Boolean,
): Color = themeColorScheme(manualThemeSeed(setting), dark).primary
