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

/** 弹窗底部按钮行：右对齐，取消为次按钮、确认为主按钮，危险操作使用错误色主按钮。 只做视觉与排版，点击语义仍由调用方传入。 */
@Composable
fun DialogActions(
    confirmText: String,
    onConfirm: () -> Unit,
    cancelText: String = "取消",
    onCancel: (() -> Unit)? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
    hint: String = "",
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
        if (onCancel != null) SecondaryButton(onCancel) { Text(cancelText) }
        Button(
            onClick = onConfirm,
            modifier = Modifier.pressScale().height(Metrics.controlHeight),
            enabled = enabled,
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
// B08 实例工具与 B07 整合包升级：复制 / 整理报告 / 校验报告 / 升级预览。
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

/** 复制存档到其他实例（B10）：目标下拉只列 MoliFox 自建、启用版本隔离的实例（排除当前实例）。 新名称默认沿用原名，可清空表示沿用；目标已有同名存档时由后端自动加序号，从不覆盖。 */
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
            it.id != instance.id && !it.externalProfile && actions.controller.store.isolated(it)
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
                "目标必须是 MoliFox 自建并启用了版本隔离的实例；外部游戏目录与当前实例不在列表里。",
                Icons.Outlined.Info,
            )
            DialogActions(confirmText = "知道了", onConfirm = onDismiss, onCancel = null)
        } else {
            SettingsSection(
                "目标实例",
                "只列出启用版本隔离的实例；外部游戏目录的实例由原启动器维护。",
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

/** 校验实例：按 B07 文件账本比对。没有账本时如实说明无法校验； 有账本时列出缺失核心文件、哈希不一致、未登记文件三组问题，缺文件可跳转「修复实例」。 */
@Composable
fun VerifyInstanceDialog(
    instance: GameInstance,
    actions: UiActions,
    onDismiss: () -> Unit,
    onRepair: () -> Unit,
) {
    var report by remember(instance.id) { mutableStateOf<VerifyReport?>(null) }
    var loading by remember(instance.id) { mutableStateOf(true) }
    var error by remember(instance.id) { mutableStateOf<String?>(null) }
    var attempt by remember(instance.id) { mutableIntStateOf(0) }
    LaunchedEffect(instance.id, attempt) {
        loading = true
        error = null
        try {
            report = withContext(Dispatchers.IO) { actions.controller.verifyInstance(instance.id) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "无法校验这个实例"
        } finally {
            loading = false
        }
    }
    val result = report
    val repairable = result != null && result.hasLedger && result.missingCoreFiles.isNotEmpty()
    Modal("校验实例", instance.name, onDismiss = onDismiss) {
        when {
            loading ->
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    MutedText("正在按整合包账本比对文件哈希…")
                }
            error != null ->
                Panel(Modifier.fillMaxWidth()) {
                    EmptyState(
                        "无法校验这个实例",
                        error.orEmpty(),
                        Icons.Outlined.CloudOff,
                    ) {
                        TextButton({ attempt++ }) { Text("重试") }
                    }
                }
            result == null -> MutedText("没有取得校验结果。")
            !result.hasLedger ->
                Panel(Modifier.fillMaxWidth()) {
                    EmptyState(
                        "该实例没有整合包账本，无法校验",
                        "只有通过整合包导入的实例才有文件账本；可以改用「整理实例目录」检查冗余文件。",
                        Icons.Outlined.Shield,
                    )
                }
            else -> {
                val clean = result.missingCoreFiles.isEmpty() && result.ledgerMismatches.isEmpty()
                Column(
                    Modifier.fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SettingsSection("校验结果", "按账本比对文件哈希；只报告，不修改任何文件。") {
                        SettingRow("缺失的核心文件", "账本登记但磁盘上不存在") {
                            Text("${result.missingCoreFiles.size} 个", fontSize = 13.sp)
                        }
                        SettingRow("内容不一致", "账本登记但哈希与记录不符") {
                            Text("${result.ledgerMismatches.size} 个", fontSize = 13.sp)
                        }
                        SettingRow("未登记文件", "账本之外、由用户或其他工具添加") {
                            Text("${result.unknownFiles.size} 个", fontSize = 13.sp)
                        }
                    }
                    if (clean) {
                        Panel(Modifier.fillMaxWidth()) {
                            EmptyState(
                                "核心文件校验通过",
                                "整合包账本中的文件都存在且哈希一致。",
                                Icons.Outlined.CheckCircle,
                            )
                        }
                    } else {
                        SettingsSection(
                            "缺失的核心文件（${result.missingCoreFiles.size} 个）",
                            "可用「修复实例」按整合包重新补齐。",
                        ) {
                            val missing = result.missingCoreFiles.visible(50)
                            if (missing.isEmpty()) MutedText("没有缺失的核心文件。")
                            else
                                missing.forEach { path ->
                                    ReportFileRow(
                                        path,
                                        "缺失",
                                        MaterialTheme.colorScheme.error,
                                    )
                                }
                            if (result.missingCoreFiles.size > missing.size)
                                MutedText("还有 ${result.missingCoreFiles.size - missing.size} 条未显示。")
                        }
                        SettingsSection(
                            "内容不一致（${result.ledgerMismatches.size} 个）",
                            "账本记录的哈希与磁盘文件不符，游戏可能无法正常启动。",
                        ) {
                            val mismatched = result.ledgerMismatches.visible(50)
                            if (mismatched.isEmpty()) MutedText("没有内容不一致的文件。")
                            else
                                mismatched.forEach { path ->
                                    ReportFileRow(
                                        path,
                                        "不一致",
                                        MaterialTheme.colorScheme.error,
                                    )
                                }
                            if (result.ledgerMismatches.size > mismatched.size)
                                MutedText(
                                    "还有 ${result.ledgerMismatches.size - mismatched.size} 条未显示。"
                                )
                        }
                    }
                    SettingsSection(
                        "未登记文件（${result.unknownFiles.size} 个）",
                        "账本之外的文件，可能来自手动安装或游戏运行，只报告。",
                    ) {
                        val unknown = result.unknownFiles.visible(50)
                        if (unknown.isEmpty()) MutedText("没有未登记文件。")
                        else
                            unknown.forEach { path ->
                                ReportFileRow(
                                    path,
                                    "未登记",
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        if (result.unknownFiles.size > unknown.size)
                            MutedText("还有 ${result.unknownFiles.size - unknown.size} 条未显示。")
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (repairable)
                Text(
                    "修复会按整合包重新校验并补齐缺失文件。",
                    Modifier.weight(1f),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            else Spacer(Modifier.weight(1f))
            if (repairable)
                SecondaryButton(onRepair, enabled = !actions.controller.busy) {
                    Icon(Icons.Outlined.Build, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("修复实例", fontSize = 12.sp)
                }
            PrimaryButton("关闭", onClick = onDismiss)
        }
    }
}

/** 升级变更的动词；与 [PackFileAction] 一一对应。 */
private fun packActionLabel(action: PackFileAction): String =
    when (action) {
        PackFileAction.Add -> "新增"
        PackFileAction.Replace -> "替换"
        PackFileAction.Remove -> "移除"
        PackFileAction.Keep -> "保留"
    }

/** 变更标签颜色：新增主题色、替换第三色、移除错误色、保留辅助色。 */
@Composable
private fun packActionColor(action: PackFileAction): Color =
    when (action) {
        PackFileAction.Add -> MaterialTheme.colorScheme.primary
        PackFileAction.Replace -> MaterialTheme.colorScheme.tertiary
        PackFileAction.Remove -> MaterialTheme.colorScheme.error
        PackFileAction.Keep -> MaterialTheme.colorScheme.onSurfaceVariant
    }

/** 文件在旧账本/磁盘上的用户状态说明；未变化的文件不重复说明。 */
private fun packUserStateLabel(state: PackUserState): String =
    when (state) {
        PackUserState.Unchanged -> ""
        PackUserState.UserModified -> "你修改过"
        PackUserState.UserAdded -> "你自行添加"
        PackUserState.UserDeleted -> "你已删除"
    }

/** 升级明细里的一行：动作标签 + 路径 + 原因/用户状态说明。 */
@Composable
private fun PackChangeRow(change: PackFileChange) {
    val note =
        listOf(
                packUserStateLabel(change.userState),
                change.reason,
                if (change.optional) "可选" else "",
            )
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().heightIn(min = Metrics.rowHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Tag(packActionLabel(change.action), packActionColor(change.action))
        Column(Modifier.weight(1f)) {
            Text(
                change.path,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (note.isNotBlank())
                Text(
                    note,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
        }
    }
}

/**
 * 整合包升级预览：展示 from→to、新增/替换/移除/保留计数、受保护文件与文件变更明细。 升级会改写实例文件，因此确认按钮为危险色并给出影响说明；
 * 计划涉及游戏版本或加载器变化时，必须勾选「我已知晓需要先切换游戏版本/加载器」才能确认。
 */
@Composable
fun PackUpgradeDialog(
    instance: GameInstance,
    archive: Path,
    actions: UiActions,
    onDismiss: () -> Unit,
    onStarted: () -> Unit = {},
) {
    var plan by remember(instance.id, archive) { mutableStateOf<PackUpgradePlan?>(null) }
    var loading by remember(instance.id, archive) { mutableStateOf(true) }
    var error by remember(instance.id, archive) { mutableStateOf<String?>(null) }
    var acknowledged by remember(instance.id, archive) { mutableStateOf(false) }
    var confirmationError by remember(instance.id, archive) { mutableStateOf<String?>(null) }
    LaunchedEffect(instance.id, archive) {
        loading = true
        error = null
        try {
            plan =
                withContext(Dispatchers.IO) {
                    actions.controller.content.previewPackUpgrade(instance.id, archive)
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "无法预览这次升级"
        } finally {
            loading = false
        }
    }
    val prepared = plan
    val requiresComponent = prepared?.requiresComponentChange == true
    val canConfirm =
        prepared != null &&
            !loading &&
            !actions.controller.busy &&
            (!requiresComponent || acknowledged)
    Modal("升级整合包", "${instance.name} · ${archive.fileName}", onDismiss = onDismiss) {
        when {
            loading ->
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    MutedText("正在读取新整合包并比对文件…")
                }
            error != null ->
                Panel(Modifier.fillMaxWidth()) {
                    EmptyState(
                        "无法预览这次升级",
                        error.orEmpty(),
                        Icons.Outlined.ErrorOutline,
                    )
                }
            prepared == null -> MutedText("没有生成升级计划。")
            else -> {
                Column(
                    Modifier.fillMaxWidth()
                        .heightIn(max = 430.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SettingsSection("版本变化", "按新旧整合包账本比对，只改动账本管理的文件。") {
                        SettingRow(
                            "整合包版本",
                            if (prepared.fromVersionId == prepared.toVersionId) "版本号未变化，仅文件内容有差异"
                            else "从旧版本升级到新版本",
                        ) {
                            Text(
                                "${prepared.fromVersionId.ifBlank { "未记录" }} → " +
                                    prepared.toVersionId.ifBlank { "未记录" },
                                fontSize = 12.sp,
                            )
                        }
                        SettingRow("受保护文件", "你修改过或自行添加的文件不会被覆盖") {
                            Text("${prepared.protectedPaths.size} 个", fontSize = 13.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Tag("新增 ${prepared.adds}", MaterialTheme.colorScheme.primary)
                            Tag("替换 ${prepared.replaces}", MaterialTheme.colorScheme.tertiary)
                            Tag("移除 ${prepared.removes}", MaterialTheme.colorScheme.error)
                            Tag("保留 ${prepared.keeps}", MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (requiresComponent)
                        Panel(
                            Modifier.fillMaxWidth(),
                            title = "需要先切换游戏版本或加载器",
                            detail = "新整合包的组件与当前实例不一致。",
                        ) {
                            Text(
                                "升级只会替换整合包文件，不会自动切换游戏版本或加载器。" +
                                    "请先在「组件维护」里切换组件，再执行升级，否则实例可能无法启动；" +
                                    "实例原有文件会被改写，升级前会自动备份，失败时回滚。",
                                fontSize = 12.sp,
                            )
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(Metrics.innerRadius))
                                    .clickable { acknowledged = !acknowledged }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(acknowledged, { acknowledged = it })
                                Text(
                                    "我已知晓需要先切换游戏版本/加载器",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    SettingsSection(
                        "文件变更（${prepared.changes.size} 条）",
                        "最多先显示 50 条；升级只处理这里列出的文件。",
                    ) {
                        val shown = prepared.changes.take(50)
                        if (shown.isEmpty()) MutedText("新旧整合包之间没有文件差异。")
                        else shown.forEach { change -> PackChangeRow(change) }
                        if (prepared.changes.size > shown.size)
                            MutedText("还有 ${prepared.changes.size - shown.size} 条未显示。")
                    }
                    Text(
                        "升级会改写实例内的整合包文件：替换为新版本、移除新包已删除的文件并按新账本记录。" +
                            "受保护文件会保留；升级前的旧文件与新归档都有备份，任何失败都会自动回滚。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        confirmationError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
        DialogActions(
            confirmText = "开始升级",
            danger = true,
            enabled = canConfirm,
            onCancel = onDismiss,
            onConfirm = {
                val current = plan
                if (current != null) {
                    try {
                        actions.controller.upgradePack(
                            instance.id,
                            archive,
                            current.archiveSha256,
                            allowComponentChange = requiresComponent && acknowledged,
                        )
                        onStarted()
                        onDismiss()
                    } catch (failure: Exception) {
                        confirmationError = failure.message ?: "无法开始升级，请稍后重试"
                    }
                }
            },
            hint =
                when {
                    prepared == null -> ""
                    requiresComponent && !acknowledged -> "请先勾选知晓组件变化"
                    else -> "升级会改动实例文件，任务可在日志页取消。"
                },
        )
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

/** 需要用户确认后才能按本地文件分发的资源：只统计模组/资源包/光影目录， 存档与截图等目录即使带资源扩展名也不会被意外打包。 */
private fun localDistributionPaths(plan: MrpackExportPlan): Set<String> =
    plan.files
        .filter {
            it.requiresPermission &&
                it.path.substringBefore('/') in setOf("mods", "resourcepacks", "shaderpacks")
        }
        .map { it.path }
        .toSet()

private fun distributionPaths(plan: MrpackExportPlan, format: PackExportFormat): Set<String> =
    if (format == PackExportFormat.Modrinth) localDistributionPaths(plan)
    else
        plan.files
            .filter {
                it.path.substringBefore('/') in setOf("mods", "resourcepacks", "shaderpacks")
            }
            .map { it.path }
            .toSet()

/**
 * 导出标准整合包（.mrpack）：先只读预览，再由任务队列写文件，进度与取消都在日志页。 文件数、需联网下载数与本地分发数全部来自 `MrpackExportPlan` 的真实字段，
 * 不提供后端不支持的选项；取消不会产生文件。
 */
@Composable
fun ExportMrpackDialog(
    instance: GameInstance,
    actions: UiActions,
    onDismiss: () -> Unit,
    onStarted: () -> Unit = {},
) {
    var format by remember(instance.id) { mutableStateOf(PackExportFormat.Modrinth) }
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
    var includeLocal by remember(instance.id) { mutableStateOf(true) }
    var plan by remember(instance.id) { mutableStateOf<MrpackExportPlan?>(null) }
    var loading by remember(instance.id) { mutableStateOf(true) }
    var error by remember(instance.id) { mutableStateOf<String?>(null) }
    var attempt by remember(instance.id) { mutableIntStateOf(0) }
    var exporting by remember(instance.id) { mutableStateOf(false) }
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
    // 打开即预览：只读扫描实例目录，并按哈希查询可用的下载来源（网络失败只记为警告）。
    LaunchedEffect(instance.id, attempt, format) {
        loading = true
        error = null
        try {
            plan =
                withContext(Dispatchers.IO) {
                    actions.controller.content.previewMrpackExport(
                        instance.id,
                        MrpackExportMetadata(stem, "1.0.0"),
                        resolveDownloads = format == PackExportFormat.Modrinth,
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
        remember(prepared, format) { prepared?.let { distributionPaths(it, format) }.orEmpty() }
    val downloads =
        if (format == PackExportFormat.Modrinth)
            prepared?.files.orEmpty().count { it.downloadUrl != null }
        else 0
    val selection =
        ((prepared?.defaultSelection ?: emptySet()) - localPaths) +
            if (includeLocal) localPaths else emptySet()
    val canConfirm =
        prepared != null &&
            prepared.files.isNotEmpty() &&
            !loading &&
            !exporting &&
            !busy &&
            validTarget &&
            validVersion
    Modal(
        "导出整合包",
        "${instance.name} · ${if (format == PackExportFormat.Modrinth) "标准 mrpack，按链接获取资源" else "MCBBS 标准 ZIP，兼容 PCL / HMCL"}",
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
                includeLocal = false
                format = chosen
            },
            Modifier.fillMaxWidth(),
            enabled = !exporting,
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
        // 预览内容可能比弹窗更高（本地资源说明与警告），单独滚动，底部按钮始终可见。
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                loading ->
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        MutedText(
                            if (format == PackExportFormat.Mcbbs) "正在读取实例文件…"
                            else "正在读取实例文件并匹配下载来源…"
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
                            "日志、缓存和启动器私有文件不会进入整合包；请先安装游戏内容。",
                            Icons.Outlined.FolderOpen,
                        )
                    }
                else -> {
                    SettingsSection(
                        "预览",
                        if (format == PackExportFormat.Mcbbs) "只读扫描实例目录；导出时复核文件哈希，将选中内容写入整合包。"
                        else "只读扫描实例目录并联网核对下载地址；导出时按同一份快照复核文件哈希。",
                    ) {
                        SettingRow("将包含的文件", "默认选择 + 已确认的本地资源；存档与截图不打包") {
                            Text(
                                "${selection.size} / ${prepared.files.size} 个",
                                fontSize = 13.sp,
                            )
                        }
                        SettingRow("导入时联网下载", "只写入下载链接，包里不含这些文件的内容") {
                            Text("$downloads 个", fontSize = 13.sp)
                        }
                        SettingRow(
                            "按本地文件分发",
                            if (localPaths.isEmpty()) "模组、资源包和光影都匹配到了下载来源"
                            else if (format == PackExportFormat.Mcbbs) "已确认分发的资源会原样写入 overrides"
                            else "未匹配来源的资源会原样写入 client-overrides",
                        ) {
                            Text(
                                "${if (includeLocal) localPaths.size else 0} 个",
                                fontSize = 13.sp,
                            )
                        }
                    }
                    if (localPaths.isNotEmpty())
                        Column(
                            Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(Metrics.innerRadius))
                                    .clickable(enabled = !exporting) {
                                        includeLocal = !includeLocal
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    includeLocal,
                                    { includeLocal = it },
                                    enabled = !exporting,
                                )
                                Text(
                                    (if (format == PackExportFormat.Mcbbs) "确认有权将这些资源打包分发"
                                    else "未匹配来源的资源按本地文件分发") + "（${localPaths.size} 个）",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                            Text(
                                (if (format == PackExportFormat.Mcbbs) "MCBBS 格式会将这些模组、资源包或光影直接打包；"
                                else "这些模组、资源包或光影按哈希查不到官方下载地址；") + "请确认你有权分发它们，取消勾选则不会写进整合包。",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
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
            confirmText = if (exporting) "正在导出…" else "开始导出",
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
                            // 名称或版本改过时重新预览，确保清单与当前输入一致。
                            val fresh =
                                if (current.metadata == metadata) current
                                else
                                    withContext(Dispatchers.IO) {
                                        actions.controller.content.previewMrpackExport(
                                            instance.id,
                                            metadata,
                                            resolveDownloads = format == PackExportFormat.Modrinth,
                                        )
                                    }
                            val local = distributionPaths(fresh, format)
                            actions.controller.exportMrpack(
                                fresh,
                                destinationPath,
                                (fresh.defaultSelection - local) +
                                    if (includeLocal) local else emptySet(),
                                if (includeLocal) local else emptySet(),
                                format,
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
                    prepared == null -> ""
                    !validTarget -> "目标文件需要是 .${format.extension} 路径"
                    !validVersion -> "版本号需要 1–100 个字符"
                    busy -> "请等待当前任务结束再导出"
                    else -> "取消不会留下文件。"
                },
        )
    }
}

// ---------------------------------------------------------------------------
// B09 隔离切换与跨游戏目录移动：两个对话框都先调用后端只读计划，再交给任务队列执行。
// 展示的数字全部来自 IsolationPlan 的真实字段；blockers 非空时禁用确认，冲突必须手动处理后
// 重新预览，不做静默覆盖。
// ---------------------------------------------------------------------------

/** 隔离相关复选框行：勾选框 + 标题 + 说明；禁用时整行 38% 透明、说明保留。 */
@Composable
private fun DialogCheckRow(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = Metrics.rowMinHeight)
            .clip(RoundedCornerShape(Metrics.innerRadius))
            .alpha(if (enabled) 1f else .38f)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (detail.isNotBlank())
                Text(
                    detail,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
    }
}

/** 计划里的冲突计数：有冲突时用错误色，提醒必须先手动处理。 */
@Composable
private fun conflictCountText(count: Int) {
    Text(
        "$count 个",
        fontSize = 13.sp,
        color =
            if (count > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * 切换隔离方式（B09）：打开即调用 `planIsolationChange` 生成只读计划，展示方向、需要搬运的 文件数与体积、移动数与冲突数；`copySaves`、`moveSource`
 * 与后端参数一一对应，改动后重新预览。 共用游戏目录只复制（moveSource 禁用并说明），`blockers` 非空时逐条列出并禁用确认。
 */
@Composable
fun IsolationChangeDialog(
    instance: GameInstance,
    actions: UiActions,
    onDismiss: () -> Unit,
    onStarted: () -> Unit = {},
) {
    val isolated = actions.controller.store.isolated(instance)
    val isolate = !isolated
    // 共用游戏目录的文件可能被其他实例共用，后端即使收到 moveSource 也只复制：界面直接禁用。
    val moveAllowed = isolated
    var copySaves by remember(instance.id) { mutableStateOf(true) }
    var moveSource by remember(instance.id) { mutableStateOf(false) }
    var plan by remember(instance.id) { mutableStateOf<IsolationPlan?>(null) }
    var loading by remember(instance.id) { mutableStateOf(true) }
    var error by remember(instance.id) { mutableStateOf<String?>(null) }
    var confirmError by remember(instance.id) { mutableStateOf<String?>(null) }
    var confirming by remember(instance.id) { mutableStateOf(false) }
    var attempt by remember(instance.id) { mutableIntStateOf(0) }
    val effectiveMoveSource = moveSource && moveAllowed
    LaunchedEffect(instance.id, copySaves, effectiveMoveSource, attempt) {
        // 参数变化后旧计划立刻失效，避免用上一次的统计确认新的参数。
        plan = null
        loading = true
        error = null
        try {
            plan =
                withContext(Dispatchers.IO) {
                    actions.controller.planIsolationChange(
                        instance.id,
                        isolate,
                        copySaves,
                        effectiveMoveSource,
                    )
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "无法预览这次切换"
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
    Modal(
        "切换隔离方式",
        "${instance.name} · ${if (isolate) "启用版本隔离" else "改用共用游戏目录"}",
        onDismiss = onDismiss,
    ) {
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
                        MutedText("正在扫描实例目录并生成迁移计划…")
                    }
                prepared == null ->
                    Panel(Modifier.fillMaxWidth()) {
                        EmptyState(
                            "无法预览这次切换",
                            error.orEmpty(),
                            Icons.Outlined.ErrorOutline,
                        ) {
                            TextButton({ attempt++ }) { Text("重新预览") }
                        }
                    }
                else -> {
                    SettingsSection(
                        "迁移方向",
                        "从 ${prepared.from.title} 到 ${prepared.to.title}，只搬运存档、模组、配置与资源包目录。",
                    ) {
                        SettingRow("方向", "切换前后使用的目录") {
                            Text(
                                "${prepared.from.title} → ${prepared.to.title}",
                                fontSize = 13.sp,
                            )
                        }
                        SettingRow(
                            "需要搬运的文件",
                            "${prepared.transferCount} 个 · ${reportBytes(prepared.copyBytes)}",
                        ) {
                            Text("${prepared.transferCount} 个", fontSize = 13.sp)
                        }
                        SettingRow("移动并删除源文件", "只在勾选「移动源文件」时执行") {
                            Text("${prepared.moveCount} 个", fontSize = 13.sp)
                        }
                        SettingRow("冲突", "目标已有不同内容或不可覆盖，必须手动处理") {
                            conflictCountText(prepared.conflictCount)
                        }
                    }
                    SettingsSection("选项", "两个选项都对应真实的迁移参数，改动后会重新预览。") {
                        DialogCheckRow(
                            title = "复制存档",
                            detail = "saves 目录中的世界存档；不勾选时存档留在原目录。",
                            checked = copySaves,
                            enabled = !confirming,
                        ) {
                            copySaves = it
                        }
                        DialogCheckRow(
                            title = "移动源文件",
                            detail =
                                if (moveAllowed) "搬运成功后删除原文件；默认关闭，只复制。" else "共用游戏目录只复制，不会删除源文件。",
                            checked = effectiveMoveSource,
                            enabled = moveAllowed && !confirming,
                        ) {
                            moveSource = it
                        }
                    }
                    Text(
                        "不会搬运：日志、崩溃报告、缓存、截图、启动器文件（.molifox-content.json 等）" +
                            "以及模组自建目录（如 journeymap/），它们都会留在原目录。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (blockers.isNotEmpty())
                        SettingsSection(
                            "需要先处理（${blockers.size} 项）",
                            "冲突需要手动处理后重新预览，不会静默覆盖或删除文件。",
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
            confirmText = if (confirming) "正在开始…" else "开始切换",
            danger = effectiveMoveSource,
            enabled = canConfirm,
            onCancel = onDismiss,
            onConfirm = {
                confirming = true
                confirmError = null
                try {
                    actions.controller.changeIsolation(
                        instance.id,
                        isolate,
                        copySaves,
                        effectiveMoveSource,
                    )
                    onStarted()
                    onDismiss()
                } catch (failure: Exception) {
                    confirming = false
                    confirmError = failure.message ?: "无法开始切换，请稍后重试"
                    // 失败可能因为磁盘在预览后发生变化：重新预览一次，让冲突如实显示出来。
                    attempt++
                }
            },
            hint =
                when {
                    prepared == null -> ""
                    blockers.isNotEmpty() -> "冲突需要手动处理后重新预览"
                    error != null -> "预览失败，请重新预览"
                    actions.controller.busy -> "请等待当前任务结束"
                    else -> "会搬运 ${prepared.transferCount} 个文件，任务可在日志页取消。"
                },
        )
    }
}

/**
 * 把实例整体移动到另一个游戏目录（B09）：先显示目标目录与只读计划的条目数、体积、冲突数； `blockers` 非空时逐条列出中文原因并禁用确认。确认后走 `moveInstance`
 * 任务队列， 后端先复制并校验、再切换实例记录、最后把原目录移入回收站。
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
                            conflictCountText(prepared.conflictCount)
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
