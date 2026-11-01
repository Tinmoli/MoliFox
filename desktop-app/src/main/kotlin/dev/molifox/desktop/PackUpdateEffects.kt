// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 资源包/光影页签的任务监听：导入删除、行内更新与一键更新。

package dev.molifox.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dev.molifox.core.LauncherState
import dev.molifox.core.PackUpdateItem
import kotlinx.coroutines.CoroutineScope

/** 监听导入/删除、行内更新与一键更新任务；把结果写回报告并通知页面刷新。 */
@Composable
internal fun PackUpdateEffects(
    liveState: LauncherState,
    watchingJob: String?,
    applyingJob: Pair<String, PackUpdateItem>?,
    batchJob: Pair<String, List<PackUpdateItem>>?,
    updates: InstanceUpdateCenter,
    scope: CoroutineScope,
    onWatchingCleared: () -> Unit,
    onRefresh: () -> Unit,
    onApplyingFinished: () -> Unit,
    onBatchFinished: () -> Unit,
    onActionError: (String) -> Unit,
    onActionNotice: (String) -> Unit,
) {
    LaunchedEffect(watchingJob, liveState.jobs.map { it.id + ":" + it.status }) {
        watchPackJob(
            watchingJob,
            liveState.jobs,
            onFailed = { onActionError(it ?: tr("pages.updateBatch.actionFailed")) },
            onCleared = onWatchingCleared,
            onFinished = onRefresh,
        )
    }
    PackUpdateJobEffects(
        liveState = liveState,
        applyingJob = applyingJob,
        batchJob = batchJob,
        updates = updates,
        scope = scope,
        onApplyingFinished = onApplyingFinished,
        onBatchFinished = onBatchFinished,
        onActionError = onActionError,
        onActionNotice = onActionNotice,
    )
}

/** 行内更新与一键更新的任务结果；与导入/删除监听分开，保持每个函数只做一件事。 */
@Composable
private fun PackUpdateJobEffects(
    liveState: LauncherState,
    applyingJob: Pair<String, PackUpdateItem>?,
    batchJob: Pair<String, List<PackUpdateItem>>?,
    updates: InstanceUpdateCenter,
    scope: CoroutineScope,
    onApplyingFinished: () -> Unit,
    onBatchFinished: () -> Unit,
    onActionError: (String) -> Unit,
    onActionNotice: (String) -> Unit,
) {
    LaunchedEffect(applyingJob, liveState.jobs.map { it.id + ":" + it.status }) {
        val pending = applyingJob ?: return@LaunchedEffect
        val job = liveState.jobs.firstOrNull { it.id == pending.first } ?: return@LaunchedEffect
        val change = packUpdateJobChange(updates.packs, pending, job) ?: return@LaunchedEffect
        change.report?.let { updates.packs = it }
        change.error?.let(onActionError)
        change.notice?.let(onActionNotice)
        onApplyingFinished()
    }
    LaunchedEffect(batchJob, liveState.jobs.map { it.id + ":" + it.status }) {
        val pending = batchJob ?: return@LaunchedEffect
        val job = liveState.jobs.firstOrNull { it.id == pending.first } ?: return@LaunchedEffect
        val change = packBatchJobChange(updates.packs, pending, job) ?: return@LaunchedEffect
        change.report?.let { updates.packs = it }
        change.error?.let(onActionError)
        change.notice?.let(onActionNotice)
        if (change.recheck) updates.checkPacks(scope)
        onBatchFinished()
    }
}
