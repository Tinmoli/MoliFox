// 负责设置页面的通用输入控件。

package dev.molifox.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.LauncherSettings
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// ---------- 即时保存 ----------

/** 连续控件（滑块 / 文本输入）停手 300ms 后写盘：拖动与逐字输入不拆成几十次落盘。 */
internal const val LIVE_WRITE_DELAY = 300L

/**
 * 设置页唯一的写入口：把改动合并到最新已保存设置后立即写入 store。
 *
 * 操作时即时写入配置。store 的 StateFlow 更新会驱动 `MoliTheme` / `LauncherBackdrop` 重新渲染，所以主题、字体、缩放、背景与透明效果当场生效。
 * 返回 null 表示已保存；否则返回校验失败的原因——非法输入不落盘，由控件或页面就地提示。
 */
internal fun applySetting(
    actions: UiActions,
    change: (LauncherSettings) -> LauncherSettings,
): String? {
    val store = actions.controller.store
    val current = store.data.settings
    val next = change(current)
    if (next == current) return null
    val failure = runCatching { actions.controller.saveSettings(next) }.exceptionOrNull()
    // 即时保存不显示成功提示：连续调整滑块或输入时提示会一直挂着，反而盖住真正的失败原因。
    if (actions.controller.state.value.toast == "设置已保存")
        actions.controller.store.dismissNotification()
    return failure?.message
}

/** 滑块本地值：拖动时只改这份本地状态以保证跟手，停手 300ms 后写入设置。 控件被销毁（切分区 / 离开页面）时补写还没到 300ms 的值，拖动结果不会丢。 */
@Composable
internal fun rememberSliderDraft(value: Float, onCommit: (Float) -> Unit): MutableFloatState {
    val draft = remember { mutableFloatStateOf(value) }
    val commit by rememberUpdatedState(onCommit)
    val saved by rememberUpdatedState(value)
    LaunchedEffect(value) { if (draft.floatValue != value) draft.floatValue = value }
    LaunchedEffect(draft.floatValue) {
        if (draft.floatValue == saved) return@LaunchedEffect
        delay(LIVE_WRITE_DELAY)
        commit(draft.floatValue)
    }
    DisposableEffect(Unit) {
        onDispose { if (draft.floatValue != saved) runCatching { commit(draft.floatValue) } }
    }
    return draft
}

/** 文本输入的本地草稿：当前文本、输入回调与写盘失败原因。 */
internal class LiveTextDraft(val text: String, val onChange: (String) -> Unit, val error: String?)

/** 文本本地草稿：输入时只改本地文本，停手 300ms 后写入设置；控件销毁时补写未落盘的输入。 [onCommit] 返回 null 表示已保存，否则是失败原因。 */
@Composable
internal fun rememberLiveTextDraft(value: String, onCommit: (String) -> String?): LiveTextDraft {
    var text by remember { mutableStateOf(value) }
    var error by remember { mutableStateOf<String?>(null) }
    val commit by rememberUpdatedState(onCommit)
    val saved by rememberUpdatedState(value)
    LaunchedEffect(value) { if (text != value) text = value }
    LaunchedEffect(text) {
        if (text == saved) return@LaunchedEffect
        delay(LIVE_WRITE_DELAY)
        error = commit(text)
    }
    DisposableEffect(Unit) {
        onDispose { if (text != saved) runCatching { commit(text) } }
    }
    return LiveTextDraft(
        text,
        { next ->
            text = next
            error = null
        },
        error,
    )
}

/** 单行文本设置：输入框 + 可选的尾部按钮；写盘失败或需要提示时就地显示一行说明。 */
@Composable
internal fun LiveTextField(
    value: String,
    onCommit: (String) -> String?,
    modifier: Modifier = Modifier,
    label: String = "",
    placeholder: String = "",
    hint: String? = null,
    enabled: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
) {
    val live = rememberLiveTextDraft(value, onCommit)
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CompactTextField(
                live.text,
                live.onChange,
                Modifier.weight(1f),
                label = label,
                placeholder = placeholder,
                enabled = enabled,
            )
            trailing?.invoke()
        }
        val message = live.error ?: hint
        if (message != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                message,
                color =
                    if (live.error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }
    }
}

/** 数值设置：只接受数字，非法值就地提示范围；合法值停手 300ms 后写入设置。 */
@Composable
internal fun LiveNumberField(
    value: Int,
    label: String,
    range: IntRange,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    onCommit: (Int) -> String?,
) {
    val live =
        rememberLiveTextDraft(value.toString()) { text ->
            val parsed = text.toIntOrNull()
            if (parsed == null || parsed !in range) null else onCommit(parsed)
        }
    Column(modifier) {
        CompactTextField(
            live.text,
            { text -> live.onChange(text.filter(Char::isDigit).take(5)) },
            label = label,
            placeholder = placeholder,
            enabled = enabled,
        )
        val parsed = live.text.toIntOrNull()
        val message =
            when {
                parsed == null || parsed !in range -> "范围 " + range.first + "–" + range.last
                else -> live.error
            }
        if (message != null) {
            Spacer(Modifier.height(4.dp))
            Text(message, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
        }
    }
}

/** 设置页滚动内容末尾的「还原默认配置」：上方一条分隔线，中间 12sp 次级色文字按钮。 需要下拉才能看到，不与常用开关抢注意力；点击后先弹确认，再交给调用方把全部选项写回默认值。 */
@Composable
internal fun SettingsResetFooter(onReset: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = hairline())
        Box(Modifier.fillMaxWidth().padding(vertical = 2.dp), contentAlignment = Alignment.Center) {
            Text(
                "还原默认配置",
                Modifier.pressScale()
                    .clip(RoundedCornerShape(Metrics.innerRadius))
                    .hoverHighlight()
                    .clickable(role = Role.Button) { confirming = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
        }
    }
    if (confirming)
        Modal(
            "还原默认配置？",
            "下面这些选项会回到出厂值，并立即生效。",
            460.dp,
            { confirming = false },
        ) {
            MutedText("内存、游戏窗口、Java 路径、更新、下载与代理、外观、字体与页面布局都会恢复默认；" + "游戏目录、账户、已导入的字体与背景图片文件保持不动。")
            DialogActions(
                confirmText = "还原默认配置",
                onConfirm = {
                    onReset()
                    confirming = false
                },
                cancelText = "取消",
                onCancel = { confirming = false },
            )
        }
}

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
 * 分隔线；标题区使用与内容一致的卡片背景。 `action` 是标题行右侧插槽（例如外观页的「已启用」标记），垂直居中。
 */
val LocalCompactSettings = staticCompositionLocalOf { false }

@Composable
fun CompactSettingsSection(
    title: String,
    detail: String = "",
    content: @Composable ColumnScope.() -> Unit,
) {
    CompositionLocalProvider(LocalCompactSettings provides true) {
        SettingsSection(title, detail, contentGap = 6.dp, content = content)
    }
}

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
                    vertical = if (LocalCompactSettings.current) 8.dp else 12.dp,
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
                minHeight =
                    if (LocalCompactSettings.current) 36.dp
                    else if (detail.isBlank()) Metrics.rowHeight else Metrics.rowHeightWithDetail
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
 * 旧实现用 `outlineVariant@.45` 画在 16dp 卡片内边距里，浅色主题下 `#E7DEE3` 叠到白卡上约等于 `#F4F0F2`，几乎看不见，而且线比文字窄
 * 32dp，看起来像没画。现在改为：
 * - 颜色提到 65%，与白卡的明度差足够，深色主题同样可见；
 * - 线本身不占用水平内边距，绘制时左右各溢出 `Metrics.cardPadding`，因此是整卡宽度的通栏细线， 溢出部分由卡片圆角裁掉，不会盖到卡片外；
 * - 只放在两行之间——首行之前、末行之后都不画；卡片标题下方已有 `SettingsSection` 自己的分隔线。
 *
 * 配合 `SettingsSection(contentGap = 0.dp)` 使用，行高仍是 44 / 52，卡片内边距不变。
 */
@Composable
fun SettingDivider(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f)
    val bleed = with(LocalDensity.current) { Metrics.cardPadding.toPx() }
    Spacer(
        modifier.fillMaxWidth().padding(vertical = Metrics.rowGap / 2).height(1.dp).drawBehind {
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
    visualTransformation: VisualTransformation = VisualTransformation.None,
    // 可选的外部聚焦入口：例如「添加认证服务器」入口打开对话框后直接落焦在地址框上。
    focusRequester: FocusRequester? = null,
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
                visualTransformation = visualTransformation,
                modifier =
                    Modifier.fillMaxWidth()
                        .then(
                            if (focusRequester == null) Modifier
                            else Modifier.focusRequester(focusRequester)
                        )
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
fun ScrollColumn(
    modifier: Modifier = Modifier,
    gap: androidx.compose.ui.unit.Dp = Metrics.sectionGap,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    Box(modifier) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(gap),
            content = content,
        )
        VerticalScrollbar(
            rememberScrollbarAdapter(scroll),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}
