// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 根据已完成的资源包/光影更新生成新报告，文件名跟随下载到的 ZIP。

package dev.molifox.core.domain.packs

import dev.molifox.core.PackUpdateCandidate
import dev.molifox.core.PackUpdateItem
import dev.molifox.core.PackUpdateReport
import dev.molifox.core.PackUpdateStatus

fun completePackUpdateReport(
    report: PackUpdateReport?,
    completed: PackUpdateItem,
): PackUpdateReport? {
    val candidate = completed.candidate ?: return report
    return report?.copy(
        items =
            report.items
                .map { item ->
                    if (item.folder == completed.folder && item.filename == completed.filename) {
                        item.completed(candidate)
                    } else {
                        item
                    }
                }.distinctBy { it.folder to it.filename },
    )
}

private fun PackUpdateItem.completed(candidate: PackUpdateCandidate): PackUpdateItem =
    copy(
        filename = candidate.filename,
        currentVersion = candidate.versionNumber,
        status = PackUpdateStatus.Latest,
        latestVersion = "",
        candidate = null,
        possibleMatch = false,
        applicable = false,
        // 文件已被替换，旧的大小与修改时间不再有效。
        size = 0,
        modified = 0,
        warning = "",
    )
