// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责设置页面的通用输入控件。

package dev.molifox.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.LauncherNoticeKind
import dev.molifox.core.LauncherSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

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
    if (actions.controller.store.noticeKind() == LauncherNoticeKind.SettingsSaved) {
        actions.controller.store.dismissNotification()
    }
    return failure?.message
}

/** 滑块本地值：拖动时只改这份本地状态以保证跟手，停手 300ms 后写入设置。 控件被销毁（切分区 / 离开页面）时补写还没到 300ms 的值，拖动结果不会丢。 */
@Composable
internal fun rememberSliderDraft(
    value: Float,
    onCommit: (Float) -> Unit,
): MutableFloatState {
    val draft = remember { mutableFloatStateOf(value) }
    val commit by rememberUpdatedState(onCommit)
    val saved by rememberUpdatedState(value)
    LaunchedEffect(value) { if (draft.floatValue != value) draft.floatValue = value }
    LaunchedEffect(draft.floatValue) {
        if (draft.floatValue == saved) return@LaunchedEffect
        delay(LIVE_WRITE_DELAY)
        // 写盘（备份复制 + 全量写 JSON）移到 IO：防抖回调跑在界面线程，同步落盘会造成拖动停手时的卡顿。
        withContext(Dispatchers.IO) { commit(draft.floatValue) }
    }
    DisposableEffect(Unit) {
        // 销毁补写是一次性收尾：保持同步执行，不依赖可能已随页面取消的作用域，防止丢写。
        onDispose { if (draft.floatValue != saved) runCatching { commit(draft.floatValue) } }
    }
    return draft
}

/** 文本输入的本地草稿：当前文本、输入回调与写盘失败原因。 */
internal class LiveTextDraft(
    val text: String,
    val onChange: (String) -> Unit,
    val error: String?,
)

/** 文本本地草稿：输入时只改本地文本，停手 300ms 后写入设置；控件销毁时补写未落盘的输入。 [onCommit] 返回 null 表示已保存，否则是失败原因。 */
@Composable
internal fun rememberLiveTextDraft(
    value: String,
    onCommit: (String) -> String?,
): LiveTextDraft {
    var text by remember { mutableStateOf(value) }
    var error by remember { mutableStateOf<String?>(null) }
    val commit by rememberUpdatedState(onCommit)
    val saved by rememberUpdatedState(value)
    LaunchedEffect(value) { if (text != value) text = value }
    LaunchedEffect(text) {
        if (text == saved) return@LaunchedEffect
        delay(LIVE_WRITE_DELAY)
        // 与滑块同口径：写盘移到 IO，界面线程只更新本地草稿与错误提示。
        error = withContext(Dispatchers.IO) { commit(text) }
    }
    DisposableEffect(Unit) {
        // 销毁补写是一次性收尾：保持同步执行，不依赖可能已随页面取消的作用域，防止丢写。
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
    description: String = "",
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
                description = description,
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
                    if (live.error != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
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
    description: String = "",
    enabled: Boolean = true,
    onCommit: (Int) -> String?,
) {
    val live =
        rememberLiveTextDraft(value.toString()) { text ->
            val parsed = text.toIntOrNull()
            if (parsed == null || parsed !in range) null else onCommit(parsed)
        }
    val strings = LocalStrings.current
    Column(modifier) {
        CompactTextField(
            live.text,
            { text -> live.onChange(text.filter(Char::isDigit).take(5)) },
            label = label,
            placeholder = placeholder,
            description = description,
            enabled = enabled,
        )
        val parsed = live.text.toIntOrNull()
        val message =
            when {
                parsed == null || parsed !in range ->
                    strings.t("settings.numberField.range", range.first, range.last)
                else -> live.error
            }
        if (message != null) {
            Spacer(Modifier.height(4.dp))
            Text(message, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
        }
    }
}

/** 数字输入框：34dp 高、圆角 4、1dp 边；保留只允许数字、最多 5 位的输入过滤。 */
@Composable
fun NumberField(
    value: String,
    label: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    placeholder: String = "",
    enabled: Boolean = true,
    // 可选：失焦回调，用于把非法输入回退到上一次有效值。
    onFocusChange: ((Boolean) -> Unit)? = null,
) {
    CompactTextField(
        value = value,
        onChange = { onChange(it.filter(Char::isDigit).take(5)) },
        modifier = modifier,
        label = label,
        hint = hint,
        placeholder = placeholder,
        enabled = enabled,
        onFocusChange = onFocusChange,
    )
}

/**
 * 通用输入框：单行时高 34dp，多行时随内容增高（最小仍 34dp）；圆角 4、1dp 边（聚焦变主题色），
 * 标签与提示 11sp。所有设置分区共用，替代 M3 `OutlinedTextField` 的 56dp 默认高度。
 */
@Composable
fun CompactTextField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    hint: String? = null,
    placeholder: String = "",
    /** 仅写进无障碍名不渲染；行标题已经说明用途的字段用它，避免界面出现两份文字。 */
    description: String = "",
    enabled: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    // 可选的外部聚焦入口：例如「添加认证服务器」入口打开对话框后直接落焦在地址框上。
    focusRequester: FocusRequester? = null,
    // 可选的失焦回调：设置页用它把非法输入回退到上一次有效值。
    onFocusChange: ((Boolean) -> Unit)? = null,
    // 可选的 IME 动作：例如认证服务器登录框的密码栏用 Done，让软键盘「完成」与回车都走同一个提交函数。
    imeAction: ImeAction = ImeAction.Default,
    onImeAction: (() -> Unit)? = null,
    // 可选的硬件回车提交：桌面端 BasicTextField 不保证把回车转成 IME Done，这里显式兜住。
    onSubmit: (() -> Unit)? = null,
    /** 多行输入：maxLines 大于 1 时输入框随内容增高；单行时保持固定高度与回车换行禁用。 */
    minLines: Int = 1,
    maxLines: Int = 1,
    /** 覆盖输入字体（如启动参数的等宽字体）；null 用全局正文字体。 */
    textStyle: androidx.compose.ui.text.TextStyle? = null,
    /** 校验失败态：边框与填充用错误色，语义与 M3 isError 一致。 */
    isError: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val borderColor by
        animateColorAsState(
            targetValue = if (isError) colors.error else controlBorderColor(focused),
            animationSpec =
                if (LocalReducedMotion.current) {
                    androidx.compose.animation.core
                        .snap()
                } else {
                    androidx.compose.animation.core
                        .tween(Motion.FAST)
                },
            label = "fieldBorder",
        )
    // 填充从「比卡片深一档」开始，悬停 / 聚焦再向主题色靠近，动画与边框同步。
    val fillColor by
        animateColorAsState(
            targetValue = controlBackground(focused, hovered),
            animationSpec =
                if (LocalReducedMotion.current) {
                    androidx.compose.animation.core
                        .snap()
                } else {
                    androidx.compose.animation.core
                        .tween(Motion.FAST)
                },
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
            Modifier
                .fillMaxWidth()
                .heightIn(min = Metrics.controlHeight)
                .clip(RoundedCornerShape(Metrics.innerRadius))
                .background(fillColor)
                .border(1.dp, borderColor, RoundedCornerShape(Metrics.innerRadius))
                .padding(horizontal = 10.dp)
                // 多行内容顶对齐时留出上下呼吸空间；单行由固定高度居中，不加纵向内边距。
                .padding(vertical = if (maxLines > 1) 7.dp else 0.dp)
                .alpha(if (enabled) 1f else .38f),
            contentAlignment = if (maxLines > 1) Alignment.TopStart else Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = (textStyle ?: MaterialTheme.typography.bodyMedium).copy(color = colors.onSurface),
                singleLine = maxLines == 1,
                maxLines = maxLines,
                minLines = minLines,
                enabled = enabled,
                cursorBrush = SolidColor(colors.primary),
                interactionSource = interaction,
                visualTransformation = visualTransformation,
                keyboardOptions =
                    if (imeAction == ImeAction.Default) {
                        KeyboardOptions.Default
                    } else {
                        KeyboardOptions(imeAction = imeAction)
                    },
                // IME 的「完成 / 前往 / 发送」统一落到调用方给的同一个提交函数；没有给就保持默认行为。
                keyboardActions =
                    if (onImeAction == null) {
                        KeyboardActions.Default
                    } else {
                        val action = onImeAction
                        KeyboardActions(
                            onDone = { action() },
                            onGo = { action() },
                            onSend = { action() },
                            onSearch = { action() },
                            onNext = { action() },
                        )
                    },
                modifier =
                    fieldInteractionModifiers(
                        focusRequester,
                        onFocusChange,
                        onSubmit,
                        label,
                        description,
                    ),
                decorationBox = { inner ->
                    if (value.isEmpty() && placeholder.isNotBlank()) {
                        Text(
                            placeholder,
                            color = colors.onSurfaceVariant.copy(alpha = .7f),
                            fontSize = 12.sp,
                            fontFamily = textStyle?.fontFamily,
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
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val reduced = LocalReducedMotion.current
    val track by
        animateColorAsState(
            targetValue = if (checked) colors.primary else controlBackground(),
            animationSpec =
                if (reduced) {
                    androidx.compose.animation.core
                        .snap()
                } else {
                    androidx.compose.animation.core
                        .tween(Motion.FAST)
                },
            label = "switchTrack",
        )
    val progress by
        animateFloatAsState(
            targetValue = if (checked) 1f else 0f,
            animationSpec =
                if (reduced) {
                    androidx.compose.animation.core
                        .snap()
                } else {
                    androidx.compose.animation.core
                        .tween(Motion.FAST)
                },
            label = "switchThumb",
        )
    Box(
        modifier
            .size(width = 40.dp, height = 22.dp)
            .alpha(if (enabled) 1f else .38f)
            .clip(RoundedCornerShape(11.dp))
            .background(track)
            .then(
                if (onCheckedChange == null) {
                    Modifier
                } else {
                    Modifier.toggleable(
                        value = checked,
                        enabled = enabled,
                        role = Role.Switch,
                        onValueChange = onCheckedChange,
                    )
                },
            ).padding(3.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(16.dp)
                .offset(x = 18.dp * progress)
                .clip(CircleShape)
                .background(Color.White),
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
            },
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

/** 滚动列右侧的滚动条留白；同容器里不滚动的内容（搜索框、页签、工具栏）按同一值留边，右缘才能对齐。 */
internal val ScrollbarGutter: androidx.compose.ui.unit.Dp = 12.dp

@Composable
fun ScrollColumn(
    modifier: Modifier = Modifier,
    gap: androidx.compose.ui.unit.Dp = Metrics.sectionGap,
    /** true 撑满可用高度；false 高度贴内容，内容超过可用高度时才滚动（配合外层面板收缩）。 */
    fillHeight: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    Box(modifier) {
        Column(
            (if (fillHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                .verticalScroll(scroll)
                .padding(end = ScrollbarGutter),
            verticalArrangement = Arrangement.spacedBy(gap),
            content = content,
        )
        VerticalScrollbar(
            rememberScrollbarAdapter(scroll),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}
