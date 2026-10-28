// 负责颜色、字号、间距和动态效果的统一主题。

package dev.molifox.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.graphics.lerp
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
import dev.molifox.core.LauncherSettings

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
    const val fast = 120
    const val normal = 180
    const val slow = 260
}

/** 按「减少动态效果」返回过渡曲线；关闭动画时直接跳到终值。 */
@Composable
private fun motionSpec(durationMillis: Int): androidx.compose.animation.core.AnimationSpec<Float> =
    if (LocalReducedMotion.current) snap<Float>() else tween<Float>(durationMillis = durationMillis)

/** 主题颜色预设：第一项是浅色主题用色（较深，保证白底对比度）， 第二项是深色主题用色（较浅）。默认樱花粉，其余为用户可选预设。 */
val accentPresets: List<Pair<String, Pair<Color, Color>>> =
    listOf(
        "sakura" to (Color(0xFFB94E7B) to Color(0xFFF2B2CF)),
        "ocean" to (Color(0xFF2C6E9E) to Color(0xFF9CC9E8)),
        "mint" to (Color(0xFF2E7D63) to Color(0xFFA8DCC6)),
        "amber" to (Color(0xFFB27427) to Color(0xFFEFC78F)),
        "lavender" to (Color(0xFF6E5AA0) to Color(0xFFC9BAEC)),
        "graphite" to (Color(0xFF565B66) to Color(0xFFB9BEC8)),
    )

val accentPresetTitles: Map<String, String> =
    mapOf(
        "sakura" to "樱花粉",
        "ocean" to "海盐蓝",
        "mint" to "薄荷绿",
        "amber" to "琥珀橙",
        "lavender" to "薰衣草紫",
        "graphite" to "石墨灰",
    )

private fun parseHexColor(value: String): Color? {
    if (!Regex("^#[0-9A-Fa-f]{6}$").matches(value)) return null
    return Color(0xFF000000L or value.substring(1).toLong(16))
}

/** 自定义颜色在浅色主题压深、深色主题提亮，保证两种模式下都有足够对比度。 */
fun resolveAccent(setting: String, dark: Boolean): Color {
    val preset = accentPresets.firstOrNull { it.first == setting }
    if (preset != null) return if (dark) preset.second.second else preset.second.first
    val custom = parseHexColor(setting) ?: return resolveAccent("sakura", dark)
    return if (dark) lerp(custom, Color.White, .45f) else lerp(custom, Color.Black, .16f)
}

@Composable
fun MoliTheme(settings: LauncherSettings, content: @Composable () -> Unit) {
    val dark =
        when (settings.themeMode) {
            "dark" -> true
            "light" -> false
            "system" -> isSystemInDarkTheme()
            else -> settings.darkTheme
        }
    val primary = resolveAccent(settings.accentColor, dark)
    val cornerRadius = settings.cornerRadiusDp.coerceIn(0, 24)
    val font = remember(settings.fontChoice) { launcherFontFamily(settings.fontChoice) }
    val surfaceAlpha = if (settings.glassEnabled) settings.surfaceOpacity.coerceIn(.6f, 1f) else 1f
    // 背景与卡片使用平面色，主题色只出现在选中态、主按钮、开关、滑块与进度上。
    val colors =
        if (dark)
            darkColorScheme(
                primary = primary,
                onPrimary = Color(0xFF1B1017),
                primaryContainer = primary.copy(alpha = .16f),
                onPrimaryContainer = lerp(primary, Color.White, .32f),
                secondary = primary,
                secondaryContainer = primary.copy(alpha = .16f),
                onSecondaryContainer = primary,
                background = Color(0xFF1B181C),
                onBackground = Color(0xFFEFE8EE),
                surface = Color(0xFF262028).copy(alpha = surfaceAlpha),
                onSurface = Color(0xFFEFE8EE),
                surfaceVariant = Color(0xFF302A32),
                surfaceContainerLow = Color(0xFF221D21),
                surfaceContainer = Color(0xFF2B2529),
                surfaceContainerHigh = Color(0xFF322C32),
                surfaceContainerHighest = Color(0xFF3B343A),
                onSurfaceVariant = Color(0xFFA9A0A7),
                outline = Color(0xFF4A4148),
                outlineVariant = Color(0xFF3A343A),
                inverseSurface = Color(0xFFF3EAF0),
                inverseOnSurface = Color(0xFF2E2830),
                error = Color(0xFFFFABB2),
            )
        else
            lightColorScheme(
                primary = primary,
                onPrimary = Color.White,
                primaryContainer = primary.copy(alpha = .10f),
                onPrimaryContainer = lerp(primary, Color.Black, .48f),
                secondary = lerp(primary, Color.Black, .10f),
                secondaryContainer = primary.copy(alpha = .10f),
                onSecondaryContainer = primary,
                background = Color(0xFFFBF7F9),
                onBackground = Color(0xFF3A333A),
                surface = Color(0xFFFFFFFF).copy(alpha = surfaceAlpha),
                onSurface = Color(0xFF3A333A),
                surfaceVariant = Color(0xFFF3EEF1),
                surfaceContainerLow = Color(0xFFFAF5F8),
                surfaceContainer = Color(0xFFF6F1F4),
                surfaceContainerHigh = Color(0xFFF2ECEF),
                surfaceContainerHighest = Color(0xFFEDE6EA),
                onSurfaceVariant = Color(0xFF6B646B),
                outline = Color(0xFFD9CFD5),
                outlineVariant = Color(0xFFCFC2C9),
                inverseSurface = Color(0xFF342D33),
                inverseOnSurface = Color(0xFFF7F1F4),
                error = Color(0xFFB3384A),
            )
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

/**
 * 输入框 / 下拉框 / 搜索框的**实色填充**：surfaceVariant 掺入少量主题色。 浅色主题下比白卡片深一档（#ECDBE3 级别），深色主题下比页面底色亮一档（#4F404B
 * 级别）； 悬停与聚焦时再向主题色靠 6%–18%，不改变尺寸与圆角。PCL2 用半透明白 + 浅主题色边框（Application.xaml:243/259），HMCL 用
 * surface-container-highest 实色填充 （root.css:951/1531）；这里取 HMCL 的「实色、比页面深一档」，边框取 PCL2 的可见细边。
 */
@Composable
fun controlBackground(focused: Boolean = false, hovered: Boolean = false): Color {
    val colors = MaterialTheme.colorScheme
    val dark = isDarkScheme()
    val base = lerp(colors.surfaceVariant, colors.primary, if (dark) .16f else .14f)
    val lift =
        when {
            focused -> if (dark) .18f else .12f
            hovered -> if (dark) .10f else .06f
            else -> 0f
        }
    return if (lift == 0f) base else lerp(base, colors.primary, lift)
}

/** 控件边框颜色：默认把 outlineVariant 压向 onSurfaceVariant（浅色 #BFB7BC 级别），聚焦用主题色。 */
@Composable
fun controlBorderColor(focused: Boolean = false): Color {
    val colors = MaterialTheme.colorScheme
    if (focused) return colors.primary.copy(alpha = .78f)
    val dark = isDarkScheme()
    return lerp(
            colors.outlineVariant,
            colors.onSurfaceVariant,
            if (dark) .45f else .30f,
        )
        .copy(alpha = .85f)
}

/** 控件 1dp 边框：与 [controlBorderColor] 配套。 */
@Composable
fun controlBorder(focused: Boolean = false): BorderStroke =
    BorderStroke(1.dp, controlBorderColor(focused))

/** 列表行悬停底色：主题色 7%（浅）/ 12%（深）。 */
@Composable
fun rowHoverColor(): Color =
    MaterialTheme.colorScheme.primary.copy(alpha = if (isDarkScheme()) .12f else .07f)

/**
 * 选中行（下拉项、选择器行、已选分类 chip）的实色底色：surfaceVariant 向主题色推进 34%（浅）/ 30%（深），对应 HMCL 的
 * `-monet-secondary-container` 选中态（blue.css:12）与 `secondary-container-transparent-50`
 * 悬停态（root.css:149/1567）。
 */
@Composable
fun selectedRowBackground(): Color {
    val colors = MaterialTheme.colorScheme
    return lerp(colors.surfaceVariant, colors.primary, if (isDarkScheme()) .30f else .34f)
}

/** 白卡片容器：1dp 细边、无阴影、圆角 6、内边距 16。 传入 title 时渲染 14sp SemiBold 标题行，并用 1dp 分隔线与内容隔开。 */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    padding: Int = 16,
    title: String? = null,
    detail: String = "",
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier,
        shape = RoundedCornerShape(Metrics.cardRadius),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, hairline()),
    ) {
        Column {
            if (!title.isNullOrBlank()) {
                Column(
                    Modifier.fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 11.dp, bottom = 10.dp)
                ) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (detail.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            detail,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                HorizontalDivider(color = hairline())
            }
            Column(Modifier.padding(padding.dp), content = content)
        }
    }
}

@Composable
fun Tag(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Box(
        Modifier.clip(RoundedCornerShape(3.dp))
            .background(color.copy(alpha = .10f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

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
            animationSpec = motionSpec(Motion.fast),
            label = "iconButtonHover",
        )
    IconButton(
        onClick = onClick,
        modifier =
            Modifier.size(size)
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

/** 主按钮：高 34、圆角 4、13sp Medium、内边距 14/8，按下 0.98 缩放。 */
@Composable
fun PrimaryButton(
    text: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick,
        Modifier.pressScale().height(Metrics.controlHeight),
        enabled = enabled,
        shape = RoundedCornerShape(Metrics.innerRadius),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun MutedText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}

/** 次按钮：与主按钮同高同圆角，透明底 + 1dp `outlineVariant` 描边。 */
@Composable
fun SecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(Metrics.innerRadius),
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    border: BorderStroke? = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick,
        modifier.height(Metrics.controlHeight),
        enabled = enabled,
        shape = shape,
        colors = colors,
        border = border,
        contentPadding = contentPadding,
    ) {
        ProvideTextStyle(
            value = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
        ) {
            content()
        }
    }
}

/** 空态：40dp 图标 + 14sp 标题 + 12sp 说明，垂直留白 28dp。 */
@Composable
fun EmptyState(
    title: String,
    detail: String,
    icon: ImageVector,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            icon,
            null,
            Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = .7f),
        )
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        action?.invoke()
    }
}

/** 统一尺寸令牌：标题栏、页面、卡片、行与控件都从这里取。 参考 HMCL 的紧凑密度（标题栏 52、行 44、控件 34、卡片圆角 6），保留樱花粉的柔和观感。 */
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
    val rowMinHeight = 44.dp
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
            animationSpec = motionSpec(Motion.fast),
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
            animationSpec = motionSpec(Motion.fast),
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
    return this.graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(Unit) {
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
