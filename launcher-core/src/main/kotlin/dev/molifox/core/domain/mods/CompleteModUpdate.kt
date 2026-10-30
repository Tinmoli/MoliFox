// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 根据已完成的模组更新生成新报告，保留禁用状态并消除重名条目。

package dev.molifox.core.domain.mods

import dev.molifox.core.ModUpdateCandidate
import dev.molifox.core.ModUpdateItem
import dev.molifox.core.ModUpdateReport
import dev.molifox.core.ModUpdateStatus

private const val DISABLED_MOD_SUFFIX = ".disabled"

fun completeModUpdateReport(
    report: ModUpdateReport?,
    completed: ModUpdateItem,
): ModUpdateReport? {
    val candidate = completed.candidate ?: return report
    return report?.copy(
        items =
            report.items
                .map { item ->
                    if (item.filename == completed.filename) {
                        item.completed(candidate)
                    } else {
                        item
                    }
                }.distinctBy { it.filename },
    )
}

private fun ModUpdateItem.completed(candidate: ModUpdateCandidate): ModUpdateItem =
    copy(
        filename =
            candidate.filename +
                if (filename.endsWith(
                        DISABLED_MOD_SUFFIX,
                        ignoreCase = true,
                    )
                ) {
                    DISABLED_MOD_SUFFIX
                } else {
                    ""
                },
        currentVersion = candidate.versionNumber,
        status = ModUpdateStatus.Latest,
        latestVersion = "",
        candidate = null,
        possibleMatch = false,
        applicable = false,
        warning = "",
    )
