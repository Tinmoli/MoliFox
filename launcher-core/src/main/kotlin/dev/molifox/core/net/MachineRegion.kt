// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 按系统语言与时区判断是否处于中国大陆网络环境，只用于自动下载源的优先顺序。

package dev.molifox.core.net

import java.time.ZoneId
import java.util.Locale

internal object MachineRegion {
    private val CN_ZONES =
        setOf(
            "Asia/Shanghai",
            "Asia/Chongqing",
            "Asia/Harbin",
            "Asia/Urumqi",
            "Asia/Kashgar",
            "PRC",
            "CTT",
        )

    /** 中国网络环境下自动模式优先用镜像源。 */
    fun prefersMirror(): Boolean =
        Locale.getDefault().country.equals("CN", ignoreCase = true) ||
            ZoneId.systemDefault().id in CN_ZONES
}
