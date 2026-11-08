// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责 Java 选择器：当前生效项 + 扁平单选列表（自动 / 检测结果 / 自定义）+ 添加入口。

package dev.molifox.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.molifox.core.JavaRuntime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Java 单选列表（扁平，不折叠）：第一行是当前生效的选择与来源，下面是所有候选，最后是「添加」。
 *
 * [value] 是当前作用域保存的 Java 路径，空串表示自动；[inheritedPath] 只用于实例作用域展示「跟随全局设置」时实际生效的值。
 * [knownCustomPaths] 是用户「添加」过的路径（`LauncherSettings.customJavaPaths`），只有它们会作为独立条目出现；
 * 临时指定的自定义路径留在「自定义」一行。[onAddCustom] 非空表示当前作用域允许添加 / 移除启动器安装的 Java。
 */
@Composable
internal fun JavaPicker(
    actions: UiActions,
    value: String,
    runtimes: List<JavaRuntime>,
    onSelect: (String) -> Unit,
    knownCustomPaths: List<String> = emptyList(),
    onAddCustom: ((String) -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
    detecting: Boolean = false,
    detectionError: String? = null,
    inheritedPath: String = "",
) {
    val colors = MaterialTheme.colorScheme
    val strings = LocalStrings.current
    val platform = actions.controller.platform
    val windows = platform.osName == "windows"
    val canManage = onAddCustom != null
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    // 所有路径统一交给 JavaService 探测；探测失败只提示，不写回、不清空已保存的选择。
    val browse: (Boolean) -> Unit = { add ->
        browseJava(
            actions = actions,
            windows = windows,
            add = add,
            isPicking = { picking },
            onPicking = { picking = it },
            onError = { error = it },
            onSelect = onSelect,
            onAddCustom = onAddCustom,
            onRefresh = onRefresh,
        )
    }
    val listed = listedJavaRuntimes(runtimes, knownCustomPaths, windows)
    val effectivePath = value.ifBlank { inheritedPath }
    val effectiveRuntime = runtimes.firstOrNull { sameJavaPath(it.path, effectivePath, windows) }
    val listedSelection = listed.firstOrNull { sameJavaPath(it.path, value, windows) }
    val customPath = if (value.isNotBlank() && listedSelection == null) value else ""
    val headerLabel = javaPickerHeaderLabel(effectiveRuntime, effectivePath)
    val autoDetail = javaPickerAutoDetail(inheritedPath, runtimes, windows)
    // 当前生效的选择、来源与占用（实例作用域可能继承全局设置）。
    val headerMeta =
        if (effectiveRuntime == null) {
            ""
        } else if (!effectiveRuntime.usable) {
            effectiveRuntime.invalidReason
        } else {
            listOf(javaSourceTitle(effectiveRuntime.source), readableJavaSize(effectiveRuntime.sizeBytes))
                .filter { it.isNotBlank() }
                .joinToString(strings.t("settings.java.picker.metaSeparator"))
        }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        JavaPickerHeader(
            headerLabel = headerLabel,
            headerMeta = headerMeta,
            canRemove =
                canManage && effectiveRuntime != null &&
                    removableJava(effectiveRuntime, windows, knownCustomPaths),
            onRemove = {
                effectiveRuntime?.let { runtime ->
                    removeJavaRuntime(actions, value, runtime, windows, onSelect, onRefresh)
                }
            },
        )
        JavaChoiceRow(
            selected = value.isBlank(),
            label = strings.t("settings.java.auto"),
            detail = autoDetail,
            onClick = {
                error = null
                onSelect("")
            },
        )
        listed.forEach { runtime ->
            JavaListedChoice(
                runtime = runtime,
                selected = sameJavaPath(runtime.path, value, windows),
                removable = canManage && removableJava(runtime, windows, knownCustomPaths),
                onRemove = {
                    removeJavaRuntime(actions, value, runtime, windows, onSelect, onRefresh)
                },
                onClick = {
                    error = null
                    onSelect(runtime.path)
                },
            )
        }
        JavaCustomChoice(
            customPath = customPath,
            picking = picking,
            onPickCustom = {
                if (customPath.isNotBlank()) {
                    error = null
                    onSelect(customPath)
                } else {
                    browse(false)
                }
            },
            onBrowse = { browse(false) },
        )
        if (canManage) {
            JavaDetectRow(
                detecting = detecting,
                detectionError = detectionError,
                onAdd = { browse(true) },
                onRefresh = onRefresh,
            )
        }
        error?.let {
            Text(
                it,
                Modifier.padding(start = 32.dp, end = 8.dp, bottom = 2.dp),
                color = colors.error,
                fontSize = 11.sp,
            )
        }
    }
}

/**
 * 实例设置的 Java 行：一行显示当前生效的运行时，点击打开底部抽屉。
 * [value] 为空表示继承全局；继承时右侧显示全局解析到的运行时。
 */
@Composable
internal fun JavaPickerRow(
    value: String,
    runtimes: List<JavaRuntime>,
    inheritedPath: String,
    ignoreCase: Boolean,
    detecting: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val strings = LocalStrings.current
    val shape = RoundedCornerShape(Metrics.innerRadius)
    val effectivePath = value.ifBlank { inheritedPath }
    val runtime = runtimes.firstOrNull { sameJavaPath(it.path, effectivePath, ignoreCase) }
    val resolved =
        when {
            detecting && runtimes.isEmpty() -> strings.t("settings.java.picker.row.detecting")
            effectivePath.isBlank() -> strings.t("settings.java.picker.row.auto")
            runtime != null && runtime.usable -> javaRuntimeHeading(runtime)
            runtime != null -> strings.t("settings.java.picker.row.invalid", runtime.invalidReason)
            else -> strings.t("settings.java.picker.row.custom")
        }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(shape)
            .hoverHighlight(shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Java", fontSize = 14.sp)
        Tag(
            if (value.isBlank()) {
                strings.t("settings.java.picker.inherit")
            } else {
                strings.t("settings.java.picker.instanceOnly")
            },
            if (value.isBlank()) colors.onSurfaceVariant else colors.primary,
        )
        Text(
            resolved,
            Modifier.weight(1f),
            fontSize = 12.sp,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
    }
}

/**
 * Java 选择抽屉：第一项「继承全局」为默认，其后是检测到的运行时。
 * Esc 或点击抽屉外关闭；[onSelect] 由调用方负责保存。
 */
@Composable
internal fun JavaPickerSheet(
    value: String,
    runtimes: List<JavaRuntime>,
    inheritedPath: String,
    ignoreCase: Boolean,
    detecting: Boolean = false,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val window = LocalWindowInfo.current.containerDpSize
    val duration = if (LocalReducedMotion.current) 0 else 120
    var visible by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val dismiss: () -> Unit = {
        if (!closing) {
            closing = true
            visible = false
            if (duration == 0) {
                onDismiss()
            } else {
                scope.launch {
                    delay(duration.toLong())
                    onDismiss()
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        visible = true
        // 焦点节点在首帧挂载；失败只影响 Esc，不影响点击关闭。
        runCatching { focus.requestFocus() }
    }
    val usable =
        runtimes
            .filter { it.usable }
            .distinctBy { it.path }
            .sortedWith(
                compareByDescending<JavaRuntime> { it.majorVersion }
                    .thenByDescending { it.bits }
                    .thenBy { it.source.ordinal },
            )
    val currentInvalid =
        runtimes.firstOrNull { !it.usable && sameJavaPath(it.path, value, ignoreCase) }
    Popup(
        alignment = Alignment.TopStart,
        onDismissRequest = dismiss,
        properties =
            PopupProperties(
                focusable = true,
                dismissOnClickOutside = true,
                dismissOnBackPress = true,
            ),
    ) {
        Box(
            Modifier
                .size(window)
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        dismiss()
                        true
                    } else {
                        false
                    }
                }.focusable(),
        ) {
            AnimatedVisibility(
                visible,
                Modifier.fillMaxSize(),
                enter = fadeIn(tween(duration)),
                exit = fadeOut(tween(duration)),
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = if (isDarkScheme()) .45f else .28f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = dismiss,
                        ),
                )
            }
            AnimatedVisibility(
                visible,
                Modifier.align(Alignment.BottomCenter).padding(16.dp),
                enter =
                    slideInVertically(tween(duration)) { height -> height } +
                        fadeIn(tween(duration)),
                exit =
                    slideOutVertically(tween(duration)) { height -> height } +
                        fadeOut(tween(duration)),
            ) {
                Surface(
                    Modifier.widthIn(max = 560.dp).fillMaxWidth().clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {},
                    shape = RoundedCornerShape(Metrics.dialogRadius),
                    color = colors.surface,
                    border = BorderStroke(1.dp, hairline()),
                    shadowElevation = 16.dp,
                ) {
                    JavaSheetSlot(
                        value = value,
                        inheritedPath = inheritedPath,
                        runtimes = runtimes,
                        ignoreCase = ignoreCase,
                        detecting = detecting,
                        currentInvalid = currentInvalid,
                        usable = usable,
                        onPick = { path ->
                            onSelect(path)
                            dismiss()
                        },
                        onDismiss = dismiss,
                    )
                }
            }
        }
    }
}

/** 抽屉正文：标题 +「继承全局」默认项 + 已检测的 Java。空路径表示继承全局。 */
@Composable
internal fun JavaSheetSlot(
    value: String,
    inheritedPath: String,
    runtimes: List<JavaRuntime>,
    ignoreCase: Boolean,
    detecting: Boolean,
    currentInvalid: JavaRuntime?,
    usable: List<JavaRuntime>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    Column(
        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(strings.t("settings.java.sheet.title"), style = MaterialTheme.typography.titleMedium)
                Text(
                    strings.t("settings.java.sheet.detail"),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SmallIconButton(Icons.Outlined.Close, strings.t("settings.java.sheet.close"), onDismiss)
        }
        HorizontalDivider(color = hairline())
        Column(
            Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            JavaSheetOption(
                selected = value.isBlank(),
                title = strings.t("settings.java.sheet.inherit", javaChoiceName(inheritedPath, runtimes, ignoreCase)),
                detail = strings.t("settings.java.sheet.inheritDetail"),
                onClick = { onPick("") },
            )
            HorizontalDivider(
                color = hairline().copy(alpha = .6f),
                modifier = Modifier.padding(vertical = 2.dp),
            )
            if (currentInvalid != null) {
                MutedText(
                    strings.t(
                        "settings.java.sheet.currentInvalid",
                        currentInvalid.invalidReason,
                    ),
                )
            }
            if (usable.isEmpty()) {
                MutedText(
                    if (detecting) {
                        strings.t("settings.java.sheet.detecting")
                    } else {
                        strings.t("settings.java.sheet.empty")
                    },
                )
            } else {
                usable.forEach { runtime ->
                    JavaSheetOption(
                        selected = sameJavaPath(runtime.path, value, ignoreCase),
                        title = javaRuntimeHeading(runtime),
                        detail =
                            listOf(
                                runtime.vendor,
                                runtime.architecture,
                                javaSourceTitle(runtime.source),
                            ).filter { it.isNotBlank() }
                                .joinToString(" · "),
                        onClick = { onPick(runtime.path) },
                    )
                }
            }
        }
    }
}

/** 抽屉里的一行：单选圆点 + 标题 + 一行次要信息（厂商 · 架构 · 来源）。 */
@Composable
private fun JavaSheetOption(
    selected: Boolean,
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Metrics.innerRadius)
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(if (selected) selectedRowBackground() else Color.Transparent)
            .hoverHighlight(shape, enabled = !selected, interactionSource = interaction)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.RadioButton,
                onClick = onClick,
            ).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        JavaRadioMark(selected)
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color =
                    if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 一个单选项：圆点 + 版本标签（+ 来源标签）+ 右侧次级色路径（从左侧省略，文件名始终可见）。 */
@Composable
internal fun JavaChoiceRow(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    detail: String? = null,
    tag: String? = null,
    tagColor: Color = MaterialTheme.colorScheme.primary,
    path: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Metrics.innerRadius)
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(if (selected) selectedRowBackground() else Color.Transparent)
            .hoverHighlight(shape, interactionSource = interaction)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.RadioButton,
                onClick = onClick,
            ).padding(start = 8.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        JavaRadioMark(selected)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = if (selected) colors.primary else colors.onSurface,
                maxLines = 1,
            )
            if (tag != null) Tag(tag, tagColor)
        }
        if (detail != null) {
            Text(
                detail,
                fontSize = 11.sp,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (path != null) {
            Spacer(Modifier.weight(1f))
            PathLabel(path, Modifier.weight(2f))
        } else {
            Spacer(Modifier.weight(1f))
        }
        trailing?.invoke()
    }
}

/** 单选圆点：选中实心主题色 + 白色内点，未选中只有 1.5dp 描边。 */
@Composable
private fun JavaRadioMark(selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(16.dp)
            .clip(CircleShape)
            .background(if (selected) colors.primary else Color.Transparent)
            .border(1.5.dp, if (selected) colors.primary else colors.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(6.dp).clip(CircleShape).background(colors.onPrimary))
    }
}

/** 路径行：右侧对齐、11sp 次级色；放不下时从左侧省略，只保留目录尾部与文件名。 */
@Composable
private fun PathLabel(
    path: String,
    modifier: Modifier,
) {
    val style =
        MaterialTheme.typography.bodySmall.copy(
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val maxWidthPx = with(LocalDensity.current) { maxWidth.toPx() }.toInt()
        val text =
            remember(path, maxWidthPx, style) {
                leftEllipsizedPath(path, maxWidthPx, measurer, style)
            }
        Text(
            text,
            Modifier.fillMaxWidth(),
            style = style,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}
