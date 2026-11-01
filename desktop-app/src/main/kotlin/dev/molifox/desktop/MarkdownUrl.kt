// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责链接地址的合法性检查与域名展示。

package dev.molifox.desktop

/** 链接域名显示长度上限，避免长域名挤满一行。 */
private const val MAX_URL_HOST_CHARS = 48

/** 只放行 http/https；相对地址与伪协议按普通文字处理。 */
internal fun safeUrl(raw: String?): String? {
    val url = raw?.trim()?.trim('<', '>')?.take(2_048) ?: return null
    if (url.isEmpty()) return null
    val lower = url.lowercase()
    return if (lower.startsWith("https://") || lower.startsWith("http://")) url else null
}

internal fun urlHost(url: String): String {
    val rest = url.substringAfter("://", url)
    val host =
        rest
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .ifBlank { tr("pages.markdown.link") }
    return host.take(MAX_URL_HOST_CHARS)
}
