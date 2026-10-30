// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 提供每次调用都会重新读取的服务器状态入口，不保留跨页面结果。

package dev.molifox.core.domain.server

import dev.molifox.core.ServerStatus

class ServerStatusQuery(
    private val load: suspend (String) -> ServerStatus?,
) {
    suspend fun read(address: String): ServerStatus? = load(address)
}
