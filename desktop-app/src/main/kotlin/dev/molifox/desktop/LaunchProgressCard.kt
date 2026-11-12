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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.DownloadJob
import dev.molifox.core.JobStatus
import kotlinx.coroutines.delay

private const val PROGRESS_DEPENDENCIES_THRESHOLD = 0.02f
private const val PROGRESS_LOGIN_THRESHOLD = 0.4f
private const val PROGRESS_PROCESS_THRESHOLD = 0.7f

/** 启动阶段：标题 + 该阶段开始时的任务进度。阈值对应当前 `GameLauncher` 里的 progress 调用。 */
private data class LaunchStage(
    val title: String,
    val threshold: Float,
)

// 阶段标题随语言变化，因此每次调用时取当前语言，不缓存成顶层常量。
private fun launchStages(): List<LaunchStage> =
    listOf(
        LaunchStage(tr("pages.launch.stage.versionCheck"), 0f),
        LaunchStage(tr("pages.launch.stage.dependencies"), PROGRESS_DEPENDENCIES_THRESHOLD),
        LaunchStage(tr("pages.launch.stage.login"), PROGRESS_LOGIN_THRESHOLD),
        LaunchStage(tr("pages.launch.stage.process"), PROGRESS_PROCESS_THRESHOLD),
    )

private enum class LaunchStagePhase {
    Waiting,
    Active,
    Done,
}

private fun currentStageIndex(job: DownloadJob?): Int {
    val progress = job?.progress ?: 0f
    return launchStages().indexOfLast { it.threshold <= progress }.coerceAtLeast(0)
}

private fun stagePhases(job: DownloadJob?): List<LaunchStagePhase> {
    val stages = launchStages()
    if (job?.status == JobStatus.Completed) return stages.map { LaunchStagePhase.Done }
    val index = currentStageIndex(job)
    return stages.mapIndexed { position, _ ->
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
    onOpenTasks: () -> Unit = {},
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
            animationSpec = tween(if (LocalReducedMotion.current) 0 else Motion.FAST),
            label = "launchCardAppear",
        )

    val phases = stagePhases(job)
    val stages = launchStages()
    val progress = if (completed) 1f else (job?.progress ?: 0f).coerceIn(0f, 1f)
    val stageTitle = stages[currentStageIndex(job)].title
    val stateLabel = launchStateLabel(failed, completed, cancelled, running)
    val detail = launchDetail(failure, completed, cancelled, job, stageTitle)

    Surface(
        modifier
            .width(360.dp)
            .heightIn(min = 220.dp)
            .graphicsLayer {
                alpha = appear
                translationY = (1f - appear) * 8f
            }.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = LocalStrings.current.t("pages.tasks.open"),
                role = Role.Button,
                onClick = onOpenTasks,
            ),
        shape = RoundedCornerShape(Metrics.dialogRadius),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .28f)),
        shadowElevation = 14.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            LaunchCardHeader(instanceName, stateLabel, failed, completed, latestDismiss)
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = hairline())
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                launchStages().forEachIndexed { index, stage ->
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
                drawStopIndicator = {},
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
                    lastLog.ifBlank { LocalStrings.current.t("pages.launch.noLog") },
                    Modifier.weight(1f),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            LaunchCardActions(
                failure = failure,
                failed = failed,
                completed = completed,
                cancelled = cancelled,
                running = running,
                onCancel = onCancel,
                onRetry = onRetry,
                onViewLogs = onViewLogs,
            )
        }
    }
}

/** 卡片头部：状态图标、标题、实例名与状态短语、关闭按钮。 */
@Composable
private fun LaunchCardHeader(
    instanceName: String,
    stateLabel: String,
    failed: Boolean,
    completed: Boolean,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(
                    (
                        if (failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    ).copy(alpha = .12f),
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
                    if (failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                strings.t("pages.launch.title"),
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
        SmallIconButton(
            Icons.Outlined.Close,
            strings.t("pages.launch.close"),
            onDismiss,
        )
    }
}

/** 底部操作区：失败给日志与重试，成功或取消给结果说明，其余给取消按钮。 */
@Composable
private fun LaunchCardActions(
    failure: String?,
    failed: Boolean,
    completed: Boolean,
    cancelled: Boolean,
    running: Boolean,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onViewLogs: () -> Unit,
) {
    val strings = LocalStrings.current
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
                    failure ?: strings.t("pages.launch.failedDefault"),
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
                SecondaryButton({ onViewLogs() }) {
                    Text(strings.t("pages.launch.viewLogs"), fontSize = 12.sp)
                }
                Spacer(Modifier.width(8.dp))
                PrimaryButton(strings.t("pages.launch.retry"), Icons.Outlined.Refresh, onClick = onRetry)
            }
        }
        completed ->
            LaunchCardResult(
                true,
                strings.t("pages.launch.result.startedTitle"),
                strings.t("pages.launch.result.startedBody"),
            )
        cancelled ->
            LaunchCardResult(
                false,
                strings.t("pages.launch.result.cancelledTitle"),
                strings.t("pages.launch.result.cancelledBody"),
            )
        else ->
            SecondaryButton(onCancel, Modifier.fillMaxWidth(), enabled = running) {
                Text(
                    if (running) {
                        strings.t("pages.launch.cancel")
                    } else {
                        strings.t("pages.launch.waiting")
                    },
                    fontSize = 12.sp,
                )
            }
    }
}

/** 状态短语：失败优先于完成，其次是取消与进行中。 */
private fun launchStateLabel(
    failed: Boolean,
    completed: Boolean,
    cancelled: Boolean,
    running: Boolean,
): String =
    when {
        failed -> tr("pages.launch.state.failed")
        completed -> tr("pages.launch.state.started")
        cancelled -> tr("pages.launch.state.cancelled")
        running -> tr("pages.launch.state.running")
        else -> tr("pages.launch.state.preparing")
    }

/** 详情行：失败、完成、取消各有固定文案，其余显示阶段与任务细节。 */
private fun launchDetail(
    failure: String?,
    completed: Boolean,
    cancelled: Boolean,
    job: DownloadJob?,
    stageTitle: String,
): String =
    when {
        failure != null -> tr("pages.launch.detail.incomplete")
        completed -> tr("pages.launch.detail.ready")
        cancelled -> tr("pages.launch.detail.cancelled")
        job == null -> tr("pages.launch.detail.preparing")
        job.status == JobStatus.Queued -> tr("pages.launch.detail.queued")
        else -> stageTitle + " · " + job.detail.orEmpty()
    }

/** 阶段行：18dp 高，三态共用左侧 13–14dp 图标位，保证文字起始位置一致。 */
@Composable
private fun LaunchStageRow(
    title: String,
    phase: LaunchStagePhase,
) {
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
                if (LocalReducedMotion.current) {
                    Icon(Icons.Outlined.ChevronRight, null, Modifier.size(14.dp), colors.primary)
                } else {
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
private fun LaunchCardResult(
    success: Boolean,
    title: String,
    detail: String,
) {
    Row(
        Modifier.fillMaxWidth().height(Metrics.controlHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (success) Icons.Outlined.Check else Icons.Outlined.Close,
            null,
            Modifier.size(15.dp),
            if (success) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.width(7.dp))
        Text(
            title,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color =
                if (success) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
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
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
