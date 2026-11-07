// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责安装计划的挂起与确认：同名冲突先询问，依赖随「同时安装依赖」开关决定。

package dev.molifox.desktop

import dev.molifox.core.ContentInstallPlan
import java.nio.file.Path

/**
 * 安装计划已经过滤完依赖后调用：同名冲突先挂起，否则直接排队。
 * 调用方需在 UI 协程里执行。
 */
internal suspend fun installOrConfirmPlan(
    actions: UiActions,
    ui: ContentProjectUiState,
    plan: ContentInstallPlan,
    instanceRoot: Path?,
    onInstallStarted: () -> Unit,
    onError: (String) -> Unit,
) {
    val conflicts = contentFileConflicts(plan, instanceRoot)
    if (conflicts.isNotEmpty()) {
        ui.pendingPlan = plan
        ui.conflicts = conflicts
        return
    }
    ui.pendingPlan = null
    installContentPlan(actions, plan)
        .onFailure { onError(it.message ?: tr("dialogs.content.project.unavailable")) }
        .onSuccess { onInstallStarted() }
}
