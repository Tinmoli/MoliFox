// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责运行日志页：顶部操作行、级别筛选、内容搜索与日志列表。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Subject
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.LauncherState
import dev.molifox.core.LogLevel
import dev.molifox.core.LogLine
import kotlinx.coroutines.launch

/** 控制台固定深色底（贴近终端观感），主题色只用于状态标记。 */
private val logConsoleBackground = Color(0xFF171A21)
private val logConsoleTimestamp = Color(0xFF7C8798)
private val logConsoleText = Color(0xFFD5DCE8)
private val logConsoleGame = Color(0xFFA9DCC4)
private val logConsoleTrace = Color(0xFF8D97A8)
private val logConsoleDebug = Color(0xFF8FB8E8)
private val logConsoleWarn = Color(0xFFF0C36B)
private val logConsoleError = Color(0xFFFFB4BC)
private val logConsoleFatal = Color(0xFFFF7B87)

/** 级别颜色：越严重越暖，TRACE 最安静，保持同一深色底上的可读性。 */
private fun levelColor(level: LogLevel): Color =
    when (level) {
        LogLevel.Trace -> logConsoleTrace
        LogLevel.Debug -> logConsoleDebug
        LogLevel.Info -> logConsoleText
        LogLevel.Warn -> logConsoleWarn
        LogLevel.Error -> logConsoleError
        LogLevel.Fatal -> logConsoleFatal
    }

/**
 * 操作按钮统一放在筛选区上方一行（窄窗口由 FlowRow 换行，不裁切）；
 * 下面依次是启动诊断提示、搜索 + 级别筛选、日志内容。
 * 入口仍是 ResourcePages.kt 里的 `ColumnScope.DownloadsPage`，调用点不变。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColumnScope.LogsPage(
    state: LauncherState,
    actions: UiActions,
) {
    val preferences = state.data.settings.pages.logs
    var query by remember { mutableStateOf("") }
    // 空集合 = 全部级别；点「全部」清空选择，点某一级则切换该级。
    var levels by remember { mutableStateOf(emptySet<LogLevel>()) }
    var onlyGame by remember { mutableStateOf(false) }
    var autoScroll by remember(preferences.autoScroll) { mutableStateOf(preferences.autoScroll) }
    var confirmClear by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    val logs =
        state.logs.filter {
            (levels.isEmpty() || it.level in levels) &&
                (!onlyGame || it.isGame) &&
                it.message.contains(query, true)
        }

    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LogAction(Icons.Outlined.FolderOpen, "打开日志文件夹") {
            actions.run { actions.controller.openLogsFolder() }
        }
        LogAction(Icons.Outlined.ContentCopy, "复制") {
            clipboard.setText(AnnotatedString(logs.joinToString("\n") { it.toClipboardText() }))
        }
        LogAction(Icons.Outlined.Save, "导出日志") { actions.exportLogs() }
        LogAction(Icons.Outlined.Description, "导出诊断包") { actions.exportDiagnostics() }
        LogAction(Icons.Outlined.DeleteOutline, "清空") { confirmClear = true }
        if (state.runningInstanceId != null) {
            LogAction(Icons.Outlined.Stop, "停止游戏") {
                actions.run { actions.controller.game.stop() }
            }
        }
    }

    state.launchIssue?.let { issue ->
        Panel(Modifier.fillMaxWidth(), padding = 14) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    issue.title,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                val issueInstance = state.data.selectedInstanceId
                if (issueInstance != null) {
                    SecondaryButton({ actions.previewLaunch(issueInstance) }) {
                        Text("查看启动参数", fontSize = 12.sp)
                    }
                }
            }
            Text(issue.suggestion, fontSize = 12.sp)
            if (issue.evidence.isNotBlank()) {
                Text(
                    issue.evidence,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompactSearchField(
            query,
            { query = it },
            Modifier.weight(1f),
            placeholder = "筛选日志内容",
        )
        FilterChip(
            onlyGame,
            { onlyGame = !onlyGame },
            { Text("仅游戏", fontSize = 11.sp) },
            modifier = Modifier.height(28.dp),
        )
    }

    // 标准级别档位；「全部」= 不限制。只列 LauncherStore.log 真实会写出的档位。
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LevelChip("全部", levels.isEmpty()) { levels = emptySet() }
        LogLevel.entries.forEach { level ->
            LevelChip(level.title, level in levels) {
                levels =
                    if (level in levels) {
                        levels - level
                    } else {
                        levels + level
                    }
            }
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(logs.lastOrNull()?.time, autoScroll, query, levels, onlyGame) {
        if (autoScroll && logs.isNotEmpty()) listState.scrollToItem(logs.lastIndex)
    }
    Surface(
        Modifier.fillMaxWidth().weight(1f),
        color = logConsoleBackground,
        shape = RoundedCornerShape(Metrics.cardRadius),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (state.runningInstanceId != null) {
                    Tag("游戏运行中")
                } else {
                    Tag("启动器日志", MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    logs.size.toString() + " 条",
                    fontSize = 11.sp,
                    color = logConsoleTimestamp,
                )
                Spacer(Modifier.weight(1f))
                Text("自动滚动", fontSize = 11.sp, color = logConsoleTimestamp)
                CompactSwitch(
                    autoScroll,
                    { enabled ->
                        autoScroll = enabled
                        // 与设置页写回同一偏好字段，保持自动滚动状态一致。
                        val settings = state.data.settings
                        actions.run {
                            actions.controller.saveSettings(
                                settings.copy(
                                    pages =
                                        settings.pages.copy(
                                            logs = settings.pages.logs.copy(autoScroll = enabled),
                                        ),
                                ),
                            )
                        }
                    },
                )
                SmallIconButton(
                    Icons.Outlined.KeyboardArrowDown,
                    "跳到最新一行",
                    {
                        scope.launch {
                            if (logs.isNotEmpty()) listState.animateScrollToItem(logs.lastIndex)
                        }
                    },
                    tint = logConsoleTimestamp,
                )
            }
            SelectionContainer {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (logs.isEmpty()) {
                        item(key = "log-empty") {
                            Box(Modifier.fillMaxWidth().padding(vertical = 40.dp)) {
                                Column(
                                    Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        Icons.Outlined.Subject,
                                        null,
                                        Modifier.size(24.dp),
                                        logConsoleTimestamp,
                                    )
                                    Text(
                                        if (state.logs.isEmpty()) {
                                            "等待日志输出…"
                                        } else {
                                            "没有符合当前筛选条件的日志"
                                        },
                                        color = logConsoleTimestamp,
                                        fontFamily = LocalLauncherFont.current,
                                        fontSize = 12.sp,
                                    )
                                    Text(
                                        if (state.logs.isEmpty()) {
                                            "启动游戏后这里会显示运行输出"
                                        } else {
                                            "调整级别、关键词或「仅游戏」后再试"
                                        },
                                        color = logConsoleTimestamp.copy(alpha = .8f),
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                        }
                    }
                    items(
                        count = logs.size,
                        // 日志行没有全局 id：用「时间戳 + 下标」做稳定 key。
                        // 追加新行时下标不变，同一毫秒写入相同正文也不会撞 key。
                        key = { index -> logs[index].time + "#" + index },
                    ) { index ->
                        LogRow(logs[index], preferences.fontSize)
                    }
                }
            }
        }
    }
    Text(
        logs.size.toString() + " / " + state.logs.size + " 条记录 · 已知登录令牌会自动隐藏",
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空界面日志？") },
            text = { Text("只清空当前列表；磁盘上的 logs/launcher.log 与已导出的诊断包保留。") },
            confirmButton = {
                TextButton({
                    confirmClear = false
                    actions.controller.clearLogs()
                }) { Text("清空") }
            },
            dismissButton = { TextButton({ confirmClear = false }) { Text("取消") } },
        )
    }
}

/** 一行日志：时间 + 固定宽度的级别列 + 自动换行的正文。 */
@Composable
private fun LogRow(
    line: LogLine,
    fontSize: Int,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 20.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            line.time.substringAfter('T').take(8),
            Modifier.width(64.dp),
            color = logConsoleTimestamp,
            fontFamily = FontFamily.Monospace,
            fontSize = (fontSize - 1).sp,
            lineHeight = (fontSize + 8).sp,
            maxLines = 1,
        )
        Text(
            line.level.label,
            Modifier.width(56.dp),
            color = levelColor(line.level),
            fontFamily = FontFamily.Monospace,
            fontSize = (fontSize - 2).sp,
            lineHeight = (fontSize + 8).sp,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        Text(
            line.message,
            Modifier.weight(1f),
            color =
                when {
                    line.level == LogLevel.Trace -> logConsoleTrace
                    line.level == LogLevel.Debug -> logConsoleDebug
                    line.level == LogLevel.Warn -> logConsoleWarn
                    line.level == LogLevel.Error -> logConsoleError
                    line.level == LogLevel.Fatal -> logConsoleFatal
                    line.isGame -> logConsoleGame
                    else -> logConsoleText
                },
            fontFamily = LocalLauncherFont.current,
            fontSize = fontSize.sp,
            lineHeight = (fontSize + 8).sp,
        )
    }
}

/** 与 [DownloadsPage] 同风格的操作按钮：次按钮 + 15dp 图标 + 12sp 文字。 */
@Composable
private fun LogAction(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
) {
    SecondaryButton(onClick) {
        Icon(icon, null, Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 12.sp)
    }
}

/** 级别筛选片：高度与搜索框一致，选中态沿用 Material3 FilterChip。 */
@Composable
private fun LevelChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected,
        onClick,
        { Text(text, fontSize = 11.sp) },
        modifier = Modifier.height(28.dp),
    )
}

private fun LogLine.toClipboardText(): String =
    time.substringAfter('T').take(8) + " [" + level.label + "] " + message
