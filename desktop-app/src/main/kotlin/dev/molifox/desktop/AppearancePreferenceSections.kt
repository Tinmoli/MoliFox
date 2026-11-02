// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 外观 → 主题与效果子页的各分组卡片，按「主题 / 颜色 / 图标 / 字体 / 行为 / 玻璃 / 背景」拆开。

package dev.molifox.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.LauncherSettings
import dev.molifox.core.i18n.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

/** 界面主题：浅色 / 深色 / 跟随系统三张预览卡。 */
@Composable
internal fun ThemeModeSection(
    settings: LauncherSettings,
    apply: AppearanceChange,
) {
    val theme = settings.themeMode
    val strings = LocalStrings.current
    val duration = if (LocalReducedMotion.current) 0 else Motion.FAST
    SettingsSection(
        strings.t("settings.appearance.theme.title"),
        strings.t("settings.appearance.theme.detail"),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ThemeOptionCard(
                "light",
                strings.t("settings.appearance.theme.light.title"),
                strings.t("settings.appearance.theme.light.detail"),
                theme == "light",
                settings.accentColor,
                duration,
            ) {
                apply { it.copy(themeMode = "light") }
            }
            ThemeOptionCard(
                "dark",
                strings.t("settings.appearance.theme.dark.title"),
                strings.t("settings.appearance.theme.dark.detail"),
                theme == "dark",
                settings.accentColor,
                duration,
            ) {
                apply { it.copy(themeMode = "dark") }
            }
            ThemeOptionCard(
                "system",
                strings.t("settings.appearance.theme.system.title"),
                strings.t("settings.appearance.theme.system.detail"),
                theme == "system",
                settings.accentColor,
                duration,
            ) {
                apply { it.copy(themeMode = "system") }
            }
        }
    }
}

/** 强调色预设标题按 id 从 settings bundle 取，颜色本身仍在 Theme.kt 定义。 */
internal fun accentPresetTitle(
    strings: Strings,
    id: String,
): String =
    if (accentPresets.any { it.first == id }) {
        strings.t("settings.appearance.accent.$id")
    } else {
        strings.t("settings.appearance.accent.custom")
    }

/** 主题颜色：预设强调色 + 自定义十六进制输入。 */
@Composable
internal fun AccentColorSection(
    settings: LauncherSettings,
    apply: AppearanceChange,
) {
    val duration = if (LocalReducedMotion.current) 0 else Motion.FAST
    val strings = LocalStrings.current
    SettingsSection(
        strings.t("settings.appearance.accent.title"),
        strings.t("settings.appearance.accent.detail"),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            accentPresets.forEach { (id, _) ->
                AccentChip(
                    id = id,
                    title = strings.t("settings.appearance.accent.$id"),
                    dark = isDarkScheme(),
                    selected = settings.accentColor == id,
                    duration = duration,
                ) {
                    apply { it.copy(accentColor = id) }
                }
            }
        }
        Text(
            strings.t("settings.appearance.accent.current", accentPresetTitle(strings, settings.accentColor)),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )
        var customHex by
            remember(settings.accentColor) {
                mutableStateOf(
                    if (Regex("^#[0-9A-Fa-f]{6}$").matches(settings.accentColor)) {
                        settings.accentColor
                    } else {
                        ""
                    },
                )
            }
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(200.dp)) {
                CompactTextField(
                    customHex,
                    { value ->
                        customHex = value
                        if (Regex("^#[0-9A-Fa-f]{6}$").matches(value)) {
                            apply { it.copy(accentColor = value.uppercase()) }
                        }
                    },
                    placeholder = "#RRGGBB",
                )
            }
            if (customHex.isNotEmpty() && !Regex("^#[0-9A-Fa-f]{6}$").matches(customHex)) {
                Text(
                    strings.t("settings.appearance.accent.invalidFormat"),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 11.sp,
                )
            } else {
                Text(
                    strings.t("settings.appearance.accent.customHint"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/** 左上角图标：只改主窗口标题栏左侧的品牌标识。 */
@Composable
internal fun BrandIconSection(
    settings: LauncherSettings,
    actions: UiActions,
) {
    // 左上角图标：只改主窗口标题栏左侧的品牌标识，标题栏图标、任务栏与准备窗口仍用内置图标。
    var brandError by remember { mutableStateOf<String?>(null) }
    val strings = LocalStrings.current
    val brandCustom = settings.brandIconPath.isNotBlank()
    SettingsSection(
        strings.t("settings.appearance.brand.title"),
        strings.t("settings.appearance.brand.detail"),
        action = { EnabledTag(brandCustom) },
        contentGap = 0.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(Metrics.innerRadius))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = .07f))
                    .border(1.dp, hairline(), RoundedCornerShape(Metrics.innerRadius)),
                contentAlignment = Alignment.Center,
            ) {
                BrandMark(actions.controller.appearance, Modifier.size(24.dp))
            }
            SecondaryButton({
                brandError = null
                actions.run {
                    val source =
                        actions
                            .choose(
                                strings.t("settings.appearance.brand.choose"),
                                extensions = listOf("png", "jpg", "jpeg"),
                            ).firstOrNull() ?: return@run
                    val result =
                        runCatching {
                            actions.controller.appearance.importBrandIcon(source)
                        }
                    withContext(Dispatchers.Swing) {
                        val imported = result.getOrNull()
                        brandError =
                            if (imported == null) {
                                result.exceptionOrNull()?.message
                                    ?: strings.t("settings.appearance.brand.failed")
                            } else {
                                applySetting(actions) {
                                    it.copy(
                                        brandIconPath = imported.toString(),
                                        brandIconRevision = System.currentTimeMillis(),
                                    )
                                }
                            }
                    }
                }
            }) {
                Icon(Icons.Outlined.Image, null, Modifier.size(16.dp))
                Spacer(Modifier.width(7.dp))
                Text(strings.t("settings.appearance.brand.pick"))
            }
            if (brandCustom) {
                SecondaryButton({
                    brandError = null
                    actions.run {
                        val stored = actions.controller.store.data.settings.brandIconPath
                        applySetting(actions) {
                            it.copy(
                                brandIconPath = "",
                                brandIconRevision = System.currentTimeMillis(),
                            )
                        }
                        actions.controller.appearance.removeBrandIcon(stored)
                    }
                }) { Text(strings.t("settings.appearance.brand.reset")) }
            }
        }
        brandError?.let { message ->
            Spacer(Modifier.height(6.dp))
            Text(message, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
        }
        Spacer(Modifier.height(8.dp))
        MutedText(strings.t("settings.appearance.brand.hint"))
    }
}

/** 卡片与控件的圆角统一随主题更新。 */
@Composable
internal fun CornerRadiusSection(
    settings: LauncherSettings,
    apply: AppearanceChange,
) {
    val strings = LocalStrings.current
    SettingsSection(
        strings.t("settings.appearance.corner.title"),
        strings.t("settings.appearance.corner.detail"),
        contentGap = 0.dp,
    ) {
        PreferenceSlider(
            strings.t("settings.appearance.corner.title"),
            settings.cornerRadiusDp.toFloat(),
            0f..24f,
            { "${it.toInt()} dp" },
        ) { value ->
            apply { it.copy(cornerRadiusDp = value.toInt()) }
        }
        MutedText(strings.t("settings.appearance.corner.hint"))
    }
}

/** 界面行为：标题栏半透明、窗口透明、过渡动画与默认游戏窗口。 */
@Composable
internal fun BehaviorSection(
    settings: LauncherSettings,
    apply: AppearanceChange,
) {
    val strings = LocalStrings.current
    SettingsSection(
        strings.t("settings.appearance.behavior.title"),
        strings.t("settings.appearance.behavior.detail"),
        contentGap = 0.dp,
    ) {
        SettingRow(
            strings.t("settings.appearance.behavior.titlebar.title"),
            strings.t("settings.appearance.behavior.titlebar.detail"),
        ) {
            CompactSwitch(
                settings.titlebarTransparent,
                { value -> apply { it.copy(titlebarTransparent = value) } },
            )
        }
        SettingDivider()
        SettingRow(
            strings.t("settings.appearance.behavior.windowTransparent.title"),
            strings.t("settings.appearance.behavior.windowTransparent.detail"),
        ) {
            CompactSwitch(
                settings.windowTransparent,
                { value -> apply { it.copy(windowTransparent = value) } },
                Modifier.semantics {
                    contentDescription = strings.t("settings.appearance.behavior.windowTransparent.switch")
                },
            )
        }
        SettingDivider()
        SettingRow(
            strings.t("settings.appearance.behavior.animation.title"),
            strings.t("settings.appearance.behavior.animation.detail"),
        ) {
            CompactSwitch(
                settings.animationsEnabled,
                { value -> apply { it.copy(animationsEnabled = value) } },
            )
        }
        SettingDivider()
        SettingRow(
            strings.t("settings.appearance.behavior.gameWindow.title"),
            strings.t("settings.appearance.behavior.gameWindow.detail"),
        ) {
            Box(Modifier.width(180.dp)) {
                SimpleDropdown(
                    settings.gameWindowMode,
                    listOf(
                        "launcher" to strings.t("settings.appearance.behavior.gameWindow.launcher"),
                        "fullscreen" to strings.t("settings.appearance.behavior.gameWindow.fullscreen"),
                        "custom" to strings.t("settings.appearance.behavior.gameWindow.custom"),
                    ),
                    { value -> apply { it.copy(gameWindowMode = value) } },
                )
            }
        }
    }
}

/** 透明与磨砂：玻璃效果开关与两个连带滑块。 */
@Composable
internal fun GlassEffectSection(
    settings: LauncherSettings,
    apply: AppearanceChange,
) {
    val strings = LocalStrings.current
    SettingsSection(
        strings.t("settings.appearance.glass.title"),
        strings.t("settings.appearance.glass.detail"),
        action = { EnabledTag(settings.glassEnabled) },
        contentGap = 0.dp,
    ) {
        SettingRow(
            strings.t("settings.appearance.glass.toggle.title"),
            strings.t("settings.appearance.glass.toggle.detail"),
        ) {
            CompactSwitch(
                settings.glassEnabled,
                { value -> apply { it.copy(glassEnabled = value) } },
                Modifier.semantics {
                    contentDescription = strings.t("settings.appearance.glass.toggle.switch")
                },
            )
        }
        SettingDivider()
        // 关闭玻璃时保留滑块并置灰，避免布局跳变与「设置丢了」的错觉。
        PreferenceSlider(
            strings.t("settings.appearance.glass.opacity"),
            settings.surfaceOpacity,
            .6f..1f,
            { (it * 100).toInt().toString() + "%" },
            enabled = settings.glassEnabled,
        ) { value ->
            apply { it.copy(surfaceOpacity = value) }
        }
        SettingDivider()
        PreferenceSlider(
            strings.t("settings.appearance.glass.blur"),
            settings.backgroundBlur,
            0f..40f,
            { it.toInt().toString() },
            enabled = settings.glassEnabled,
        ) { value ->
            apply { it.copy(backgroundBlur = value) }
        }
        if (!settings.glassEnabled) {
            MutedText(strings.t("settings.appearance.glass.hint"), Modifier.padding(top = 4.dp))
        }
    }
}

/** 窗口背景：图片选择、移除确认与浓度 / 适配 / 位置。 */
@Composable
internal fun BackgroundImageSection(
    settings: LauncherSettings,
    actions: UiActions,
    apply: AppearanceChange,
) {
    var removing by remember { mutableStateOf(false) }
    val strings = LocalStrings.current
    val backgroundOn = settings.backgroundPath.isNotBlank()
    SettingsSection(
        strings.t("settings.appearance.background.title"),
        strings.t("settings.appearance.background.detail"),
        action = { EnabledTag(backgroundOn) },
        // 卡片内的四个行块之间用 1dp 细线分隔：行数 4 → 分隔线 3 条，首末不加。
        contentGap = 0.dp,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton({
                actions.run {
                    val source =
                        actions
                            .choose(
                                strings.t("settings.appearance.background.choose"),
                                extensions = listOf("png", "jpg", "jpeg"),
                            ).firstOrNull()
                    if (source != null) {
                        val imported =
                            actions.controller.appearance.importBackground(source)
                        applySetting(actions) {
                            it.copy(
                                backgroundPath = imported.toString(),
                                backgroundRevision = System.currentTimeMillis(),
                            )
                        }
                    }
                }
            }) {
                Icon(Icons.Outlined.Image, null, Modifier.size(16.dp))
                Spacer(Modifier.width(7.dp))
                Text(strings.t("settings.appearance.background.pick"))
            }
            if (backgroundOn) {
                SecondaryButton({ removing = true }) {
                    Text(strings.t("settings.appearance.background.remove"))
                }
            }
        }
        SettingDivider()
        PreferenceSlider(
            strings.t("settings.appearance.background.opacity"),
            settings.backgroundOpacity,
            0f..1f,
            { (it * 100).toInt().toString() + "%" },
            enabled = backgroundOn,
        ) { value ->
            apply { it.copy(backgroundOpacity = value) }
        }
        SettingDivider()
        SettingRow(
            strings.t("settings.appearance.background.fit.title"),
            strings.t("settings.appearance.background.fit.detail"),
        ) {
            SimpleDropdown(
                settings.backgroundFit,
                listOf(
                    "crop" to strings.t("settings.appearance.background.fit.crop"),
                    "fit" to strings.t("settings.appearance.background.fit.fit"),
                    "stretch" to strings.t("settings.appearance.background.fit.stretch"),
                ),
                { value -> apply { it.copy(backgroundFit = value) } },
                Modifier.width(170.dp),
                enabled = true,
            )
        }
        SettingDivider()
        SettingRow(
            strings.t("settings.appearance.background.position.title"),
            strings.t("settings.appearance.background.position.detail"),
        ) {
            SimpleDropdown(
                settings.backgroundAlignment,
                listOf(
                    "topLeft" to strings.t("settings.appearance.background.position.topLeft"),
                    "topCenter" to strings.t("settings.appearance.background.position.topCenter"),
                    "topRight" to strings.t("settings.appearance.background.position.topRight"),
                    "centerLeft" to strings.t("settings.appearance.background.position.centerLeft"),
                    "center" to strings.t("settings.appearance.background.position.center"),
                    "centerRight" to strings.t("settings.appearance.background.position.centerRight"),
                    "bottomLeft" to strings.t("settings.appearance.background.position.bottomLeft"),
                    "bottomCenter" to strings.t("settings.appearance.background.position.bottomCenter"),
                    "bottomRight" to strings.t("settings.appearance.background.position.bottomRight"),
                ),
                { value -> apply { it.copy(backgroundAlignment = value) } },
                Modifier.width(170.dp),
                enabled = settings.backgroundFit != "stretch",
            )
        }
        if (!backgroundOn) {
            MutedText(strings.t("settings.appearance.background.note.before"), Modifier.padding(top = 4.dp))
        }
        if (settings.backgroundFit == "stretch") {
            MutedText(strings.t("settings.appearance.background.note.stretch"))
        }
    }
    // 移除背景会删除 MoliFox/appearance 里的图片副本，先确认再执行。
    if (removing) {
        Modal(
            strings.t("settings.appearance.background.remove.title"),
            strings.t("settings.appearance.background.remove.detail"),
            460.dp,
            { removing = false },
        ) {
            MutedText(strings.t("settings.appearance.background.remove.note"))
            DialogActions(
                confirmText = strings.t("settings.appearance.background.remove"),
                onConfirm = {
                    actions.run {
                        applySetting(actions) { it.copy(backgroundPath = "") }
                        actions.controller.appearance.removeBackground()
                    }
                    removing = false
                },
                cancelText = strings.t("settings.appearance.background.remove.cancel"),
                onCancel = { removing = false },
                danger = true,
            )
        }
    }
}

/** 背景图适配模式 → Compose 缩放方式；未知值按填满裁切。 */
internal fun backgroundContentScale(fit: String): ContentScale =
    when (fit) {
        "fit" -> ContentScale.Fit
        "stretch" -> ContentScale.FillBounds
        else -> ContentScale.Crop
    }

/** 背景图对齐位置 → Compose 对齐方式；未知值居中。 */
internal fun backgroundContentAlignment(alignment: String): Alignment =
    when (alignment) {
        "topLeft" -> Alignment.TopStart
        "topCenter" -> Alignment.TopCenter
        "topRight" -> Alignment.TopEnd
        "centerLeft" -> Alignment.CenterStart
        "centerRight" -> Alignment.CenterEnd
        "bottomLeft" -> Alignment.BottomStart
        "bottomCenter" -> Alignment.BottomCenter
        "bottomRight" -> Alignment.BottomEnd
        else -> Alignment.Center
    }
