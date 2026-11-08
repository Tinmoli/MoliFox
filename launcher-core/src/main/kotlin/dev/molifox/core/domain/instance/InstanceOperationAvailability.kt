// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 集中实例维护和移动入口的可用性，界面只展示结果。

package dev.molifox.core.domain.instance

import dev.molifox.core.i18n.I18n

data class InstanceOperationAvailability(
    val maintain: Boolean,
    val move: Boolean,
    val moveBlockedNote: String,
)

fun instanceOperationAvailability(
    isolated: Boolean,
    external: Boolean,
    busy: Boolean,
): InstanceOperationAvailability =
    InstanceOperationAvailability(
        maintain = isolated && !busy,
        move = isolated && !external && !busy,
        moveBlockedNote =
            when {
                external -> I18n.t("instA2.operationAvailability.external")
                !isolated -> I18n.t("instA2.operationAvailability.notIsolated")
                busy -> I18n.t("instA2.operationAvailability.busy")
                else -> ""
            },
    )
