// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core.platform

import com.sun.jna.Platform
import java.io.IOException

/** 返回系统给出的 SRV 文本记录；空列表仅表示确认无记录，查询故障必须抛出异常。 */
internal fun interface SystemDnsResolver {
    fun querySrv(name: String): List<String>
}

internal fun systemDnsResolver(): SystemDnsResolver =
    when {
        Platform.isWindows() -> SystemDnsResolver(::queryWindowsSrvRecords)

        Platform.isLinux() && !Platform.isAndroid() -> SystemDnsResolver(::queryLinuxSrvRecords)

        else ->
            SystemDnsResolver {
                throw IOException(
                    "System SRV resolver is not implemented for ${Platform.getOSType()}",
                )
            }
    }
