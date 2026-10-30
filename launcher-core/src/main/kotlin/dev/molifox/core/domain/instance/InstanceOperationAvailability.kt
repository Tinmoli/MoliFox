// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 集中实例维护和移动入口的可用性，界面只展示结果。

package dev.molifox.core.domain.instance

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
                external -> "外部游戏目录的实例由原启动器维护，不能移动"
                !isolated -> "共用游戏目录没有独立目录，请先把此实例设为「版本隔离」"
                busy -> "请等待当前任务结束"
                else -> ""
            },
    )
