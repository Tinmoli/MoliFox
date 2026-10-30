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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toLong
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.molifox.core.JavaRuntime
import dev.molifox.core.JavaRuntimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.math.max

/** 自动项的固定文案：设置页与实例页共用，避免出现两种说法。 */
internal const val AUTO_JAVA_LABEL = "自动选择合适的 Java"

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
    val platform = actions.controller.platform
    val windows = platform.osName == "windows"
    val canManage = onAddCustom != null
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }

    fun matches(
        left: String,
        right: String,
    ) = sameJavaPath(left, right, windows)

    // 启动器安装的与用户添加的可以删（自定义只删记录）；系统 Java 只读。
    fun removable(runtime: JavaRuntime) =
        runtime.managed ||
            (
                runtime.source == JavaRuntimeSource.Custom &&
                    knownCustomPaths.any { matches(it, runtime.path) }
            )
    // 用户添加的路径才进列表；当前临时指定的自定义路径由下方「自定义」一行承载。
    val listed =
        runtimes
            .filter {
                it.source != JavaRuntimeSource.Custom ||
                    knownCustomPaths.any { path -> matches(path, it.path) }
            }.sortedWith(
                compareByDescending<JavaRuntime> { it.usable }
                    .thenByDescending { it.majorVersion }
                    .thenBy { it.source.ordinal },
            )
    val effectivePath = value.ifBlank { inheritedPath }
    val effectiveRuntime = runtimes.firstOrNull { matches(it.path, effectivePath) }
    val listedSelection = listed.firstOrNull { matches(it.path, value) }
    val customPath = if (value.isNotBlank() && listedSelection == null) value else ""
    val headerLabel =
        when {
            effectiveRuntime != null -> javaRuntimeLabel(effectiveRuntime)
            effectivePath.isBlank() -> AUTO_JAVA_LABEL
            else -> "自定义"
        }
    val autoDetail =
        if (inheritedPath.isBlank()) {
            "按游戏版本自动匹配"
        } else {
            "跟随全局设置：" + javaChoiceName(inheritedPath, runtimes, windows)
        }

    // 所有路径统一交给 JavaService 探测；探测失败只提示，不写回、不清空已保存的选择。
    fun browseJava(add: Boolean) {
        if (picking) return
        picking = true
        error = null
        actions.run {
            try {
                val chosen =
                    actions
                        .choose(
                            if (add) "添加 Java 可执行文件" else "选择 Java 可执行文件",
                            extensions = if (windows) listOf("exe") else emptyList(),
                        ).firstOrNull()
                if (chosen != null) {
                    val runtime =
                        withContext(Dispatchers.IO) {
                            runCatching { actions.controller.java.inspect(chosen.toString()) }
                                .getOrNull()
                        }
                    withContext(Dispatchers.Swing) {
                        if (runtime == null) {
                            error = "这个文件不是可运行的 Java：" + chosen
                        } else {
                            if (add) {
                                onAddCustom?.invoke(runtime.path)
                                actions.controller.java.invalidate()
                                onRefresh?.invoke()
                            }
                            onSelect(runtime.path)
                        }
                    }
                }
            } finally {
                withContext(Dispatchers.Swing) { picking = false }
            }
        }
    }

    fun removeJava(runtime: JavaRuntime) {
        val job = actions.controller.removeJavaRuntime(runtime)
        // 当前选择指向被删的运行时：回到自动，避免留下失效路径。
        if (sameJavaPath(value, runtime.path, windows)) onSelect("")
        actions.run {
            job.join()
            withContext(Dispatchers.Swing) { onRefresh?.invoke() }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // 当前生效的选择、来源与占用（实例作用域可能继承全局设置）。
        val headerMeta =
            buildString {
                effectiveRuntime?.let { runtime ->
                    if (!runtime.usable) {
                        append(runtime.invalidReason)
                    } else {
                        append(javaSourceTitle(runtime.source))
                        if (runtime.sizeBytes > 0) {
                            append("，")
                            append(readableJavaSize(runtime.sizeBytes))
                        }
                    }
                }
            }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("当前使用", fontSize = 12.sp, color = colors.onSurfaceVariant)
            Text(headerLabel, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            if (headerMeta.isNotBlank()) {
                Text(
                    "（" + headerMeta + "）",
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            if (canManage && effectiveRuntime != null && removable(effectiveRuntime)) {
                TextButton(
                    { removeJava(effectiveRuntime) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text("移除", fontSize = 12.sp)
                }
            }
        }
        JavaChoiceRow(
            selected = value.isBlank(),
            label = AUTO_JAVA_LABEL,
            detail = autoDetail,
            onClick = {
                error = null
                onSelect("")
            },
        )
        listed.forEach { runtime ->
            val invalid = !runtime.usable
            JavaChoiceRow(
                selected = matches(runtime.path, value),
                label = javaRuntimeLabel(runtime),
                tag =
                    when {
                        invalid -> runtime.invalidReason
                        runtime.source == JavaRuntimeSource.System -> null
                        else -> javaSourceTitle(runtime.source)
                    },
                tagColor = if (invalid) colors.error else sourceColor(runtime.source),
                path = runtime.path,
                onClick = {
                    error = null
                    onSelect(runtime.path)
                },
                trailing =
                    if (canManage && removable(runtime)) {
                        {
                            TextButton(
                                { removeJava(runtime) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) {
                                Text("移除", fontSize = 11.sp)
                            }
                        }
                    } else {
                        null
                    },
            )
        }
        JavaChoiceRow(
            selected = customPath.isNotBlank(),
            label = "自定义",
            detail = if (customPath.isBlank()) "尚未选择 Java 可执行文件" else null,
            path = customPath.ifBlank { null },
            onClick = {
                if (customPath.isNotBlank()) {
                    error = null
                    onSelect(customPath)
                } else {
                    browseJava(add = false)
                }
            },
            trailing = {
                SecondaryButton(
                    { browseJava(add = false) },
                    enabled = !picking,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Outlined.FolderOpen, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (picking) "选择中…" else "选择…", fontSize = 12.sp)
                }
            },
        )
        if (canManage) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(Metrics.innerRadius))
                        .hoverHighlight()
                        .clickable(role = Role.Button) { browseJava(add = true) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Outlined.Add,
                        null,
                        Modifier.size(16.dp),
                        tint = colors.primary,
                    )
                    Text("添加 Java", fontSize = 13.sp, color = colors.primary)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    when {
                        detecting -> "正在检测…"
                        detectionError != null -> "检测失败，可重试"
                        else -> "重新扫描系统与启动器目录"
                    },
                    fontSize = 11.sp,
                    color = if (detectionError != null) colors.error else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (onRefresh != null) {
                    SecondaryButton(
                        { onRefresh() },
                        enabled = !detecting,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(if (detecting) "检测中…" else "检测 Java", fontSize = 12.sp)
                    }
                }
            }
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
    val shape = RoundedCornerShape(Metrics.innerRadius)
    val effectivePath = value.ifBlank { inheritedPath }
    val runtime = runtimes.firstOrNull { sameJavaPath(it.path, effectivePath, ignoreCase) }
    val resolved =
        when {
            detecting && runtimes.isEmpty() -> "正在检测…"
            effectivePath.isBlank() -> "自动按游戏版本匹配"
            runtime != null && runtime.usable -> javaRuntimeHeading(runtime)
            runtime != null -> "不可用：" + runtime.invalidReason
            else -> "自定义 Java"
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
            if (value.isBlank()) "继承全局" else "仅此实例",
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
    Column(
        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("选择 Java", style = MaterialTheme.typography.titleMedium)
                Text(
                    "只影响当前实例，选择后立即生效",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SmallIconButton(Icons.Outlined.Close, "关闭 Java 选择器", onDismiss)
        }
        HorizontalDivider(color = hairline())
        Column(
            Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            JavaSheetOption(
                selected = value.isBlank(),
                title =
                    "继承全局（当前：" +
                        javaChoiceName(inheritedPath, runtimes, ignoreCase) +
                        "）",
                detail = "之后修改全局 Java 会自动跟随",
                onClick = { onPick("") },
            )
            HorizontalDivider(
                color = hairline().copy(alpha = .6f),
                modifier = Modifier.padding(vertical = 2.dp),
            )
            if (currentInvalid != null) {
                MutedText("当前 Java 不可用：" + currentInvalid.invalidReason + "，请重新选择。")
            }
            if (usable.isEmpty()) {
                MutedText(
                    if (detecting) "正在检测 Java…" else "未检测到其它 Java，可在设置页添加或安装。",
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
private fun JavaChoiceRow(
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

/** 版本标签：`<完整版本或主版本>（<位数> 位）`；位数取启动器探测结果，缺失时退回架构推断。 */
internal fun javaRuntimeLabel(runtime: JavaRuntime): String = javaRuntimeLabel(runtime, "x64")

/** 兼容调用方显式传入本机架构：探测结果里没有位数时用它兜底。 */
internal fun javaRuntimeLabel(
    runtime: JavaRuntime,
    fallbackArchitecture: String,
): String {
    if (runtime.majorVersion <= 0) return "未知版本"
    val version = runtime.version.ifBlank { runtime.majorVersion.toString() }
    return version + "（" + javaBits(runtime, fallbackArchitecture) + " 位）"
}

internal fun javaBits(
    runtime: JavaRuntime,
    fallbackArchitecture: String,
): String {
    if (runtime.bits == 32 || runtime.bits == 64) return runtime.bits.toString()
    return when (runtime.architecture.lowercase()) {
        "amd64",
        "x86_64",
        "x64",
        "aarch64",
        "arm64",
        "ppc64le",
        "s390x",
        -> "64"
        "x86",
        "i386",
        "i486",
        "i586",
        "i686",
        "arm",
        "arm32",
        -> "32"
        else ->
            when (fallbackArchitecture.lowercase()) {
                "x86" -> "32"
                else -> "64"
            }
    }
}

/** 来源文案：启动器自己安装的 Java 标成「启动器安装」，与系统安装区分开。 */
internal fun javaSourceTitle(source: JavaRuntimeSource): String =
    when (source) {
        JavaRuntimeSource.Bundled -> "启动器安装"
        JavaRuntimeSource.System -> "系统"
        JavaRuntimeSource.Custom -> "自定义"
    }

/** 占用文案：只有启动器安装的运行时能可靠统计；未知时返回空串。 */
internal fun readableJavaSize(bytes: Long): String =
    when {
        bytes <= 0 -> ""
        bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1073741824f)
        else -> "%.0f MB".format(bytes / 1048576f)
    }

@Composable
private fun sourceColor(source: JavaRuntimeSource): Color =
    if (source == JavaRuntimeSource.Bundled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

/** 运行时标题：`Java 21.0.5（64 位）`；不可用条目直接给出原因。 */
internal fun javaRuntimeHeading(
    runtime: JavaRuntime,
    fallbackArchitecture: String = "x64",
): String {
    if (!runtime.usable) return "不可用的 Java（" + runtime.invalidReason + "）"
    val version = runtime.version.ifBlank { runtime.majorVersion.toString() }
    return "Java " + version + "（" + javaBits(runtime, fallbackArchitecture) + " 位）"
}

/** 当前 Java 的可读名称：空路径表示自动，找不到运行时按自定义处理。 */
internal fun javaChoiceName(
    path: String,
    runtimes: List<JavaRuntime>,
    ignoreCase: Boolean,
): String {
    if (path.isBlank()) return "自动按游戏版本匹配"
    return runtimes.firstOrNull { sameJavaPath(it.path, path, ignoreCase) }?.let {
        javaRuntimeHeading(it)
    } ?: "自定义 Java"
}

/** 路径比较：Windows 忽略大小写；解析失败时只比较原文。 */
internal fun sameJavaPath(
    left: String,
    right: String,
    ignoreCase: Boolean,
): Boolean {
    if (left.isBlank() || right.isBlank()) return false
    if (left.equals(right, ignoreCase)) return true
    return runCatching {
        val a = Path.of(left).toAbsolutePath().normalize()
        val b = Path.of(right).toAbsolutePath().normalize()
        a.toString().equals(b.toString(), ignoreCase)
    }.getOrDefault(false)
}

/** 从左侧省略路径，保留最后的目录段与文件名；文件名本身放不下时按可用宽度截断。 */
internal fun leftEllipsizedPath(
    path: String,
    maxWidthPx: Int,
    measurer: TextMeasurer,
    style: TextStyle,
): String {
    if (path.isEmpty() || maxWidthPx <= 0) return path

    fun fits(text: String): Boolean =
        measurer.measure(AnnotatedString(text), style, maxLines = 1, softWrap = false).size.width <=
            maxWidthPx
    if (fits(path)) return path
    val nameStart = max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1
    var low = 0
    var high = nameStart
    var best = nameStart
    while (low <= high) {
        val mid = (low + high) / 2
        if (fits("…" + path.substring(mid))) {
            best = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return "…" + path.substring(best)
}
