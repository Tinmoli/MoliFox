// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责游戏启动进度、阶段和错误反馈。

package dev.molifox.desktop

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.DownloadJob
import dev.molifox.core.GameInstance
import dev.molifox.core.JobStatus
import kotlinx.coroutines.delay

/**
 * 开始页的启动浮窗状态。
 *
 * 这里只记录「哪个任务、哪个实例、从何时开始」，阶段与百分比一律读 [DownloadJob] 的真实进度（由 `JobContext.progress` 写入），不另造一套启动进度模型。状态挂在
 * `LaunchPage` 的 `remember` 上， 离开页面即随组合释放（启动任务本身仍在任务队列里继续）。
 */
class LaunchCardState {
    var jobId by mutableStateOf<String?>(null)
        private set

    var instanceId by mutableStateOf("")
        private set

    var instanceName by mutableStateOf("")
        private set

    var startedAtMillis by mutableLongStateOf(0L)
        private set

    /** `controller.launch()` 同步抛出的错误（尚未生成任务时也要在卡片里说明原因）。 */
    var localError by mutableStateOf<String?>(null)
        private set

    /** 任务被「清除已结束任务」等操作移除后，卡片仍保留最后一帧真实进度。 */
    var lastJob by mutableStateOf<DownloadJob?>(null)
        private set

    /** 是否真的看到过游戏进程运行：用来区分「上一次退出事件」和本次启动。 */
    var sawRunning by mutableStateOf(false)
        private set

    val visible: Boolean
        get() = jobId != null || localError != null

    fun begin(instance: GameInstance) {
        jobId = null
        localError = null
        lastJob = null
        sawRunning = false
        instanceId = instance.id
        instanceName = instance.name
        startedAtMillis = System.currentTimeMillis()
    }

    fun attach(id: String) {
        jobId = id
    }

    fun fail(message: String) {
        localError = message
    }

    fun remember(job: DownloadJob) {
        lastJob = job
    }

    fun markRunning() {
        sawRunning = true
    }

    fun clear() {
        jobId = null
        localError = null
        lastJob = null
        sawRunning = false
        instanceId = ""
        instanceName = ""
        startedAtMillis = 0L
    }
}

/** 启动阶段：标题 + 该阶段开始时的任务进度。阈值对应当前 `GameLauncher` 里的 progress 调用。 */
private data class LaunchStage(val title: String, val threshold: Float)

private val launchStages =
    listOf(
        LaunchStage("版本检查", 0f),
        LaunchStage("依赖检查", .02f),
        LaunchStage("登录", .4f),
        LaunchStage("启动进程", .7f),
    )

private enum class LaunchStagePhase {
    Waiting,
    Active,
    Done,
}

private fun currentStageIndex(job: DownloadJob?): Int {
    val progress = job?.progress ?: 0f
    return launchStages.indexOfLast { it.threshold <= progress }.coerceAtLeast(0)
}

private fun stagePhases(job: DownloadJob?): List<LaunchStagePhase> {
    if (job?.status == JobStatus.Completed) return launchStages.map { LaunchStagePhase.Done }
    val index = currentStageIndex(job)
    return launchStages.mapIndexed { position, _ ->
        when {
            position < index -> LaunchStagePhase.Done
            position == index && job?.status == JobStatus.Running -> LaunchStagePhase.Active
            else -> LaunchStagePhase.Waiting
        }
    }
}

/**
 * 启动进度浮窗：360dp 宽、约 220dp 高的圆角卡片，悬浮在开始页右下角。
 *
 * 阶段行三态：等待中灰色空心圆 / 进行中主题色箭头（脉动）/ 已完成勾选；失败时追加红色错误块与 「重试」「查看日志」，成功时显示「已启动」并在 1.5 秒后自动收起。
 */
@Composable
fun LaunchProgressCard(
    instanceName: String,
    job: DownloadJob?,
    error: String?,
    startedAtMillis: Long,
    lastLog: String,
    modifier: Modifier = Modifier,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onViewLogs: () -> Unit,
    onDismiss: () -> Unit,
) {
    val status = job?.status
    val running = status == JobStatus.Queued || status == JobStatus.Running
    val completed = status == JobStatus.Completed
    val cancelled = status == JobStatus.Cancelled
    val failure = error ?: job?.error
    val failed = failure != null || status == JobStatus.Failed
    val latestDismiss by rememberUpdatedState(onDismiss)

    // 耗时：进行中每 0.5 秒刷新，结束后停在最后一帧。
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAtMillis, running) {
        if (!running) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val elapsed = if (startedAtMillis <= 0L) 0L else (now - startedAtMillis).coerceAtLeast(0L)

    // 成功停留 1.5 秒再收起；取消后稍作停留说明结果。
    LaunchedEffect(status, error) {
        when {
            completed -> {
                delay(1500)
                latestDismiss()
            }
            cancelled -> {
                delay(1200)
                latestDismiss()
            }
        }
    }

    // 入场：120ms 淡入 + 8dp 上移；关闭动画时直接呈现。
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val appear by
        animateFloatAsState(
            targetValue = if (appeared) 1f else 0f,
            animationSpec = tween(if (LocalReducedMotion.current) 0 else Motion.fast),
            label = "launchCardAppear",
        )

    val phases = stagePhases(job)
    val progress = if (completed) 1f else (job?.progress ?: 0f).coerceIn(0f, 1f)
    val stageTitle = launchStages[currentStageIndex(job)].title
    val stateLabel =
        when {
            failed -> "启动失败"
            completed -> "已启动"
            cancelled -> "已取消"
            running -> "正在启动"
            else -> "准备启动"
        }
    val detail =
        when {
            failure != null -> "启动未能完成"
            completed -> "游戏进程已就绪"
            cancelled -> "启动已取消"
            job == null -> "准备启动…"
            status == JobStatus.Queued -> "等待任务队列…"
            else -> stageTitle + " · " + job?.detail.orEmpty()
        }

    Surface(
        modifier
            .width(360.dp)
            .heightIn(min = 220.dp)
            .graphicsLayer {
                alpha = appear
                translationY = (1f - appear) * 8f
            }
            // 吞掉卡片区域的点击，避免点击落到下方「最近游玩」行；点卡片外部不会关闭卡片。
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {},
        shape = RoundedCornerShape(Metrics.dialogRadius),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .28f)),
        shadowElevation = 14.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(28.dp)
                        .clip(CircleShape)
                        .background(
                            (if (failed) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary)
                                .copy(alpha = .12f)
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        when {
                            failed -> Icons.Outlined.ErrorOutline
                            completed -> Icons.Outlined.Check
                            else -> Icons.Outlined.PlayArrow
                        },
                        null,
                        Modifier.size(16.dp),
                        tint =
                            if (failed) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        "启动游戏",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        instanceName + " · " + stateLabel,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                SmallIconButton(Icons.Outlined.Close, "关闭启动进度", latestDismiss)
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = hairline())
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                launchStages.forEachIndexed { index, stage ->
                    LaunchStageRow(stage.title, phases[index])
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    detail,
                    Modifier.weight(1f),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    (progress * 100).toInt().toString() + "%",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = .14f),
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatElapsed(elapsed),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    lastLog.ifBlank { "暂无日志" },
                    Modifier.weight(1f),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            when {
                failed -> {
                    Surface(
                        shape = RoundedCornerShape(Metrics.innerRadius),
                        color = MaterialTheme.colorScheme.error.copy(alpha = .10f),
                        border =
                            BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.error.copy(alpha = .30f),
                            ),
                    ) {
                        Text(
                            failure ?: "启动失败，请查看日志",
                            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SecondaryButton({ onViewLogs() }) { Text("查看日志", fontSize = 12.sp) }
                        Spacer(Modifier.width(8.dp))
                        PrimaryButton("重试", Icons.Outlined.Refresh, onClick = onRetry)
                    }
                }
                completed -> LaunchCardResult(true, "已启动", "游戏进程已启动，窗口即将出现")
                cancelled -> LaunchCardResult(false, "已取消", "启动任务已停止")
                else ->
                    SecondaryButton(onCancel, Modifier.fillMaxWidth(), enabled = running) {
                        Text(if (running) "取消" else "请稍候…", fontSize = 12.sp)
                    }
            }
        }
    }
}

/** 阶段行：18dp 高，三态共用左侧 13–14dp 图标位，保证文字起始位置一致。 */
@Composable
private fun LaunchStageRow(title: String, phase: LaunchStagePhase) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (phase) {
            LaunchStagePhase.Waiting ->
                Icon(
                    Icons.Outlined.RadioButtonUnchecked,
                    null,
                    Modifier.size(13.dp),
                    colors.onSurfaceVariant.copy(alpha = .55f),
                )
            LaunchStagePhase.Done ->
                Icon(Icons.Outlined.Check, null, Modifier.size(13.dp), colors.primary)
            LaunchStagePhase.Active ->
                if (LocalReducedMotion.current)
                    Icon(Icons.Outlined.ChevronRight, null, Modifier.size(14.dp), colors.primary)
                else {
                    val transition = rememberInfiniteTransition(label = "launchStage")
                    val pulse by
                        transition.animateFloat(
                            initialValue = 1f,
                            targetValue = .35f,
                            animationSpec =
                                infiniteRepeatable(
                                    tween(650, easing = FastOutSlowInEasing),
                                    RepeatMode.Reverse,
                                ),
                            label = "launchStagePulse",
                        )
                    Icon(
                        Icons.Outlined.ChevronRight,
                        null,
                        Modifier.size(14.dp).graphicsLayer { alpha = pulse },
                        colors.primary,
                    )
                }
        }
        Spacer(Modifier.width(7.dp))
        Text(
            title,
            fontSize = 12.sp,
            fontWeight =
                if (phase == LaunchStagePhase.Active) FontWeight.SemiBold else FontWeight.Normal,
            color =
                when (phase) {
                    LaunchStagePhase.Waiting -> colors.onSurfaceVariant.copy(alpha = .72f)
                    LaunchStagePhase.Done -> colors.onSurface
                    LaunchStagePhase.Active -> colors.primary
                },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 结束态占位行：与按钮同高，避免卡片在成功/取消后跳高。 */
@Composable
private fun LaunchCardResult(success: Boolean, title: String, detail: String) {
    Row(
        Modifier.fillMaxWidth().height(Metrics.controlHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (success) Icons.Outlined.Check else Icons.Outlined.Close,
            null,
            Modifier.size(15.dp),
            if (success) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(7.dp))
        Text(
            title,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color =
                if (success) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            detail,
            Modifier.weight(1f),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 耗时显示：`mm:ss`，超过一小时显示 `h:mm:ss`。 */
private fun formatElapsed(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    val seconds = total % 60
    val minutes = (total / 60) % 60
    val hours = total / 3600
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}
