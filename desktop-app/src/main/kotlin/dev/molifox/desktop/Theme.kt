// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责颜色、字号、间距和动态效果的统一主题。

package dev.molifox.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.ktx.animateColorScheme
import com.materialkolor.rememberDynamicColorScheme
import dev.molifox.core.AppearanceAssetService
import dev.molifox.core.LauncherSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val MutedGreen = Color(0xFF398571)

/** 关闭动画时页面切换与提示直接呈现，不做过渡。 */
val LocalAnimationsEnabled = staticCompositionLocalOf { true }

/** 「减少动态效果」开关：开启后所有过渡（悬停淡入、按下缩放、页面切换）直接跳到终值。 由外观页的「过渡动画」开关派生，默认 false。 */
val LocalReducedMotion = staticCompositionLocalOf { false }
val LocalCornerRadius = staticCompositionLocalOf { 6.dp }

/** 当前启动器设置：磨砂面板要读背景图路径、浓度与适配方式，避免每个调用点都传一遍。 */
val LocalLauncherSettings = compositionLocalOf { LauncherSettings() }

/** 统一过渡时长（毫秒）。页面与通用件复用同一组令牌，避免各处写 120/180/260。 */
object Motion {
    const val FAST = 120
    const val NORMAL = 180
}

/** 按「减少动态效果」返回过渡曲线；关闭动画时直接跳到终值。 */
@Composable
private fun motionSpec(durationMillis: Int): androidx.compose.animation.core.AnimationSpec<Float> =
    if (LocalReducedMotion.current) snap<Float>() else tween<Float>(durationMillis = durationMillis)

@Composable
fun MoliTheme(
    settings: LauncherSettings,
    assets: AppearanceAssetService? = null,
    videoAccent: String? = null,
    content: @Composable () -> Unit,
) {
    val dark =
        when (settings.themeMode) {
            "dark" -> true
            "light" -> false
            "system" -> isSystemInDarkTheme()
            else -> isSystemInDarkTheme()
        }
    val automatic by rememberBackgroundAccent(settings, dark, assets, videoAccent)
    val cornerRadius = settings.cornerRadiusDp.coerceIn(0, 24)
    val font by produceState<FontFamily>(FontFamily.Default, settings.fontChoice) {
        value = withContext(Dispatchers.IO) { launcherFontFamily(settings.fontChoice) }
    }
    val surfaceAlpha = if (settings.glassEnabled) settings.surfaceOpacity.coerceIn(.6f, 1f) else 1f
    // 种子只交给 Material，完整的前景、容器、中性色和语义色由同一算法生成。
    val targetColors =
        rememberDynamicColorScheme(
            seedColor = themeSeed(settings, automatic.hex),
            isDark = dark,
            style = PaletteStyle.TonalSpot,
            specVersion = ColorSpec.SpecVersion.SPEC_2021,
        )
    // 切换明暗时不把黑字与白字交叉淡化，避免中途两者都变成灰色；同模式换种子平滑过渡。
    val animated =
        key(dark) {
            animateColorScheme(targetColors, animationSpec = {
                if (settings.animationsEnabled) tween(Motion.NORMAL) else snap()
            })
        }
    val colors = animated.copy(surface = animated.surface.copy(alpha = surfaceAlpha))
    val density = LocalDensity.current
    MaterialTheme(
        colorScheme = colors,
        shapes =
            Shapes(
                extraSmall = RoundedCornerShape((cornerRadius * 2f / 3).dp),
                small = RoundedCornerShape((cornerRadius * 2f / 3).dp),
                medium = RoundedCornerShape(cornerRadius.dp),
                large = RoundedCornerShape((cornerRadius * 5f / 3).dp),
                extraLarge = RoundedCornerShape((cornerRadius * 5f / 3).dp),
            ),
        // 字号阶梯只有 6 档：18 / 16 / 14 / 13 / 12 / 11，行高按 1.35–1.45 倍给。
        typography =
            Typography(
                displayLarge =
                    MaterialTheme.typography.displayLarge.copy(
                        fontFamily = font,
                        fontSize = 18.sp,
                        lineHeight = 25.sp,
                    ),
                displayMedium =
                    MaterialTheme.typography.displayMedium.copy(
                        fontFamily = font,
                        fontSize = 18.sp,
                        lineHeight = 25.sp,
                    ),
                displaySmall =
                    MaterialTheme.typography.displaySmall.copy(
                        fontFamily = font,
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                    ),
                headlineSmall =
                    MaterialTheme.typography.headlineSmall.copy(
                        fontFamily = font,
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                    ),
                headlineLarge =
                    MaterialTheme.typography.headlineLarge.copy(
                        fontFamily = font,
                        fontSize = 18.sp,
                        lineHeight = 25.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                headlineMedium =
                    MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = font,
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                titleLarge =
                    MaterialTheme.typography.titleLarge.copy(
                        fontFamily = font,
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                titleMedium =
                    MaterialTheme.typography.titleMedium.copy(
                        fontFamily = font,
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                titleSmall =
                    MaterialTheme.typography.titleSmall.copy(
                        fontFamily = font,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                bodyLarge =
                    MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = font,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    ),
                bodyMedium =
                    MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = font,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    ),
                bodySmall =
                    MaterialTheme.typography.bodySmall.copy(
                        fontFamily = font,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    ),
                labelLarge =
                    MaterialTheme.typography.labelLarge.copy(
                        fontFamily = font,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                labelMedium =
                    MaterialTheme.typography.labelMedium.copy(
                        fontFamily = font,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                labelSmall =
                    MaterialTheme.typography.labelSmall.copy(
                        fontFamily = font,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.Medium,
                    ),
            ),
    ) {
        CompositionLocalProvider(
            LocalLauncherFont provides font,
            LocalTextStyle provides MaterialTheme.typography.bodyLarge.copy(fontFamily = font),
            LocalContentColor provides colors.onSurface,
            LocalAnimationsEnabled provides settings.animationsEnabled,
            LocalReducedMotion provides !settings.animationsEnabled,
            LocalCornerRadius provides settings.cornerRadiusDp.coerceIn(0, 24).dp,
            LocalLauncherSettings provides settings,
            LocalBackgroundAccent provides automatic,
            LocalDensity provides
                Density(
                    density.density * settings.uiScale.coerceIn(.85f, 1.2f),
                    density.fontScale * settings.fontScale.coerceIn(.85f, 1.25f),
                ),
            content = content,
        )
    }
}

val LocalLauncherFont = staticCompositionLocalOf<FontFamily> { FontFamily.Default }

/** 卡片描边、卡片内分隔线、标题栏底线使用统一颜色，浅色下也保留可见对比。 */
@Composable fun hairline(): Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .9f)

/** 当前是否为深色配色。以 surface 的相对亮度判断，主题预设、自定义强调色和玻璃透明度都不影响结果。 用于让控件填充在浅色下「压深一档」、深色下「提亮一档」。 */
@Composable fun isDarkScheme(): Boolean = MaterialTheme.colorScheme.surface.luminance() < .5f

/** 图标按钮：默认 30dp 触区、16dp 图标，悬停时 6% `onSurface` 圆底淡入；[size] 可放大触区（开始页账户卡用 32dp）。 */
@Composable
fun SmallIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    enabled: Boolean = true,
    size: Dp = Metrics.iconButton,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val hoverAlpha by
        animateFloatAsState(
            targetValue = if (hovered && enabled) .06f else 0f,
            animationSpec = motionSpec(Motion.FAST),
            label = "iconButtonHover",
        )
    IconButton(
        onClick = onClick,
        modifier =
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = hoverAlpha)),
        enabled = enabled,
        interactionSource = interaction,
    ) {
        Icon(
            icon,
            label,
            Modifier.size(16.dp),
            tint = if (enabled) tint else tint.copy(alpha = .38f),
        )
    }
}

/** 统一尺寸令牌：标题栏、页面、卡片、行与控件都从这里取。 采用紧凑密度（标题栏 52、行 44、控件 34、卡片圆角 6），保留樱花粉的柔和观感。 */
object Metrics {
    val titlebarHeight = 52.dp
    val pageHorizontal = 20.dp
    val pageVertical = 18.dp
    val cardPadding = 16.dp
    val cardRadius: Dp
        @Composable get() = LocalCornerRadius.current

    val innerRadius: Dp
        @Composable get() = LocalCornerRadius.current * (2f / 3)

    val dialogRadius: Dp
        @Composable get() = LocalCornerRadius.current * (5f / 3)

    val cardGap = 12.dp
    val sectionGap = 12.dp
    val rowHeight = 44.dp
    val rowHeightWithDetail = 52.dp
    val rowGap = 10.dp
    val controlHeight = 34.dp
    val iconButton = 30.dp
    val navItemHeight = 32.dp
}

/** 悬停反馈：桌面端鼠标悬停必须有反馈。 底色用 `animateFloatAsState` 在 120ms 内淡入淡出（关闭动画时直接跳到终值），按下仍交给 ripple。 */
@Composable
fun Modifier.hoverHighlight(
    shape: Shape = RoundedCornerShape(Metrics.innerRadius),
    enabled: Boolean = true,
    alpha: Float = .05f,
    interactionSource: MutableInteractionSource? = null,
): Modifier {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val progress by
        animateFloatAsState(
            targetValue = if (hovered && enabled) alpha else 0f,
            animationSpec = motionSpec(Motion.FAST),
            label = "hoverHighlight",
        )
    val tint = MaterialTheme.colorScheme.primary
    // 调用方已把同一 interactionSource 交给 clickable / selectable 时不再重复注册悬停。
    val hover =
        if (interactionSource == null) Modifier.hoverable(source, enabled = enabled) else Modifier
    return this.then(hover).background(tint.copy(alpha = progress), shape)
}

/** 行级悬停：颜色直接取 [rowHoverColor]，浅 / 深色主题各自有合适的透明度。 与 [hoverHighlight] 一样只做视觉反馈，不消费点击与涟漪。 */
@Composable
fun Modifier.rowHoverHighlight(
    shape: Shape = RoundedCornerShape(Metrics.innerRadius),
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
): Modifier {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val progress by
        animateFloatAsState(
            targetValue = if (hovered && enabled) 1f else 0f,
            animationSpec = motionSpec(Motion.FAST),
            label = "rowHover",
        )
    val tint = rowHoverColor()
    val hover =
        if (interactionSource == null) Modifier.hoverable(source, enabled = enabled) else Modifier
    return this.then(hover).background(tint.copy(alpha = tint.alpha * progress), shape)
}

/** 按下反馈：元素缩小到 0.98（70ms）。 只观察指针事件、不消费，因此不影响 clickable / selectable 的涟漪与窗口拖拽。 */
@Composable
fun Modifier.pressScale(pressedScale: Float = .98f): Modifier {
    var pressed by remember { mutableStateOf(false) }
    val scale by
        animateFloatAsState(
            targetValue = if (pressed) pressedScale else 1f,
            animationSpec = motionSpec(70),
            label = "pressScale",
        )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    pressed = true
                    waitForUpOrCancellation(pass = PointerEventPass.Initial)
                    pressed = false
                }
            }
        }
}

/** 键盘焦点环：2dp `primary`、向外扩张 2dp，仅在 InteractionSource 获得焦点时绘制。 需要放在 `.clip()` 之前，避免被裁剪。 */
@Composable
fun Modifier.focusRing(
    focused: Boolean,
    cornerRadius: Dp = Metrics.innerRadius,
    color: Color = MaterialTheme.colorScheme.primary,
): Modifier {
    if (!focused) return this
    return this.drawBehind {
        val offset = 2.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(-offset, -offset),
            size = Size(size.width + offset * 2, size.height + offset * 2),
            cornerRadius = CornerRadius(cornerRadius.toPx() + offset, cornerRadius.toPx() + offset),
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}
