// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责右下角任务进度入口与独立的下载、处理任务管理面板。

package dev.molifox.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.molifox.core.*
import java.util.Locale
import kotlinx.coroutines.delay

private const val COMPLETED_TASK_HIDE_DELAY_MILLIS = 5_000L

private fun DownloadJob.active() = status == JobStatus.Running || status == JobStatus.Queued

private fun taskPercent(jobs: List<DownloadJob>): Float =
    if (jobs.isEmpty()) 0f
    else jobs.map { it.progress.coerceIn(0f, 1f).toDouble() }.average().toFloat()

@Composable
fun TaskProgressButton(state: LauncherState, actions: UiActions, modifier: Modifier = Modifier) {
    val active = state.jobs.filter { it.active() }
    val progress = if (active.isEmpty()) 1f else taskPercent(active)
    val failed = state.jobs.any { it.status == JobStatus.Failed }
    val finishedIds = state.jobs.filterNot { it.active() }.map { it.id }.toSet()
    LaunchedEffect(finishedIds) {
        if (finishedIds.isNotEmpty()) {
            delay(COMPLETED_TASK_HIDE_DELAY_MILLIS)
            actions.controller.jobs.clearFinished(finishedIds)
        }
    }
    if (state.jobs.isEmpty()) return
    Surface(
        modifier
            .padding(bottom = 60.dp)
            .semantics { contentDescription = "打开任务中心" }
            .clickable(role = Role.Button, onClickLabel = "打开任务中心") { actions.showTasks() },
        shape = RoundedCornerShape(Metrics.cardRadius),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .35f)),
        shadowElevation = 3.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (active.isNotEmpty())
                CircularProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.size(26.dp),
                    strokeWidth = 3.dp,
                )
            else
                Icon(
                    if (failed) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle,
                    null,
                    tint =
                        if (failed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            Column {
                Text(
                    if (active.isNotEmpty()) "${active.size} 个任务进行中" else "任务已结束",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    if (active.isNotEmpty())
                        // 长阶段（数千个资源文件）必须露出阶段名，否则看起来像卡死。
                        "${(progress * 100).toInt()}% · ${active.first().detail}"
                    else "即将自动收起",
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun TaskCenterDialog(state: LauncherState, actions: UiActions) {
    val active = state.jobs.filter { it.active() }
    var cancelling by remember { mutableStateOf<DownloadJob?>(null) }
    Dialog(
        onDismissRequest = { actions.taskCenterOpen = false },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            Modifier.width(860.dp).heightIn(max = 570.dp),
            shape = RoundedCornerShape(Metrics.dialogRadius),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 1f),
            border = BorderStroke(1.dp, hairline()),
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().height(52.dp).padding(start = 20.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.CloudDownload,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("任务中心", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    SmallIconButton(
                        Icons.Outlined.Close,
                        "关闭任务中心",
                        onClick = { actions.taskCenterOpen = false },
                    )
                }
                HorizontalDivider(color = hairline())
                Row(Modifier.height(500.dp)) {
                    Column(
                        Modifier.width(184.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = .035f))
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(22.dp),
                    ) {
                        TaskStatistic(
                            "当前进度",
                            if (active.isEmpty()) "—"
                            else "${(taskPercent(active) * 100).toInt()}%",
                        )
                        TaskStatistic(
                            "下载速度",
                            if (active.any { it.totalFiles > 0 })
                                transferSize(active.sumOf { it.bytesPerSecond }) + "/s"
                            else "—",
                        )
                        TaskStatistic(
                            "剩余文件",
                            if (active.any { it.totalFiles > 0 })
                                active
                                    .sumOf { (it.totalFiles - it.completedFiles).coerceAtLeast(0) }
                                    .toString()
                            else "—",
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "下载、校验和安装会在后台继续进行。",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    VerticalDivider(color = hairline())
                    Column(
                        Modifier.weight(1f).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val visible = active
                        if (visible.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(
                                        Icons.Outlined.CloudDone,
                                        null,
                                        Modifier.size(36.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text("这里暂时没有任务", fontSize = 14.sp)
                                    Text(
                                        "安装游戏或下载资源后，进度会显示在这里。",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        } else
                            LazyColumn(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(visible, key = { it.id }) { job ->
                                    TaskDetailCard(
                                        job,
                                        onCancel = { cancelling = job },
                                    )
                                }
                            }
                    }
                }
            }
        }
    }
    cancelling?.let { job ->
        AlertDialog(
            onDismissRequest = { cancelling = null },
            title = { Text("取消任务？") },
            text = { Text(job.title + "\n取消后删除本次下载的文件和分段缓存。已有文件与其他实例共用的文件会保留。") },
            confirmButton = {
                TextButton({
                    actions.controller.jobs.cancel(job.id)
                    cancelling = null
                }) {
                    Text("确定取消")
                }
            },
            dismissButton = { TextButton({ cancelling = null }) { Text("继续任务") } },
        )
    }
}

@Composable
private fun TaskStatistic(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun TaskDetailCard(job: DownloadJob, onCancel: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(Metrics.cardRadius),
        border = BorderStroke(1.dp, hairline()),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    job.title,
                    Modifier.weight(1f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (job.active())
                    SmallIconButton(Icons.Outlined.Close, "取消任务 ${job.title}", onClick = onCancel)
            }
            Text(
                job.detail,
                fontSize = 12.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color =
                    if (job.status == JobStatus.Failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { job.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp),
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val label =
                    when (job.status) {
                        JobStatus.Queued -> "等待中"
                        JobStatus.Running -> "进行中"
                        JobStatus.Completed -> "已完成"
                        JobStatus.Cancelled -> "已取消"
                        JobStatus.Failed -> "失败"
                    }
                Text(
                    "$label · ${(job.progress * 100).toInt()}%",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (job.totalFiles > 0)
                    Text("文件 ${job.completedFiles}/${job.totalFiles}", fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                if (job.totalBytes > 0)
                    Text(
                        "${transferSize(job.downloadedBytes)} / ${transferSize(job.totalBytes)}",
                        fontSize = 11.sp,
                    )
            }
        }
    }
}

private fun transferSize(bytes: Long): String =
    when {
        bytes >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MiB", bytes / 1048576.0)
        bytes >= 1024 -> String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0)
        else -> "$bytes B"
    }
