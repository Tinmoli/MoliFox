// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 集中跨目录移动预览和确认，避免与实例复制混用操作。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.visible
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import dev.molifox.desktop.MoliTextButton as TextButton
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
import java.io.UncheckedIOException
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
    val strings = LocalStrings.current
    // 目标目录可以在弹窗内重新选择：同目录、磁盘根目录等会被后端拒绝，不必关闭重来。
    var target by remember(instance.id, destination) { mutableStateOf(destination) }
    var plan by remember(instance.id, destination) { mutableStateOf<IsolationPlan?>(null) }
    var loading by remember(instance.id, destination) { mutableStateOf(true) }
    var error by remember(instance.id, destination) { mutableStateOf<String?>(null) }
    var confirmError by remember(instance.id, destination) { mutableStateOf<String?>(null) }
    var confirming by remember(instance.id, destination) { mutableStateOf(false) }
    var attempt by remember(instance.id, destination) { mutableIntStateOf(0) }
    MovePlanEffect(
        actions = actions,
        instanceId = instance.id,
        target = target,
        attempt = attempt,
        onPlan = { plan = it },
        onLoading = { loading = it },
        onError = { error = it },
    )
    val prepared = plan
    val blockers = prepared?.blockers.orEmpty()
    Modal(strings.t("dialogs.move.title"), instance.name, width = 640.dp, onDismiss = onDismiss) {
        MovePlanBody(
            prepared = prepared,
            loading = loading,
            error = error,
            target = target,
            blockers = blockers,
            confirming = confirming,
            onRetry = { attempt++ },
            onPickTarget = { pickMoveTarget(actions) { target = it } },
        )
        if (prepared != null) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        }
        confirmError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        DialogActions(
            confirmText = if (confirming) strings.t("dialogs.move.starting") else strings.t("dialogs.move.start"),
            danger = true,
            enabled =
                canStartMove(
                    prepared,
                    loading,
                    confirming,
                    error,
                    blockers,
                    actions.controller.busy,
                ),
            onCancel = onDismiss,
            onConfirm = {
                confirming = true
                confirmError = null
                val failure = startInstanceMove(actions, instance.id, target, onStarted, onDismiss)
                if (failure != null) {
                    confirming = false
                    confirmError = failure
                    attempt++
                }
            },
            hint = moveHint(prepared, blockers, actions.controller.busy),
        )
    }
}

/** 预览移动计划：目标目录变化或重新预览时旧计划立刻失效，避免按别的目录统计确认。 */
@Composable
private fun MovePlanEffect(
    actions: UiActions,
    instanceId: String,
    target: Path,
    attempt: Int,
    onPlan: (IsolationPlan?) -> Unit,
    onLoading: (Boolean) -> Unit,
    onError: (String?) -> Unit,
) {
    val strings = LocalStrings.current
    LaunchedEffect(instanceId, target, attempt) {
        onPlan(null)
        onLoading(true)
        onError(null)
        try {
            val prepared =
                withContext(Dispatchers.IO) {
                    actions.controller.planInstanceMove(instanceId, target)
                }
            onPlan(prepared)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IllegalArgumentException) {
            onError(failure.message ?: strings.t("dialogs.move.previewFailed"))
        } catch (failure: IllegalStateException) {
            onError(failure.message ?: strings.t("dialogs.move.previewFailed"))
        } catch (failure: UncheckedIOException) {
            onError(failure.message ?: strings.t("dialogs.move.previewFailed"))
        } finally {
            onLoading(false)
        }
    }
}

/** 弹窗正文：加载中、无法预览与预览详情三种状态。 */
@Composable
private fun MovePlanBody(
    prepared: IsolationPlan?,
    loading: Boolean,
    error: String?,
    target: Path,
    blockers: List<String>,
    confirming: Boolean,
    onRetry: () -> Unit,
    onPickTarget: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            loading -> MovePlanLoading()
            prepared == null -> MovePlanUnavailable(error.orEmpty(), onRetry, onPickTarget)
            else -> MovePlanDetails(prepared, target, blockers, confirming, onRetry)
        }
    }
}

@Composable
private fun MovePlanLoading() {
    val strings = LocalStrings.current
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        MutedText(strings.t("dialogs.move.scanning"))
    }
}

@Composable
private fun MovePlanUnavailable(
    error: String,
    onRetry: () -> Unit,
    onPickTarget: () -> Unit,
) {
    val strings = LocalStrings.current
    Panel(Modifier.fillMaxWidth()) {
        EmptyState(
            strings.t("dialogs.move.previewFailed"),
            error,
            Icons.Outlined.ErrorOutline,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onRetry) { Text(strings.t("dialogs.move.previewAgain")) }
                TextButton(onPickTarget) { Text(strings.t("dialogs.move.chooseOther")) }
            }
        }
    }
}

/** 预览详情：目标路径、计划统计、说明文字与阻塞项列表。 */
@Composable
private fun MovePlanDetails(
    prepared: IsolationPlan,
    target: Path,
    blockers: List<String>,
    confirming: Boolean,
    onRetry: () -> Unit,
) {
    val strings = LocalStrings.current
    Text(
        target.toString(),
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    SettingsSection(strings.t("dialogs.move.preview"), strings.t("dialogs.move.previewHint")) {
        SettingRow(strings.t("dialogs.move.entryCount"), strings.t("dialogs.move.entryCountHint")) {
            Text(strings.t("dialogs.move.items", prepared.entries.size), fontSize = 13.sp)
        }
        SettingRow(strings.t("dialogs.move.toCopy"), strings.t("dialogs.move.toCopyHint")) {
            Text(strings.t("dialogs.move.transferItems", prepared.transferCount), fontSize = 13.sp)
        }
        SettingRow(strings.t("dialogs.move.totalSize"), strings.t("dialogs.move.totalSizeHint")) {
            Text(reportBytes(prepared.copyBytes), fontSize = 13.sp)
        }
        SettingRow(strings.t("dialogs.move.conflicts"), strings.t("dialogs.move.conflictsHint")) {
            ConflictCountText(prepared.conflictCount)
        }
    }
    Text(
        strings.t("dialogs.move.note"),
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (blockers.isNotEmpty()) {
        SettingsSection(
            strings.t("dialogs.move.blockers", blockers.size),
            strings.t("dialogs.move.blockersHint"),
            action = {
                if (!confirming) {
                    TextButton(onRetry) {
                        Text(strings.t("dialogs.move.previewAgain"), fontSize = 12.sp)
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
            if (blockers.size > 8) MutedText(strings.t("dialogs.move.moreHidden", blockers.size - 8))
        }
    }
}

/** 计划里的冲突计数：有冲突时用错误色，提醒必须先手动处理。 */
@Composable
private fun ConflictCountText(count: Int) {
    val strings = LocalStrings.current
    Text(
        strings.t("dialogs.move.countItems", count),
        fontSize = 13.sp,
        color =
            if (count > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

/** 确认按钮是否可用：计划就绪、无阻塞项、没有正在进行的任务。 */
private fun canStartMove(
    prepared: IsolationPlan?,
    loading: Boolean,
    confirming: Boolean,
    error: String?,
    blockers: List<String>,
    busy: Boolean,
): Boolean =
    prepared != null &&
        !loading &&
        !confirming &&
        error == null &&
        blockers.isEmpty() &&
        !busy

/** 底部提示：说明为什么暂时不能点确认。 */
private fun moveHint(
    prepared: IsolationPlan?,
    blockers: List<String>,
    busy: Boolean,
): String =
    when {
        prepared == null -> ""
        blockers.isNotEmpty() -> tr("dialogs.move.conflictHint")
        busy -> tr("dialogs.move.busyHint")
        else -> tr("dialogs.move.trashHint")
    }

/** 提交移动任务；返回非空字符串表示失败原因，由调用方恢复确认状态。 */
private fun startInstanceMove(
    actions: UiActions,
    instanceId: String,
    target: Path,
    onStarted: () -> Unit,
    onDismiss: () -> Unit,
): String? =
    try {
        actions.controller.moveInstance(instanceId, target)
        onStarted()
        onDismiss()
        null
    } catch (failure: IllegalArgumentException) {
        failure.message ?: tr("dialogs.move.failed")
    } catch (failure: IllegalStateException) {
        failure.message ?: tr("dialogs.move.failed")
    } catch (failure: UncheckedIOException) {
        failure.message ?: tr("dialogs.move.failed")
    }

/** 选择目标游戏目录：实例会放到所选目录的 versions 文件夹中。 */
private fun pickMoveTarget(
    actions: UiActions,
    onPicked: (Path) -> Unit,
) {
    actions.run {
        actions
            .choose(
                tr("dialogs.move.chooseDir"),
                directory = true,
            ).firstOrNull()
            ?.let { selected ->
                withContext(Dispatchers.Swing) { onPicked(selected) }
            }
    }
}
