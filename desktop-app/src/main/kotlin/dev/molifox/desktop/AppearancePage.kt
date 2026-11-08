// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责外观主题、背景、透明度与动画偏好。

package dev.molifox.desktop

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.onClick
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.AppearanceAssetService
import dev.molifox.core.LauncherSettings
import dev.molifox.core.LauncherState
import dev.molifox.core.effectiveBackground
import dev.molifox.core.effectiveVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

private const val WINDOW_BACKGROUND_ALPHA = .82f

typealias AppearanceChange = ((LauncherSettings) -> LauncherSettings) -> Unit

/** 窗口背景：按当前主题显示纯色底、静态图片或动态图片；浓度与整窗模糊对所有类型一致生效。 */
@Composable
fun LauncherBackdrop(
    settings: LauncherSettings,
    assets: AppearanceAssetService,
    dataDirectory: java.nio.file.Path,
    paused: Boolean = false,
    onVideoAccent: (String?) -> Unit = {},
) {
    var backdrop by remember { mutableStateOf<LoadedBackdrop?>(null) }
    val base = MaterialTheme.colorScheme.background
    val dark = isDarkScheme()
    val video =
        settings.effectiveVideo(dark)?.takeIf { path ->
            VideoRuntime.isInstalled(dataDirectory) &&
                runCatching { Files.isRegularFile(Path.of(path)) }.getOrDefault(false)
        }
    val clearVideoAccent by rememberUpdatedState(onVideoAccent)
    LaunchedEffect(video) { clearVideoAccent(null) }
    LaunchedEffect(settings.effectiveBackground(dark), settings.backgroundRevision, dark, video) {
        backdrop = null
        if (video != null) return@LaunchedEffect
        val bytes =
            withContext(Dispatchers.IO) {
                assets.readModeBackground(settings, dark)
            }
        backdrop = bytes?.let { loadBackdrop(it) }
    }
    Box(
        Modifier
            .fillMaxSize()
            .alpha(if (settings.windowTransparent) WINDOW_BACKGROUND_ALPHA else 1f)
            .then(
                if (settings.glassEnabled && settings.backgroundBlur > 0f) {
                    Modifier.blur(settings.backgroundBlur.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                } else {
                    Modifier
                },
            ),
    ) {
        Box(Modifier.fillMaxSize().background(base))
        val loaded = backdrop
        when {
            video != null ->
                VideoBackdrop(
                    dataDirectory = dataDirectory,
                    videoPath = video,
                    settings = settings,
                    paused = paused,
                    onAccent = onVideoAccent,
                    modifier = Modifier.fillMaxSize().alpha(settings.backgroundOpacity.coerceIn(0f, 1f)),
                )

            loaded is LoadedBackdrop.Animated ->
                AnimatedBackdrop(
                    animation = loaded,
                    modifier = Modifier,
                    alpha = settings.backgroundOpacity.coerceIn(0f, 1f),
                    contentScale = backgroundContentScale(settings.backgroundFit),
                    alignment = backgroundContentAlignment(settings.backgroundAlignment),
                    paused = paused,
                )

            loaded is LoadedBackdrop.Static ->
                Image(
                    loaded.bitmap,
                    null,
                    Modifier.fillMaxSize().alpha(settings.backgroundOpacity.coerceIn(0f, 1f)),
                    contentScale = backgroundContentScale(settings.backgroundFit),
                    alignment = backgroundContentAlignment(settings.backgroundAlignment),
                )
        }
    }
}

/** 设置 → 外观板块：子页（主题与效果 / 字体与缩放 / 页面布局）由设置页左栏在「外观」下面展开选择， 这里直接渲染当前子页的设置卡片。 */
@Composable
fun ColumnScope.AppearanceSection(
    state: LauncherState,
    actions: UiActions,
    onResetDefaults: () -> Unit,
    subSection: Int,
) {
    val settings = state.data.settings
    // 外观与其它分区一致：改动当场写入 store，MoliTheme / LauncherBackdrop 随之重绘，
    // 主题和窗口效果会随保存的设置即时更新。
    var saveError by remember { mutableStateOf<String?>(null) }
    val apply: AppearanceChange = { change -> saveError = applySetting(actions, change) }
    Column(Modifier.weight(1f).fillMaxWidth()) {
        saveError?.let { message ->
            Text(LocalStrings.current.t("settings.unsavedPrefix") + message, color = MaterialTheme.colorScheme.error)
        }
        Crossfade(
            subSection,
            Modifier.weight(1f).fillMaxWidth(),
            animationSpec = tween(if (LocalReducedMotion.current) 0 else 150),
            label = "appearanceSection",
        ) { index ->
            ScrollColumn(Modifier.fillMaxSize()) {
                when (index) {
                    0 -> ThemePreferences(settings, actions, apply)
                    1 -> FontPreferences(settings, actions, apply)
                    else -> PageLayoutPreferences(settings, apply)
                }
                Spacer(Modifier.height(6.dp))
                SettingsResetFooter(onResetDefaults)
            }
        }
    }
}

/** 外观子页标题：顺序与设置页左栏「外观」下面展开的子条目一致。 */
internal val appearanceSubSectionKeys =
    listOf("settings.appearance.sub.theme", "settings.appearance.sub.font", "settings.appearance.sub.layout")

@Composable
private fun ThemePreferences(
    settings: LauncherSettings,
    actions: UiActions,
    apply: AppearanceChange,
) {
    ThemeModeSection(settings, apply)
    AccentColorSection(settings, apply)
    BrandIconSection(settings, actions)
    CornerRadiusSection(settings, apply)
    BehaviorSection(settings, apply)
    GlassEffectSection(settings, apply)
    BackgroundImageSection(settings, actions, apply)
}

/** 已启用 / 未启用 Tag：分组卡片标题行右侧的状态标记。 */
@Composable
internal fun EnabledTag(enabled: Boolean) {
    val duration = if (LocalReducedMotion.current) 0 else Motion.FAST
    val color by
        animateColorAsState(
            if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            tween(duration),
            label = "enabledTag",
        )
    val strings = LocalStrings.current
    Tag(if (enabled) strings.t("settings.appearance.enabled") else strings.t("settings.appearance.disabled"), color)
}

/** 主题卡片：64dp 预览块 + 12sp 标题 + 11sp 说明，选中时边框 2dp 主题色。 */
@Composable
internal fun RowScope.ThemeOptionCard(
    id: String,
    title: String,
    detail: String,
    selected: Boolean,
    accentSetting: String,
    duration: Int,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    // 配色过渡每帧都会重组卡片；种子未变时复用预览，避免重复运行 HCT 配色生成。
    val lightScheme = remember(accentSetting) { themeColorScheme(manualThemeSeed(accentSetting), false) }
    val darkScheme = remember(accentSetting) { themeColorScheme(manualThemeSeed(accentSetting), true) }
    val border by
        animateColorAsState(
            if (selected) colors.primary else hairline(),
            tween(duration),
            label = "themeBorder",
        )
    val borderWidth by
        animateFloatAsState(if (selected) 2f else 1f, tween(duration), label = "themeBorderWidth")
    val fill by
        animateColorAsState(
            if (selected) colors.primaryContainer else Color.Transparent,
            tween(duration),
            label = "themeFill",
        )
    Column(
        Modifier
            .weight(1f)
            .pressScale()
            .clip(RoundedCornerShape(Metrics.cardRadius))
            .hoverHighlight(RoundedCornerShape(Metrics.cardRadius))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .background(fill)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(Metrics.cardRadius))
                .border(borderWidth.dp, border, RoundedCornerShape(Metrics.cardRadius)),
        ) {
            when (id) {
                "light" ->
                    ThemeSwatchPane(
                        lightScheme,
                        Modifier.fillMaxSize(),
                    )

                "dark" ->
                    ThemeSwatchPane(
                        darkScheme,
                        Modifier.fillMaxSize(),
                    )

                else ->
                    Row(Modifier.fillMaxSize()) {
                        ThemeSwatchPane(
                            lightScheme,
                            Modifier.weight(1f),
                        )
                        ThemeSwatchPane(
                            darkScheme,
                            Modifier.weight(1f),
                        )
                    }
            }
        }
        Text(
            title,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) colors.primary else colors.onSurface,
            maxLines = 1,
        )
        Text(detail, fontSize = 11.sp, color = colors.onSurfaceVariant, maxLines = 1)
    }
}

/** 预览也使用同一种子生成的完整 Material 角色，避免预览和实际主题各走一套。 */
@Composable
private fun ThemeSwatchPane(
    scheme: androidx.compose.material3.ColorScheme,
    modifier: Modifier,
) {
    val surface = scheme.surface
    val line = scheme.outlineVariant
    val accent = scheme.primary
    Column(
        modifier.background(surface).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent.copy(alpha = .9f)),
            )
            Box(
                Modifier
                    .height(3.dp)
                    .fillMaxWidth(.55f)
                    .clip(RoundedCornerShape(2.dp))
                    .background(line),
            )
        }
        Box(
            Modifier
                .height(3.dp)
                .fillMaxWidth(.8f)
                .clip(RoundedCornerShape(2.dp))
                .background(line),
        )
        Box(
            Modifier
                .width(34.dp)
                .height(11.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(accent),
        )
    }
}

/** 强调色 chip：色点 16dp + 12sp 文字，选中加 2dp 主题色边框与对勾。 */
@Composable
internal fun AccentChip(
    id: String,
    title: String,
    dark: Boolean,
    selected: Boolean,
    duration: Int,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val swatch = remember(id, dark) { resolveAccent(id, dark) }
    val border by
        animateColorAsState(
            if (selected) colors.primary else colors.outlineVariant.copy(alpha = .5f),
            tween(duration),
            label = "chipBorder",
        )
    val borderWidth by
        animateFloatAsState(if (selected) 2f else 1f, tween(duration), label = "chipBorderWidth")
    val fill by
        animateColorAsState(
            if (selected) selectedRowBackground() else Color.Transparent,
            tween(duration),
            label = "chipFill",
        )
    Row(
        Modifier
            .pressScale()
            .alpha(if (enabled) 1f else .38f)
            .clip(RoundedCornerShape(Metrics.innerRadius))
            .hoverHighlight(RoundedCornerShape(Metrics.innerRadius), enabled = enabled)
            .background(fill)
            .border(borderWidth.dp, border, RoundedCornerShape(Metrics.innerRadius))
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(swatch)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        )
        Text(
            title,
            fontSize = 12.sp,
            color = if (selected) colors.onPrimaryContainer else colors.onSurface,
        )
        if (selected) {
            Icon(
                Icons.Outlined.Check,
                null,
                Modifier.size(13.dp),
                colors.onPrimaryContainer,
            )
        }
    }
}

/** 紧凑滑块行：标题 + 右侧数值 Tag，下方轨道 4dp / 滑块 14dp。 拖动时只改本地值保证跟手，停手 300ms 后写入设置。 */
@Composable
fun PreferenceSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    enabled: Boolean = true,
    onApply: (Float) -> Unit,
) {
    val draft = rememberSliderDraft(value) { committed -> onApply(committed) }
    val strings = LocalStrings.current
    Column {
        SettingRow(title) { Tag(format(draft.floatValue)) }
        CompactSlider(
            draft.floatValue,
            { draft.floatValue = it },
            Modifier.fillMaxWidth().semantics {
                contentDescription = strings.t("settings.appearance.slider.a11y", title)
            },
            valueRange = range,
            enabled = enabled,
        )
    }
}
