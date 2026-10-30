// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 集中跨目录移动预览和确认，避免与实例复制混用操作。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.visible
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.GameInstance
import dev.molifox.core.IsolationPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.nio.file.Path

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
                                                "选择目标游戏目录（实例会放到其中的 versions 文件夹）",
                                                directory = true,
                                            ).firstOrNull()
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
                    if (blockers.isNotEmpty()) {
                        SettingsSection(
                            "需要先处理（${blockers.size} 项）",
                            "处理完成后重新预览；不会静默覆盖或删除文件。",
                            action = {
                                if (!confirming) {
                                    TextButton({ attempt++ }) {
                                        Text("重新预览", fontSize = 12.sp)
                                    }
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
        }
        if (prepared != null) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        }
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
