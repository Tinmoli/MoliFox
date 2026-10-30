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
import dev.molifox.core.importAppearanceBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.nio.file.Files

/** 界面主题：浅色 / 深色 / 跟随系统三张预览卡。 */
@Composable
internal fun ThemeModeSection(
    settings: LauncherSettings,
    apply: AppearanceChange,
) {
    val theme = settings.themeMode
    val duration = if (LocalReducedMotion.current) 0 else Motion.FAST
    SettingsSection("界面主题", "选择界面明暗；切换后立即生效。") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ThemeOptionCard(
                "light",
                "浅色",
                "始终使用浅色界面",
                theme == "light",
                settings.accentColor,
                duration,
            ) {
                apply { it.copy(themeMode = "light") }
            }
            ThemeOptionCard(
                "dark",
                "深色",
                "始终使用深色界面",
                theme == "dark",
                settings.accentColor,
                duration,
            ) {
                apply { it.copy(themeMode = "dark") }
            }
            ThemeOptionCard(
                "system",
                "跟随系统",
                "跟随操作系统外观",
                theme == "system",
                settings.accentColor,
                duration,
            ) {
                apply { it.copy(themeMode = "system") }
            }
        }
    }
}

/** 主题颜色：预设强调色 + 自定义十六进制输入。 */
@Composable
internal fun AccentColorSection(
    settings: LauncherSettings,
    apply: AppearanceChange,
) {
    val duration = if (LocalReducedMotion.current) 0 else Motion.FAST
    SettingsSection("主题颜色", "默认樱花粉；可选择预设颜色，或输入十六进制值自定义。") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            accentPresetTitles.forEach { (id, title) ->
                AccentChip(
                    id = id,
                    title = title,
                    dark = isDarkScheme(),
                    selected = settings.accentColor == id,
                    duration = duration,
                ) {
                    apply { it.copy(accentColor = id) }
                }
            }
        }
        Text(
            (accentPresetTitles[settings.accentColor] ?: "自定义颜色") + " · 统一界面配色",
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
                    "格式需为 #RRGGBB",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 11.sp,
                )
            } else {
                Text(
                    "输入 #RRGGBB 作为自定义强调色",
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
    val brandCustom = settings.brandIconPath.isNotBlank()
    SettingsSection(
        "左上角图标",
        "只替换启动器左上角的品牌标识；标题栏与准备窗口仍使用内置图标。",
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
                BrandMark(Modifier.size(24.dp))
            }
            SecondaryButton({
                brandError = null
                actions.run {
                    val source =
                        actions
                            .choose("选择左上角图标", extensions = listOf("png", "jpg", "jpeg"))
                            .firstOrNull() ?: return@run
                    val result =
                        runCatching {
                            importBrandIcon(actions.controller.store.dataDirectory, source)
                        }
                    withContext(Dispatchers.Swing) {
                        val imported = result.getOrNull()
                        brandError =
                            if (imported == null) {
                                result.exceptionOrNull()?.message ?: "无法使用这张图片"
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
                Text("选择图片")
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
                        removeBrandIcon(actions.controller.store.dataDirectory, stored)
                    }
                }) { Text("恢复默认") }
            }
        }
        brandError?.let { message ->
            Spacer(Modifier.height(6.dp))
            Text(message, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
        }
        Spacer(Modifier.height(8.dp))
        MutedText("支持 PNG / JPEG，最大 2 MB、边长 16–1024 像素；图片副本保存在 MoliFox/appearance/branding。")
    }
}

/** 卡片与控件的圆角统一随主题更新。 */
@Composable
internal fun CornerRadiusSection(
    settings: LauncherSettings,
    apply: AppearanceChange,
) {
    SettingsSection("界面圆角", "统一调整卡片、按钮与输入框。", contentGap = 0.dp) {
        PreferenceSlider(
            "界面圆角",
            settings.cornerRadiusDp.toFloat(),
            0f..24f,
            { "${it.toInt()} dp" },
        ) { value ->
            apply { it.copy(cornerRadiusDp = value.toInt()) }
        }
        MutedText("0 为直角；卡片、按钮、输入框与对话框按统一比例实时调整。")
    }
}

/** 界面行为：标题栏半透明、窗口透明、过渡动画与默认游戏窗口。 */
@Composable
internal fun BehaviorSection(
    settings: LauncherSettings,
    apply: AppearanceChange,
) {
    SettingsSection("界面行为", "标题栏、窗口透明与动画效果即时生效。", contentGap = 0.dp) {
        SettingRow("标题栏半透明", "让背景透过顶部标题栏，无需开启玻璃效果") {
            CompactSwitch(
                settings.titlebarTransparent,
                { value -> apply { it.copy(titlebarTransparent = value) } },
            )
        }
        SettingDivider()
        SettingRow("窗口透明", "整窗半透明，即时生效；需要系统支持窗口透明") {
            CompactSwitch(
                settings.windowTransparent,
                { value -> apply { it.copy(windowTransparent = value) } },
                Modifier.semantics { contentDescription = "窗口透明开关" },
            )
        }
        SettingDivider()
        SettingRow("过渡动画", "关闭后页面切换与提示不再淡入淡出") {
            CompactSwitch(
                settings.animationsEnabled,
                { value -> apply { it.copy(animationsEnabled = value) } },
            )
        }
        SettingDivider()
        SettingRow("默认游戏窗口", "实例的窗口设置可以覆盖这里的默认值") {
            Box(Modifier.width(180.dp)) {
                SimpleDropdown(
                    settings.gameWindowMode,
                    listOf(
                        "launcher" to "与启动器一样大",
                        "fullscreen" to "全屏",
                        "custom" to "自定义分辨率",
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
    SettingsSection(
        "透明与磨砂",
        "让背景透过卡片，保留清晰的文字与操作层级。",
        action = { EnabledTag(settings.glassEnabled) },
        contentGap = 0.dp,
    ) {
        SettingRow("玻璃效果", "关闭后使用实色面板") {
            CompactSwitch(
                settings.glassEnabled,
                { value -> apply { it.copy(glassEnabled = value) } },
                Modifier.semantics { contentDescription = "玻璃效果开关" },
            )
        }
        SettingDivider()
        // 关闭玻璃时保留滑块并置灰，避免布局跳变与「设置丢了」的错觉。
        PreferenceSlider(
            "面板不透明度",
            settings.surfaceOpacity,
            .6f..1f,
            { (it * 100).toInt().toString() + "%" },
            enabled = settings.glassEnabled,
        ) { value ->
            apply { it.copy(surfaceOpacity = value) }
        }
        SettingDivider()
        PreferenceSlider(
            "背景磨砂强度",
            settings.backgroundBlur,
            0f..40f,
            { it.toInt().toString() },
            enabled = settings.glassEnabled,
        ) { value ->
            apply { it.copy(backgroundBlur = value) }
        }
        if (!settings.glassEnabled) MutedText("需开启玻璃效果后这些滑块才生效。", Modifier.padding(top = 4.dp))
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
    val backgroundOn = settings.backgroundPath.isNotBlank()
    SettingsSection(
        "窗口背景",
        "图片副本保存在 MoliFox/appearance，可搭配透明与磨砂效果。",
        action = { EnabledTag(backgroundOn) },
        // 卡片内的四个行块之间用 1dp 细线分隔：行数 4 → 分隔线 3 条，首末不加。
        contentGap = 0.dp,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton({
                actions.run {
                    val source =
                        actions
                            .choose("选择窗口背景", extensions = listOf("png", "jpg", "jpeg"))
                            .firstOrNull()
                    if (source != null) {
                        val imported =
                            importAppearanceBackground(
                                actions.controller.store.dataDirectory,
                                source,
                            )
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
                Text("选择背景图片")
            }
            if (backgroundOn) SecondaryButton({ removing = true }) { Text("移除背景") }
        }
        SettingDivider()
        PreferenceSlider(
            "背景浓度",
            settings.backgroundOpacity,
            0f..1f,
            { (it * 100).toInt().toString() + "%" },
            enabled = backgroundOn,
        ) { value ->
            apply { it.copy(backgroundOpacity = value) }
        }
        SettingDivider()
        SettingRow("图片适配", "填满可能裁边；完整显示保留全图；拉伸可能改变比例") {
            SimpleDropdown(
                settings.backgroundFit,
                listOf("crop" to "填满并裁切", "fit" to "完整显示", "stretch" to "拉伸铺满"),
                { value -> apply { it.copy(backgroundFit = value) } },
                Modifier.width(170.dp),
                enabled = true,
            )
        }
        SettingDivider()
        SettingRow("图片位置", "控制裁切中心或完整图片在窗口中的对齐位置") {
            SimpleDropdown(
                settings.backgroundAlignment,
                listOf(
                    "topLeft" to "左上",
                    "topCenter" to "居中靠上",
                    "topRight" to "右上",
                    "centerLeft" to "靠左居中",
                    "center" to "居中",
                    "centerRight" to "靠右居中",
                    "bottomLeft" to "左下",
                    "bottomCenter" to "居中靠下",
                    "bottomRight" to "右下",
                ),
                { value -> apply { it.copy(backgroundAlignment = value) } },
                Modifier.width(170.dp),
                enabled = settings.backgroundFit != "stretch",
            )
        }
        if (!backgroundOn) MutedText("可以先设置图片适配和位置，选择背景图片后生效。", Modifier.padding(top = 4.dp))
        if (settings.backgroundFit == "stretch") MutedText("拉伸铺满时没有空白或裁切区域；切换为填满或完整显示后可调整位置。")
    }
    // 移除背景会删除 MoliFox/appearance 里的图片副本，先确认再执行。
    if (removing) {
        Modal(
            "移除背景？",
            "图片副本会从 MoliFox/appearance 删除，无法从启动器恢复。",
            460.dp,
            { removing = false },
        ) {
            MutedText("原图若保存在其它位置不受影响；移除后窗口背景恢复为纯色。")
            DialogActions(
                confirmText = "移除背景",
                onConfirm = {
                    actions.run {
                        applySetting(actions) { it.copy(backgroundPath = "") }
                        Files.deleteIfExists(
                            actions.controller.store.directories.backgrounds.resolve(
                                "background.img",
                            ),
                        )
                    }
                    removing = false
                },
                cancelText = "保留",
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
