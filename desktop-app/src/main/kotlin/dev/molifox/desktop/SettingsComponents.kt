package dev.molifox.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** 分组小标题：11sp `onSurfaceVariant`，默认上间距 18dp，供设置页分组使用。 */
@Composable
fun SettingsGroupTitle(title: String, topPadding: Dp = 18.dp) {
    Text(
        title,
        Modifier.padding(start = 2.dp, top = topPadding, bottom = 2.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
    )
}

/**
 * 设置卡片：白底、圆角 6、1dp `outlineVariant@.5` 边、无阴影、内边距 16。 标题行 14sp SemiBold（可带 12sp 说明）+ 1dp
 * 分隔线；不再有整块色带与左侧竖条。 `action` 是标题行右侧插槽（例如「重置本区」文字按钮），垂直居中。
 */
@Composable
fun SettingsSection(
    title: String,
    detail: String = "",
    action: @Composable RowScope.() -> Unit = {},
    contentGap: Dp = Metrics.rowGap,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Metrics.cardRadius),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, hairline()),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth()
                    .padding(
                        start = Metrics.cardPadding,
                        end = Metrics.cardPadding,
                        top = 11.dp,
                        bottom = 10.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
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
                action()
            }
            HorizontalDivider(color = hairline())
            Column(
                Modifier.padding(
                    horizontal = Metrics.cardPadding,
                    vertical = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(contentGap),
                content = content,
            )
        }
    }
}

/** 设置行：行高 44（带说明 52），标题 14sp、说明 12sp，行间 10dp。 */
@Composable
fun SettingRow(title: String, detail: String = "", content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .defaultMinSize(
                minHeight = if (detail.isBlank()) Metrics.rowHeight else Metrics.rowHeightWithDetail
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
            )
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        content()
    }
}

/**
 * 卡片内相邻设置行之间的细分隔线：1dp、`outlineVariant@.65`，上下各留 5dp（`Metrics.rowGap/2`）。
 *
 * 旧实现用 `outlineVariant@.45` 画在 16dp 卡片内边距里，浅色主题下 `#E7DEE3` 叠到白卡上约等于
 * `#F4F0F2`，几乎看不见，而且线比文字窄 32dp，看起来像没画。现在改为：
 * - 颜色提到 65%，与白卡的明度差足够，深色主题同样可见；
 * - 线本身不占用水平内边距，绘制时左右各溢出 `Metrics.cardPadding`，因此是整卡宽度的通栏细线，
 *   溢出部分由卡片圆角裁掉，不会盖到卡片外；
 * - 只放在两行之间——首行之前、末行之后都不画；卡片标题下方已有 `SettingsSection` 自己的分隔线。
 *
 * 配合 `SettingsSection(contentGap = 0.dp)` 使用，行高仍是 44 / 52，卡片内边距不变。
 */
@Composable
fun SettingDivider(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f)
    val bleed = with(LocalDensity.current) { Metrics.cardPadding.toPx() }
    Spacer(
        modifier.fillMaxWidth()
            .padding(vertical = Metrics.rowGap / 2)
            .height(1.dp)
            .drawBehind {
                drawRect(
                    color,
                    topLeft = Offset(-bleed, 0f),
                    size = Size(size.width + bleed * 2f, size.height),
                )
            }
    )
}

/** 数字输入框：34dp 高、圆角 4、1dp 边；保留只允许数字、最多 5 位的输入过滤。 */
@Composable
fun NumberField(
    value: String,
    label: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    enabled: Boolean = true,
) {
    CompactTextField(
        value = value,
        onChange = { onChange(it.filter(Char::isDigit).take(5)) },
        modifier = modifier,
        label = label,
        hint = hint,
        enabled = enabled,
    )
}

/** 通用单行输入框：高 34dp、圆角 4、1dp 边（聚焦变主题色），标签与提示 11sp。 所有设置分区共用，替代 M3 `OutlinedTextField` 的 56dp 默认高度。 */
@Composable
fun CompactTextField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    hint: String? = null,
    placeholder: String = "",
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val borderColor by
        animateColorAsState(
            targetValue = controlBorderColor(focused),
            animationSpec =
                if (LocalReducedMotion.current) androidx.compose.animation.core.snap()
                else androidx.compose.animation.core.tween(Motion.fast),
            label = "fieldBorder",
        )
    // 填充从「比卡片深一档」开始，悬停 / 聚焦再向主题色靠近，动画与边框同步。
    val fillColor by
        animateColorAsState(
            targetValue = controlBackground(focused, hovered),
            animationSpec =
                if (LocalReducedMotion.current) androidx.compose.animation.core.snap()
                else androidx.compose.animation.core.tween(Motion.fast),
            label = "fieldFill",
        )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label.isNotBlank()) {
            // 标签只是视觉说明：从无障碍树移除，让读屏软件与自动化探针把名字落在真正的输入框上，
            // 否则「玩家名称」这类名字会被静态 Text 抢走，可编辑节点反而没有名字。
            Text(
                label,
                Modifier.clearAndSetSemantics {},
                color = colors.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
        Box(
            Modifier.fillMaxWidth()
                .height(Metrics.controlHeight)
                .clip(RoundedCornerShape(Metrics.innerRadius))
                .background(fillColor)
                .border(1.dp, borderColor, RoundedCornerShape(Metrics.innerRadius))
                .padding(horizontal = 10.dp)
                .alpha(if (enabled) 1f else .38f),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                singleLine = true,
                enabled = enabled,
                cursorBrush = SolidColor(colors.primary),
                interactionSource = interaction,
                modifier =
                    Modifier.fillMaxWidth()
                        .then(
                            if (label.isBlank()) Modifier
                            else Modifier.semantics { contentDescription = label }
                        ),
                decorationBox = { inner ->
                    if (value.isEmpty() && placeholder.isNotBlank()) {
                        Text(
                            placeholder,
                            color = colors.onSurfaceVariant.copy(alpha = .7f),
                            fontSize = 12.sp,
                        )
                    }
                    inner()
                },
            )
        }
        if (hint != null) {
            Text(hint, color = colors.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

/** 紧凑开关：轨道 40×22、拇指 16，开态用主题色。 */
@Composable
fun CompactSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val reduced = LocalReducedMotion.current
    val track by
        animateColorAsState(
            targetValue = if (checked) colors.primary else controlBackground(),
            animationSpec =
                if (reduced) androidx.compose.animation.core.snap()
                else androidx.compose.animation.core.tween(Motion.fast),
            label = "switchTrack",
        )
    val progress by
        animateFloatAsState(
            targetValue = if (checked) 1f else 0f,
            animationSpec =
                if (reduced) androidx.compose.animation.core.snap()
                else androidx.compose.animation.core.tween(Motion.fast),
            label = "switchThumb",
        )
    Box(
        modifier
            .size(width = 40.dp, height = 22.dp)
            .alpha(if (enabled) 1f else .38f)
            .clip(RoundedCornerShape(11.dp))
            .background(track)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(3.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier.size(16.dp)
                .offset(x = 18.dp * progress)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

/** 紧凑滑块：轨道 4dp、滑块 14dp，已选区用主题色。 */
@Composable
fun CompactSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    var widthPx by remember { mutableStateOf(1f) }
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it != 0f } ?: 1f
    fun update(positionX: Float) {
        val fraction = (positionX / widthPx).coerceIn(0f, 1f)
        var next = valueRange.start + fraction * span
        if (steps > 0) {
            val stepSize = span / (steps + 1)
            next = valueRange.start + ((next - valueRange.start) / stepSize).roundToInt() * stepSize
        }
        onValueChange(next.coerceIn(valueRange.start, valueRange.endInclusive))
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(22.dp)
            .alpha(if (enabled) 1f else .38f)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(enabled, steps, valueRange) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    update(down.position.x)
                    drag(down.id) { change ->
                        change.consume()
                        update(change.position.x)
                    }
                }
            }
    ) {
        val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
        val centerY = size.height / 2f
        drawLine(
            colors.primary.copy(alpha = .20f),
            Offset(0f, centerY),
            Offset(size.width, centerY),
            4.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawLine(
            colors.primary,
            Offset(0f, centerY),
            Offset(size.width * fraction, centerY),
            4.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawCircle(colors.primary, 7.dp.toPx(), Offset(size.width * fraction, centerY))
        drawCircle(colors.surface, 4.5f.dp.toPx(), Offset(size.width * fraction, centerY))
    }
}

@Composable
fun ScrollColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Box(modifier) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(Metrics.cardGap),
            content = content,
        )
        VerticalScrollbar(
            rememberScrollbarAdapter(scroll),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}
