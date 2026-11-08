// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责安装前依赖列表的筛选：已安装同版本的不再询问，未勾选的依赖不进入安装计划。

package dev.molifox.desktop

import dev.molifox.core.ContentInstallPlan
import dev.molifox.core.PlannedDependency

/** 需要用户决定的依赖：未安装，或已安装的版本与目标不同。 */
internal fun pendingDependencies(plan: ContentInstallPlan): List<PlannedDependency> =
    plan.dependencies.filter { !it.upToDate }

/** 「同时安装依赖」开关选中的依赖集合：开 = 全部未安装依赖；关 = 空集，只装主文件。已装同版本的仍会跳过。 */
internal fun selectedDependencyIds(
    ui: ContentProjectUiState,
    plan: ContentInstallPlan,
): Set<String> =
    if (ui.includeDependencies) {
        pendingDependencies(plan).mapTo(mutableSetOf()) { it.projectId }
    } else {
        emptySet()
    }

/**
 * 按用户勾选裁剪安装计划：主项目和已勾选依赖保留，其余依赖文件移除。
 * 同版本已安装的依赖也在这里移除，避免安装时重复下载。
 */
internal fun planWithSelectedDependencies(
    plan: ContentInstallPlan,
    selected: Set<String>,
): ContentInstallPlan {
    if (plan.dependencies.isEmpty()) return plan
    val dependencyIds = plan.dependencies.mapTo(mutableSetOf()) { it.projectId }
    return plan.copy(
        files = plan.files.filter { it.projectId !in dependencyIds || it.projectId in selected },
    )
}
