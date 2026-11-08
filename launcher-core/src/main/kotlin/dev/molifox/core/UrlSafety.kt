// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 服务根地址的共通校验；各来源只定义自己的 scheme 规则。

package dev.molifox.core

import java.net.URI

/**
 * 服务根地址共通校验：能解析为绝对 URI、有主机，且不带用户信息、查询串与片段。
 * scheme 规则由调用方决定（GitHub 加速前缀只接受 https，CurseForge 镜像接受 http/https）。
 */
internal fun isValidServiceRootUrl(
    value: String,
    schemeAccepted: (String?) -> Boolean,
): Boolean {
    val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return false
    return schemeAccepted(uri.scheme) &&
        !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null &&
        uri.rawQuery == null &&
        uri.rawFragment == null
}
