// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 实例详情页与设置页签共用的启动参数校验。

package dev.molifox.desktop.instance

import dev.molifox.core.MAX_GAME_WINDOW_SIZE
import dev.molifox.core.MIN_GAME_WINDOW_SIZE
import dev.molifox.core.validateInstanceLaunchOptions

// 单项校验：失焦时只回退自己这一格，不误伤其它已经改好的字段。
internal fun invalidWindowField(value: Int?): Boolean =
    value == null ||
        (value != 0 && value !in MIN_GAME_WINDOW_SIZE..MAX_GAME_WINDOW_SIZE)

internal fun extraArgumentError(
    jvm: String,
    game: String,
): String? =
    runCatching {
        validateInstanceLaunchOptions(
            memoryMb = 0,
            width = 0,
            height = 0,
            windowMode = "inherit",
            extraJvmArgs = jvm,
            extraGameArgs = game,
        )
    }.exceptionOrNull()
        ?.message
