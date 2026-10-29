// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责实例维护、整合包升级与目录迁移对话框。

package dev.molifox.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.molifox.core.*
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

/** 弹窗显隐动画时长：0.96→1.0 缩放 + 淡入，关闭时反向；不做任何循环动画。 */
private const val DialogMotionMillis = 100

/**
 * 统一弹窗：宽 460 / 560（默认 560）、圆角 10、内边距 20、标题 16sp SemiBold + 12sp 副标题。 打开时 100ms 内从 0.96 缩放到 1.0
 * 并淡入，关闭时反向播完再交给调用方销毁。 业务弹窗的入参名与回调保持不变。
 */
@Composable
fun Modal(
    title: String,
    subtitle: String = "",
    width: Dp = 560.dp,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val duration = if (reduced) 0 else DialogMotionMillis
    var visible by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { visible = true }
    // 关闭先播反向动画，再通知调用方移除弹窗；重复点击只生效一次。
    val dismiss: () -> Unit = {
        if (!closing) {
            closing = true
            visible = false
            if (duration == 0) onDismiss()
            else
                scope.launch {
                    delay(duration.toLong())
                    onDismiss()
                }
        }
    }
    Dialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        AnimatedVisibility(
            visible,
            enter = fadeIn(tween(duration)) + scaleIn(tween(duration), initialScale = .96f),
            exit = fadeOut(tween(duration)) + scaleOut(tween(duration), targetScale = .96f),
        ) {
            Surface(
                Modifier.width(width).heightIn(max = 640.dp),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(Metrics.dialogRadius),
                border = BorderStroke(1.dp, hairline()),
                shadowElevation = 12.dp,
            ) {
                Column(
                    Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleLarge)
                            if (subtitle.isNotBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    subtitle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        SmallIconButton(Icons.Outlined.Close, "关闭对话框", dismiss)
                    }
                    HorizontalDivider(color = hairline())
                    // 正文统一 13sp（bodyLarge / bodyMedium 已在 Typography 收敛到 13）。
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        content()
                    }
                }
            }
        }
    }
}

/** 弹窗底部按钮行：右对齐，取消为次按钮、确认为主按钮，危险操作使用错误色主按钮。 只做视觉与排版，点击语义仍由调用方传入。 [busy] 为真时确认按钮禁用并显示小号进度圈。 */
@Composable
fun DialogActions(
    confirmText: String,
    onConfirm: () -> Unit,
    cancelText: String = "取消",
    onCancel: (() -> Unit)? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
    hint: String = "",
    busy: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (hint.isNotBlank())
            Text(
                hint,
                Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        else Spacer(Modifier.weight(1f))
        if (onCancel != null) SecondaryButton(onCancel, enabled = !busy) { Text(cancelText) }
        Button(
            onClick = onConfirm,
            modifier = Modifier.pressScale().height(Metrics.controlHeight),
            enabled = enabled && !busy,
            shape = RoundedCornerShape(Metrics.innerRadius),
            colors =
                if (danger)
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                else ButtonDefaults.buttonColors(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = LocalContentColor.current,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(confirmText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** 弹窗内的列表行：44dp 高、悬停行级主题底色，选中态用主题色容器（与设置页、选择器一致）。 */
@Composable
fun DialogListRow(
    title: String,
    detail: String = "",
    selected: Boolean = false,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = Metrics.rowHeight)
            .clip(RoundedCornerShape(Metrics.innerRadius))
            .background(
                if (selected) selectedRowBackground() else Color.Transparent,
                RoundedCornerShape(Metrics.innerRadius),
            )
            .rowHoverHighlight(enabled = !selected)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Medium else null,
                color =
                    if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    color =
                        if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * 紧凑下拉：控件 34dp 高、圆角 4、实色主题填充 + 1dp 细边，菜单宽 210–500、高上限 320。 填充与边框来自 Theme.kt 的 controlBackground /
 * controlBorder，与输入框、搜索框保持一致。
 */
@Composable
fun SimpleDropdown(
    value: String,
    values: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(Metrics.innerRadius)
    val animation: androidx.compose.animation.core.AnimationSpec<Color> =
        if (LocalReducedMotion.current) androidx.compose.animation.core.snap<Color>()
        else tween<Color>(Motion.fast)
    val fill by
        animateColorAsState(
            controlBackground(hovered = hovered && enabled),
            animation,
            label = "dropdownFill",
        )
    val border by animateColorAsState(controlBorderColor(), animation, label = "dropdownBorder")
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth()
                .height(Metrics.controlHeight)
                .alpha(if (enabled) 1f else .45f)
                .clip(shape)
                .background(fill, shape)
                .border(1.dp, border, shape)
                .hoverable(interaction, enabled = enabled)
                .clickable(enabled = enabled, role = Role.DropdownList) { expanded = true }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                values.find { it.first == value }?.second ?: value.ifBlank { "请选择" },
                Modifier.weight(1f),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                Icons.Outlined.ExpandMore,
                null,
                Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(
            expanded,
            { expanded = false },
            modifier = Modifier.heightIn(max = 320.dp).widthIn(min = 210.dp, max = 500.dp),
        ) {
            values.forEach { (id, label) ->
                val selected = id == value
                DropdownMenuItem(
                    {
                        Text(
                            label,
                            fontSize = 13.sp,
                            color =
                                if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    {
                        onSelect(id)
                        expanded = false
                    },
                    modifier =
                        Modifier.background(
                            if (selected) selectedRowBackground() else Color.Transparent
                        ),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 实例工具与 整合包升级：复制 / 整理报告 / 校验报告 / 升级预览。
// 四个对话框都只做展示与确认，耗时动作交给控制器任务队列或后台线程，关闭后不阻塞界面。
// ---------------------------------------------------------------------------

/** 报告里可读的字节数；只用于展示，不参与业务计算。 */
private fun reportBytes(bytes: Long): String =
    when {
        bytes >= 1073741824L -> "%.1f GB".format(bytes / 1073741824f)
        bytes >= 1048576L -> "%.1f MB".format(bytes / 1048576f)
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024f)
        else -> bytes.toString() + " B"
    }

/** 报告行：44dp 高、路径省略、行尾状态标签；只读，不提供点击或删除。 */
@Composable
private fun ReportFileRow(title: String, tag: String, color: Color) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Metrics.rowHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            title,
            Modifier.weight(1f),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Tag(tag, color)
    }
}

/** 列表过长时先只渲染前 [limit] 条，其余用一行说明；只影响展示。 */
private fun List<String>.visible(limit: Int) = take(limit)

/** 复制实例：名称 +「复制存档」「复制模组」两个开关；确认后复制在任务队列里进行。 */
@Composable
fun CopyInstanceDialog(instance: GameInstance, actions: UiActions, onDismiss: () -> Unit) {
    var name by remember(instance.id) { mutableStateOf("${instance.name} - 副本") }
    var copySaves by remember(instance.id) { mutableStateOf(true) }
    var copyMods by remember(instance.id) { mutableStateOf(true) }
    var error by remember(instance.id) { mutableStateOf<String?>(null) }
    val trimmed = name.trim()
    val valid = trimmed.isNotEmpty() && trimmed.length <= 80
    Modal("复制实例", "以「${instance.name}」为模板新建独立实例", onDismiss = onDismiss) {
        CompactTextField(
            name,
            { name = it.take(80) },
            Modifier.fillMaxWidth(),
            label = "新实例名称",
            placeholder = "${instance.name} - 副本",
        )
        SettingsSection("复制内容", "日志、崩溃报告、缓存、升级备份与链接都不会复制。") {
            SettingRow("复制存档", "saves 目录中的世界存档") {
                CompactSwitch(copySaves, { copySaves = it })
            }
            SettingRow("复制模组", "mods 目录中的模组与已停用模组") {
                CompactSwitch(copyMods, { copyMods = it })
            }
        }
        Text(
            "副本拥有独立目录、文件账本与整合包归档；复制在后台任务中进行，进度显示在日志页。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        DialogActions(
            confirmText = "开始复制",
            onConfirm = {
                try {
                    actions.controller.copyInstance(instance.id, trimmed, copySaves, copyMods)
                    onDismiss()
                } catch (failure: Exception) {
                    error = failure.message ?: "无法开始复制，请稍后重试"
                }
            },
            onCancel = onDismiss,
            enabled = valid,
            hint = if (valid) "" else "名称需要 1–80 个字符",
        )
    }
}

/** 复制存档到其他实例：目标下拉只列 MoliFox 自建、启用版本隔离的实例（排除当前实例）。 新名称默认沿用原名，可清空表示沿用；目标已有同名存档时由后端自动加序号，从不覆盖。 */
@Composable
fun CopySaveDialog(
    instance: GameInstance,
    saveName: String,
    actions: UiActions,
    onDismiss: () -> Unit,
    onStarted: (String) -> Unit = {},
) {
    val state by actions.controller.state.collectAsState()
    val targets =
        state.data.instances.filter {
            it.id != instance.id &&
                it.installed &&
                actions.controller.store.instancePath(it.id).toAbsolutePath().normalize() !=
                    actions.controller.store.instancePath(instance.id).toAbsolutePath().normalize()
        }
    val defaultName = saveNameWithoutZip(saveName)
    var targetId by remember(saveName) { mutableStateOf(targets.firstOrNull()?.id.orEmpty()) }
    var rename by remember(saveName) { mutableStateOf(defaultName) }
    var error by remember(saveName) { mutableStateOf<String?>(null) }
    val trimmed = rename.trim()
    Modal(
        "复制存档",
        "把「" + saveName + "」复制到另一个实例 · 原存档不会被移动或删除",
        onDismiss = onDismiss,
    ) {
        if (targets.isEmpty()) {
            EmptyState(
                "没有可用的目标实例",
                "需要另一个已安装且使用不同游戏目录的实例。",
                Icons.Outlined.Info,
            )
            DialogActions(confirmText = "知道了", onConfirm = onDismiss, onCancel = null)
        } else {
            SettingsSection(
                "目标实例",
                "支持外部实例；原存档保留，同名存档会自动加序号。",
            ) {
                SimpleDropdown(
                    value = targetId,
                    values = targets.map { it.id to (it.name + " · " + it.gameVersion) },
                    onSelect = {
                        targetId = it
                        error = null
                    },
                )
            }
            CompactTextField(
                rename,
                { rename = it.take(80) },
                Modifier.fillMaxWidth(),
                label = "新名称（可留空沿用原名）",
                placeholder = defaultName,
                hint = if (trimmed.isEmpty()) "留空时沿用「" + defaultName + "」" else null,
            )
            Text(
                "目标已有同名存档时会自动加序号（如「World 2」），不会覆盖。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            DialogActions(
                confirmText = "开始复制",
                onConfirm = {
                    try {
                        val jobId =
                            actions.controller.copySaveToInstance(
                                instance.id,
                                saveName,
                                targetId,
                                trimmed.ifBlank { null },
                            )
                        onStarted(jobId)
                        onDismiss()
                    } catch (failure: Exception) {
                        error = failure.message ?: "无法开始复制，请稍后重试"
                    }
                },
                onCancel = onDismiss,
                enabled = targetId.isNotBlank(),
            )
        }
    }
}

/** 存档名去掉 `.zip` 后缀（不区分大小写），只用于对话框里的默认名称展示。 */
private fun saveNameWithoutZip(name: String): String =
    if (name.endsWith(".zip", true)) name.dropLast(4) else name

/** 整理结果：报告删除了多少空目录、统计到多少 `.jar.disabled`、重复模组分组、 残留文件与可回收字节。全部只报告——对话框内不提供任何删除按钮。 */
@Composable
fun TidyReportDialog(instanceName: String, report: TidyReport, onDismiss: () -> Unit) {
    Modal("整理实例目录", instanceName, onDismiss = onDismiss) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSection("整理结果", "只清理实例目录内的空目录，模组与存档一律不动。") {
                SettingRow("删除空目录", "实例目录中不再使用的空文件夹") {
                    Text("${report.removedEmptyDirs} 个", fontSize = 13.sp)
                }
                SettingRow("已停用模组", "mods/ 中以 .jar.disabled 结尾的文件，仅统计") {
                    Text("${report.movedDisabledMods} 个", fontSize = 13.sp)
                }
                SettingRow("重复模组可回收", "内容完全相同（同 SHA-1）的多余副本") {
                    Text(reportBytes(report.totalBytes), fontSize = 13.sp)
                }
            }
            SettingsSection(
                "重复模组（${report.duplicateMods.size} 组）",
                "每组内容相同，只报告，不会自动清理或删除。",
            ) {
                if (report.duplicateMods.isEmpty()) MutedText("没有发现内容重复的模组。")
                else
                    report.duplicateMods.forEach { group ->
                        Column(
                            Modifier.fillMaxWidth()
                                .heightIn(min = Metrics.rowHeight)
                                .padding(vertical = 3.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    group.first(),
                                    Modifier.weight(1f),
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Tag("${group.size} 份", MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (group.size > 1)
                                Text(
                                    group.drop(1).joinToString(" · "),
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                        }
                    }
            }
            SettingsSection(
                "残留文件（${report.orphanFiles.size} 个）",
                "mods/ 中既不是 .jar 也不是 .jar.disabled 的文件，只报告。",
            ) {
                val orphans = report.orphanFiles.visible(50)
                if (orphans.isEmpty()) MutedText("没有发现残留文件。")
                else
                    orphans.forEach { path ->
                        ReportFileRow(
                            path,
                            "只报告",
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                if (report.orphanFiles.size > orphans.size)
                    MutedText("还有 ${report.orphanFiles.size - orphans.size} 条未显示。")
            }
        }
        Text(
            "整理不会删除模组或存档。需要清理重复模组时，请自己确认后在文件管理器中处理。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DialogActions(confirmText = "知道了", onConfirm = onDismiss, onCancel = null)
    }
}

/** 实例名转成安全的导出文件名：替换 Windows 不允许的字符，避免导出时才失败。 */
private fun exportFileStem(name: String): String =
    name
        .map { if (it < ' ' || it in "<>:\"/\\|?*") '_' else it }
        .joinToString("")
        .trim()
        .trim('.')
        .ifBlank { "instance" }

/** 一行导出开关：标题、说明与复选框，点整行都能切换。 */
@Composable
private fun ExportOptionRow(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(Metrics.innerRadius))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, { onCheckedChange(it) }, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (detail.isNotBlank())
                Text(detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 「选择内容」的一行：复选框 + 顶层条目名 + 文件数与磁盘体积；扫描中显示 `…`。 */
@Composable
private fun ExportEntryRow(
    entry: PackExportEntry,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val size =
        when {
            entry.scanning -> "…"
            entry.directory -> "${entry.fileCount} 个文件 · ${reportBytes(entry.totalBytes)}"
            else -> reportBytes(entry.totalBytes)
        }
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(Metrics.innerRadius))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked, { onCheckedChange(it) }, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color =
                    if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!enabled)
                Text(
                    if (entry.unavailable) "共用游戏目录，不写入导出包" else "仅完整备份可导出日志",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
        Text(size, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 导出整合包或完整备份：先只读预览，再由任务队列写文件，进度与取消都在日志页。
 * 「选择内容」按实例目录的真实顶层条目勾选（文件夹整段写入或整段排除），
 * 默认只勾 mods/config/resourcepacks/shaderpacks/options.txt；取消不会留下半写文件。
 */
@Composable
fun ExportMrpackDialog(
    instance: GameInstance,
    actions: UiActions,
    onDismiss: () -> Unit,
    onStarted: () -> Unit = {},
) {
    var format by remember(instance.id) { mutableStateOf(PackExportFormat.Modrinth) }
    var options by remember(instance.id) {
        mutableStateOf(PackExportOptions.defaultsFor(PackExportFormat.Modrinth))
    }
    val stem = remember(instance.name) { exportFileStem(instance.name) }
    val defaultTarget =
        remember(stem) {
            runCatching {
                    Path.of(System.getProperty("user.home", "."))
                        .toAbsolutePath()
                        .resolve("$stem.mrpack")
                        .toString()
                }
                .getOrDefault("$stem.mrpack")
        }
    var target by remember(instance.id) { mutableStateOf(defaultTarget) }
    var version by remember(instance.id) { mutableStateOf("1.0.0") }
    var plan by remember(instance.id) { mutableStateOf<MrpackExportPlan?>(null) }
    var loading by remember(instance.id) { mutableStateOf(true) }
    var error by remember(instance.id) { mutableStateOf<String?>(null) }
    var attempt by remember(instance.id) { mutableIntStateOf(0) }
    var exporting by remember(instance.id) { mutableStateOf(false) }
    // 顶层内容扫描：先列出条目，再逐步补全文件数与体积；关闭对话框即取消。
    var scan by remember(instance.id) { mutableStateOf<PackExportScan?>(null) }
    var scanError by remember(instance.id) { mutableStateOf<String?>(null) }
    var scanAttempt by remember(instance.id) { mutableIntStateOf(0) }
    // 勾选只按条目键保存；touched 记录用户手动改过的行，切换格式时不重置。
    var checked by remember(instance.id) { mutableStateOf<Set<String>>(emptySet()) }
    var touched by remember(instance.id) { mutableStateOf<Set<String>>(emptySet()) }
    // 资源管理器「复制路径」会带引号，先去掉再解析，避免把合法路径判成无效。
    val destination = runCatching { Path.of(target.trim().trim('"').trim()) }.getOrNull()
    val fileName = destination?.fileName?.toString().orEmpty()
    val packName =
        if (fileName.endsWith(".${format.extension}", true))
            fileName.dropLast(format.extension.length + 1)
        else fileName
    val versionText = version.trim()
    val validName = packName.isNotBlank() && packName.length <= 200
    val validVersion = versionText.isNotBlank() && versionText.length <= 100
    val validTarget =
        destination != null && fileName.endsWith(".${format.extension}", true) && validName
    val busy = actions.controller.busy
    LaunchedEffect(instance.id, scanAttempt) {
        scanError = null
        try {
            actions.controller.content.exportEntries(instance.id).collect { scan = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            scanError = failure.message ?: "无法读取实例目录"
        }
    }
    // 条目第一次到达时套用默认勾选（mods/config/resourcepacks/shaderpacks/options.txt）；
    // 之后扫描只更新数字，不会覆盖用户手动改动。默认值与格式无关，切换格式时保持勾选。
    val entryKeys = scan?.entries?.map { it.key }
    LaunchedEffect(entryKeys) {
        val entries = scan?.entries ?: return@LaunchedEffect
        val next = defaultExportEntries(entries).toMutableSet()
        for (key in touched) if (key in checked) next += key else next -= key
        checked = next
    }
    val selectableEntries =
        remember(scan?.entries, format) {
            scan?.entries.orEmpty().filter {
                it.selectable && (format == PackExportFormat.Backup || !it.backupOnly)
            }
        }
    val selectableKeys =
        remember(selectableEntries) { selectableEntries.mapTo(mutableSetOf()) { it.key } }
    val selectedEntries = checked.filterTo(mutableSetOf()) { it in selectableKeys }
    // 勾选日志行本身就是显式同意：只有完整备份会带着它重新预览。
    val includeLogs =
        format == PackExportFormat.Backup &&
            scan?.entries.orEmpty().any { it.backupOnly && it.key in selectedEntries }
    // 打开即预览：只读扫描实例目录，并按哈希查询可用的下载来源（网络失败只记为警告）。
    LaunchedEffect(instance.id, attempt, format, includeLogs) {
        loading = true
        error = null
        try {
            plan =
                withContext(Dispatchers.IO) {
                    actions.controller.content.previewMrpackExport(
                        instance.id,
                        MrpackExportMetadata(stem, "1.0.0"),
                        resolveDownloads = format == PackExportFormat.Modrinth,
                        resolveCurseForge = format == PackExportFormat.CurseForge,
                        includeLogs = includeLogs,
                    )
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "无法读取实例目录"
        } finally {
            loading = false
        }
    }
    val prepared = plan
    val localPaths =
        remember(prepared, format) {
            prepared?.let { exportLocalResourcePaths(it, format) }.orEmpty()
        }
    val downloads =
        if (format == PackExportFormat.Modrinth)
            prepared?.files.orEmpty().count { it.downloadUrl != null }
        else 0
    val managedFiles =
        if (format == PackExportFormat.CurseForge)
            prepared?.files.orEmpty().count { it.curseForgeProjectId != null }
        else 0
    val selection =
        remember(prepared, format, options, selectedEntries) {
            prepared?.let { exportSelectedPaths(it, format, options, selectedEntries) }.orEmpty()
        }
    val disabledMods = remember(prepared) {
        prepared?.files.orEmpty().count {
            it.path.startsWith("mods/", true) &&
                it.path.substringAfterLast('/').endsWith(".disabled", true)
        }
    }
    // 清单里的开关按实际勾选写入，完整备份的记录与归档内容保持一致。
    fun effectiveOptions(): PackExportOptions =
        options.copy(
            includeSaves = "saves" in selectedEntries,
            includeScreenshots = "screenshots" in selectedEntries,
            includeOptionsTxt =
                selectedEntries.any {
                    it == "options.txt" || it == "optionsof.txt" || it == "optionsshaders.txt"
                },
            includeLogs = includeLogs,
        )
    fun toggleEntry(key: String, on: Boolean) {
        touched = touched + key
        checked = if (on) checked + key else checked - key
    }
    val canConfirm =
        prepared != null &&
            prepared.files.isNotEmpty() &&
            selection.isNotEmpty() &&
            !loading &&
            !exporting &&
            !busy &&
            validTarget &&
            validVersion
    val currentScan = scan
    Modal(
        "导出整合包 / 完整备份",
        "${instance.name} · ${format.title}",
        width = 640.dp,
        onDismiss = onDismiss,
    ) {
        SimpleDropdown(
            format.name,
            PackExportFormat.entries.map { it.name to it.title },
            { name ->
                val chosen = PackExportFormat.valueOf(name)
                target =
                    runCatching {
                            val file = Path.of(target.trim().trim('"'))
                            file
                                .resolveSibling(
                                    file.fileName.toString().substringBeforeLast('.') +
                                        "." +
                                        chosen.extension
                                )
                                .toString()
                        }
                        .getOrDefault(target)
                // 默认勾选与格式无关：保留用户当前选择，只丢弃已不存在的条目；选项也不重置。
                checked = scan?.let { retainExportSelection(it.entries, checked) } ?: checked
                format = chosen
            },
            Modifier.fillMaxWidth(),
            enabled = !exporting,
        )
        Text(
            format.purpose,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            CompactTextField(
                target,
                { target = it },
                Modifier.weight(1f),
                label = "目标文件",
                placeholder = "$stem.${format.extension}",
                enabled = !exporting,
            )
            SecondaryButton(
                {
                    actions.run {
                        actions.choose("选择整合包导出目录", directory = true).firstOrNull()?.let {
                            target = it.resolve(fileName.ifBlank { "$stem.mrpack" }).toString()
                        }
                    }
                },
                enabled = !exporting,
            ) {
                Text("选择目录…", fontSize = 12.sp)
            }
        }
        CompactTextField(
            version,
            { version = it.take(100) },
            Modifier.fillMaxWidth(),
            label = "整合包版本",
            placeholder = "1.0.0",
            enabled = !exporting,
        )
        Text(
            "导出文件需放在游戏目录与启动器数据目录之外；导出在后台任务中写入，进度在日志页。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 中间区域单独滚动，底部按钮始终可见；顶层条目很多时也只在这里滚动。
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 顶层内容扫描与归档预览互相独立：预览联网时也能先勾选。
            when {
                currentScan == null && scanError != null ->
                    Panel(Modifier.fillMaxWidth()) {
                        EmptyState(
                            "无法读取实例目录",
                            scanError.orEmpty(),
                            Icons.Outlined.ErrorOutline,
                        ) {
                            TextButton({ scanAttempt++ }) { Text("重试") }
                        }
                    }
                currentScan == null ->
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        MutedText("正在读取实例目录的顶层内容…")
                    }
                else -> {
                    SettingsSection(
                        "选择内容",
                        "只列出实例目录的真实顶层条目；数量与体积按磁盘文件统计，勾选决定写入包里的内容。",
                    ) {
                        if (selectableEntries.isEmpty())
                            MutedText("实例目录里没有可勾选的内容。")
                        selectableEntries.forEach { entry ->
                            ExportEntryRow(
                                entry = entry,
                                checked = entry.key in selectedEntries,
                                enabled = !exporting,
                            ) { toggleEntry(entry.key, it) }
                        }
                    }
                    if (currentScan.omitted.isNotEmpty())
                        Text(
                            "不导出：${currentScan.omitted.take(8).joinToString("、")}" +
                                if (currentScan.omitted.size > 8)
                                    " 等 ${currentScan.omitted.size} 项"
                                else "",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
            }
            when {
                loading ->
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        MutedText(
                            when (format) {
                                PackExportFormat.Modrinth -> "正在读取实例文件并匹配下载来源…"
                                PackExportFormat.CurseForge -> "正在读取实例文件并匹配 CurseForge 编号…"
                                else -> "正在读取实例文件…"
                            }
                        )
                    }
                prepared == null ->
                    Panel(Modifier.fillMaxWidth()) {
                        EmptyState(
                            "无法预览这次导出",
                            error.orEmpty(),
                            Icons.Outlined.ErrorOutline,
                        ) {
                            TextButton({ attempt++ }) { Text("重试") }
                        }
                    }
                prepared.files.isEmpty() ->
                    Panel(Modifier.fillMaxWidth()) {
                        EmptyState(
                            "实例目录没有可导出的文件",
                            "日志默认不写入；启动器私有文件与实例版本文件不会进入导出包；请先安装游戏内容。",
                            Icons.Outlined.FolderOpen,
                        )
                    }
                else -> {
                    SettingsSection(
                        "预览",
                        when (format) {
                            PackExportFormat.Backup ->
                                "只读扫描实例目录；日志与崩溃报告仅在勾选后写入，疑似含登录凭据的日志会跳过。"
                            PackExportFormat.CurseForge ->
                                "只读扫描实例目录并联网匹配 CurseForge 文件编号；导出时复核文件哈希。"
                            PackExportFormat.Modrinth ->
                                "只读扫描实例目录并联网核对下载地址；导出时按同一份快照复核文件哈希。"
                            else -> "只读扫描实例目录；导出时复核文件哈希，将选中内容写入包内。"
                        },
                    ) {
                        SettingRow(
                            "将包含的文件",
                            "按上方勾选的顶层内容写入；未勾选的文件夹整段不写入",
                        ) {
                            Text("${selection.size} / ${prepared.files.size} 个", fontSize = 13.sp)
                        }
                        if (format == PackExportFormat.Modrinth)
                            SettingRow("导入时联网下载", "只写入下载链接，包里不含这些文件的内容") {
                                Text("$downloads 个", fontSize = 13.sp)
                            }
                        if (format == PackExportFormat.CurseForge)
                            SettingRow(
                                "写入受管清单",
                                if (managedFiles == 0) "没有匹配到 CurseForge 编号；全部内容放进 overrides/，不会自动更新"
                                else "已匹配 projectID/fileID；未匹配内容放进 overrides/",
                            ) {
                                Text("$managedFiles 个", fontSize = 13.sp)
                            }
                        if (format != PackExportFormat.Backup)
                            SettingRow(
                                "按本地文件分发",
                                if (localPaths.isEmpty()) "模组、资源包和光影都匹配到了下载来源"
                                else "这些模组、资源包或光影会原样写入包内",
                            ) {
                                Text(
                                    "${if (options.includeLocalResources) localPaths.size else 0} 个",
                                    fontSize = 13.sp,
                                )
                            }
                    }
                    SettingsSection("导出选项", "在已勾选内容的基础上进一步筛选写入的文件。") {
                        ExportOptionRow(
                            "仅保留已启用的模组",
                            if (disabledMods > 0)
                                "勾选后不打包 $disabledMods 个 .disabled 模组（不会改名为启用）"
                            else "勾选后不打包 .disabled 模组（不会改名为启用）",
                            options.enabledModsOnly,
                            !exporting,
                        ) { options = options.copy(enabledModsOnly = it) }
                    }
                    if (format == PackExportFormat.Backup)
                        Text(
                            "这是完整备份，不是可导入的整合包：其他启动器不会把它识别成整合包，只用于还原或留档。" +
                                "游戏运行库（assets / libraries / versions）与启动器私有数据不会写入。",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    else if (localPaths.isNotEmpty())
                        ExportOptionRow(
                            "确认有权打包这些本地资源（${localPaths.size} 个）",
                            "未走下载清单的模组、资源包或光影会原样写入包内；取消勾选则不含。",
                            options.includeLocalResources,
                            !exporting,
                        ) { options = options.copy(includeLocalResources = it) }
                    if (options.enabledModsOnly && disabledMods > 0)
                        Text(
                            "已排除 $disabledMods 个禁用的模组文件。",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    prepared.warnings.forEach { warning ->
                        Text(
                            warning,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (prepared != null)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        DialogActions(
            confirmText =
                when {
                    exporting -> if (format == PackExportFormat.Backup) "正在备份…" else "正在导出…"
                    format == PackExportFormat.Backup -> "生成备份"
                    else -> "开始导出"
                },
            enabled = canConfirm,
            onCancel = onDismiss,
            onConfirm = {
                val current = plan
                val destinationPath = destination
                if (current != null && destinationPath != null) {
                    exporting = true
                    error = null
                    actions.run {
                        try {
                            val metadata = MrpackExportMetadata(packName, versionText)
                            val wantsLogs = includeLogs
                            // 名称、版本或日志勾选改过时重新预览，确保清单与当前输入一致。
                            val fresh =
                                if (current.metadata == metadata && current.includesLogs == wantsLogs)
                                    current
                                else
                                    withContext(Dispatchers.IO) {
                                        actions.controller.content.previewMrpackExport(
                                            instance.id,
                                            metadata,
                                            resolveDownloads = format == PackExportFormat.Modrinth,
                                            resolveCurseForge = format == PackExportFormat.CurseForge,
                                            includeLogs = wantsLogs,
                                        )
                                    }
                            val chosen = effectiveOptions()
                            val local = exportLocalResourcePaths(fresh, format)
                            actions.controller.exportMrpack(
                                fresh,
                                destinationPath,
                                exportSelectedPaths(fresh, format, chosen, selectedEntries),
                                if (chosen.includeLocalResources) local else emptySet(),
                                format,
                                chosen,
                            )
                            onStarted()
                            onDismiss()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            exporting = false
                            error = failure.message ?: "无法开始导出，请稍后重试"
                        }
                    }
                }
            },
            hint =
                when {
                    loading -> "正在读取导出内容…"
                    prepared == null -> ""
                    !validTarget -> "目标文件需要是 .${format.extension} 路径"
                    !validVersion -> "版本号需要 1–100 个字符"
                    busy -> "请等待当前任务结束再导出"
                    selection.isEmpty() && selectedEntries.isNotEmpty() ->
                        "勾选的内容没有可写入的文件；本地资源需先确认分发权限"
                    selection.isEmpty() -> "请至少选择一项要写入的内容"
                    else -> "取消不会留下文件。"
                },
        )
    }
}

// ---------------------------------------------------------------------------
// 跨游戏目录移动：对话框先调用后端只读计划，再交给任务队列执行。
// 展示的数字全部来自 IsolationPlan 的真实字段；blockers 非空时禁用确认，冲突必须手动处理后
// 重新预览，不做静默覆盖。
// ---------------------------------------------------------------------------

/** 计划里的冲突计数：有冲突时用错误色，提醒必须先手动处理。 */
@Composable
private fun ConflictCountText(count: Int) {
    Text(
        "$count 个",
        fontSize = 13.sp,
        color =
            if (count > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * 把实例整体移动到另一个游戏目录：先显示目标目录与只读计划的条目数、体积、冲突数； `blockers` 非空时逐条列出中文原因并禁用确认。确认后走 `moveInstance` 任务队列，
 * 后端先复制并校验、再切换实例记录、最后把原目录移入回收站。
 */
@Composable
fun InstanceMoveDialog(
    instance: GameInstance,
    destination: Path,
    actions: UiActions,
    onDismiss: () -> Unit,
    onStarted: () -> Unit = {},
) {
    // 目标目录可以在弹窗内重新选择：同目录、磁盘根目录等会被后端拒绝，不必关闭重来。
    var target by remember(instance.id, destination) { mutableStateOf(destination) }
    var plan by remember(instance.id, destination) { mutableStateOf<IsolationPlan?>(null) }
    var loading by remember(instance.id, destination) { mutableStateOf(true) }
    var error by remember(instance.id, destination) { mutableStateOf<String?>(null) }
    var confirmError by remember(instance.id, destination) { mutableStateOf<String?>(null) }
    var confirming by remember(instance.id, destination) { mutableStateOf(false) }
    var attempt by remember(instance.id, destination) { mutableIntStateOf(0) }
    LaunchedEffect(instance.id, target, attempt) {
        // 目标目录变化或重新预览时旧计划立刻失效，避免按别的目录统计确认。
        plan = null
        loading = true
        error = null
        try {
            plan =
                withContext(Dispatchers.IO) {
                    actions.controller.planInstanceMove(instance.id, target)
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "无法预览这次移动"
        } finally {
            loading = false
        }
    }
    val prepared = plan
    val blockers = prepared?.blockers.orEmpty()
    val canConfirm =
        prepared != null &&
            !loading &&
            !confirming &&
            error == null &&
            blockers.isEmpty() &&
            !actions.controller.busy
    Modal("移动到其他游戏目录", instance.name, width = 640.dp, onDismiss = onDismiss) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                loading ->
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        MutedText("正在扫描实例目录并生成移动计划…")
                    }
                prepared == null ->
                    Panel(Modifier.fillMaxWidth()) {
                        EmptyState(
                            "无法预览这次移动",
                            error.orEmpty(),
                            Icons.Outlined.ErrorOutline,
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton({ attempt++ }) { Text("重新预览") }
                                TextButton({
                                    actions.run {
                                        actions
                                            .choose(
                                                "选择目标游戏目录（实例会放到其中的 instances 文件夹）",
                                                directory = true,
                                            )
                                            .firstOrNull()
                                            ?.let { selected ->
                                                withContext(Dispatchers.Swing) {
                                                    target = selected
                                                }
                                            }
                                    }
                                }) {
                                    Text("选择其他目录…")
                                }
                            }
                        }
                    }
                else -> {
                    Text(
                        target.toString(),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    SettingsSection("移动预览", "只读扫描实例目录，不会改动任何文件。") {
                        SettingRow("条目数", "实例目录中扫描到的全部文件") {
                            Text("${prepared.entries.size} 个", fontSize = 13.sp)
                        }
                        SettingRow("需要复制", "其中可以复制的文件") {
                            Text("${prepared.transferCount} 个", fontSize = 13.sp)
                        }
                        SettingRow("总体积", "复制后按文件数与字节数校验") {
                            Text(reportBytes(prepared.copyBytes), fontSize = 13.sp)
                        }
                        SettingRow("冲突", "目标已有同名文件或文件夹") {
                            ConflictCountText(prepared.conflictCount)
                        }
                    }
                    Text(
                        "移动会先复制到新目录并校验，再切换实例记录，最后把原目录移入回收站；" +
                            "任何一步失败都不会删除源目录。目标目录未登记时会自动加入游戏目录列表。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (blockers.isNotEmpty())
                        SettingsSection(
                            "需要先处理（${blockers.size} 项）",
                            "处理完成后重新预览；不会静默覆盖或删除文件。",
                            action = {
                                if (!confirming)
                                    TextButton({ attempt++ }) {
                                        Text("重新预览", fontSize = 12.sp)
                                    }
                            },
                        ) {
                            blockers.visible(8).forEach { blocker ->
                                Text(
                                    "· $blocker",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            if (blockers.size > 8) MutedText("还有 ${blockers.size - 8} 条未显示。")
                        }
                }
            }
        }
        if (prepared != null)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        confirmError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        DialogActions(
            confirmText = if (confirming) "正在开始…" else "开始移动",
            danger = true,
            enabled = canConfirm,
            onCancel = onDismiss,
            onConfirm = {
                confirming = true
                confirmError = null
                try {
                    actions.controller.moveInstance(instance.id, target)
                    onStarted()
                    onDismiss()
                } catch (failure: Exception) {
                    confirming = false
                    confirmError = failure.message ?: "无法开始移动，请稍后重试"
                    attempt++
                }
            },
            hint =
                when {
                    prepared == null -> ""
                    blockers.isNotEmpty() -> "目标有冲突，需要手动处理后重新预览"
                    actions.controller.busy -> "请等待当前任务结束"
                    else -> "原目录会在校验通过后移入回收站。"
                },
        )
    }
}
