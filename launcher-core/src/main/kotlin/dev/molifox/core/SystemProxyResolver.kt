// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责系统代理与手动代理配置的解析。

package dev.molifox.core

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/** 代理地址里没写端口时的默认值：HTTP 走 80，HTTPS 走 443，SOCKS 走 1080。 */
private const val DEFAULT_HTTP_PROXY_PORT = 80

private const val DEFAULT_HTTPS_PROXY_PORT = 443
private const val DEFAULT_SOCKS_PROXY_PORT = 1080

fun LauncherSettings.effectiveProxyMode(): String =
    proxyMode.ifBlank {
        if (proxyEnabled) "manual" else "direct"
    }

internal fun isLoopbackHost(host: String): Boolean =
    host.lowercase() in setOf("localhost", "::1", "[::1]") ||
        Regex("127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}").matches(host)

/** Read existing OS settings and Linux proxy environment variables without changing either. */
internal class SystemProxyResolver(
    private val native: ProxySelector? = ProxySelector.getDefault(),
    private val environment: (String) -> String? = System::getenv,
    private val preferNative: Boolean = System.getProperty("os.name").startsWith("Windows", true),
) : ProxySelector() {
    override fun select(uri: URI?): List<Proxy> {
        if (uri == null || uri.host == null || isLoopbackHost(uri.host)) {
            return listOf(Proxy.NO_PROXY)
        }
        val nativeSelection = runCatching { native?.select(uri) }.getOrNull().orEmpty()
        if (preferNative && nativeSelection.any { it.type() != Proxy.Type.DIRECT }) {
            return nativeSelection
        }
        if (isBypassed(uri)) {
            return listOf(Proxy.NO_PROXY)
        }
        val proxy = environmentProxy(uri)
        if (proxy != null) return listOf(proxy)
        return nativeSelection.takeIf { it.isNotEmpty() } ?: listOf(Proxy.NO_PROXY)
    }

    /** `no_proxy` / `NO_PROXY` 里任意一条命中即直连。 */
    private fun isBypassed(uri: URI): Boolean {
        val bypass = environment("no_proxy") ?: environment("NO_PROXY")
        return bypass?.split(',')?.any { entry -> matchesBypassEntry(uri, entry) } == true
    }

    /** 按 scheme 读取 `*_proxy` 环境变量；解析失败视同没有配置。 */
    private fun environmentProxy(uri: URI): Proxy? {
        val value =
            environment("${uri.scheme.lowercase()}_proxy")
                ?: environment("${uri.scheme.uppercase()}_PROXY")
                ?: environment("all_proxy")
                ?: environment("ALL_PROXY")
        return value?.takeIf { it.isNotBlank() }?.let { parseProxy(it) }
    }

    private fun parseProxy(value: String): Proxy? =
        runCatching {
            val address = URI(if (value.contains("://")) value else "http://$value")
            val type =
                when (address.scheme.lowercase()) {
                    "http" -> Proxy.Type.HTTP
                    "socks",
                    "socks5",
                    "socks5h",
                    -> Proxy.Type.SOCKS
                    else -> return@runCatching null
                }
            if (address.host.isNullOrBlank()) return@runCatching null
            val port = proxyPort(address.port, type)
            Proxy(type, InetSocketAddress.createUnresolved(address.host, port))
        }.getOrNull()

    /** 地址里写了端口就用它，否则按代理类型取默认端口。 */
    private fun proxyPort(
        configured: Int,
        type: Proxy.Type,
    ): Int =
        when {
            configured > 0 -> configured
            type == Proxy.Type.SOCKS -> DEFAULT_SOCKS_PROXY_PORT
            else -> DEFAULT_HTTP_PROXY_PORT
        }

    override fun connectFailed(
        uri: URI?,
        sa: SocketAddress?,
        error: IOException?,
    ) {
        native?.connectFailed(uri, sa, error)
    }
}

/** `no_proxy` 的单条规则：`*`、域名后缀或 `host:port` 命中即直连。 */
private fun matchesBypassEntry(
    uri: URI,
    entry: String,
): Boolean {
    val candidate = entry.trim().lowercase()
    val hostname = candidate.trimStart('.')
    val matchesHost = uri.host.equals(hostname, true) || uri.host.endsWith(".$hostname", true)
    val hostWithPort = "${uri.host}:${bypassPort(uri)}"
    return candidate == "*" || matchesHost || hostWithPort.equals(candidate, true)
}

private fun bypassPort(uri: URI): Int =
    when {
        uri.port > 0 -> uri.port
        uri.scheme == "https" -> DEFAULT_HTTPS_PROXY_PORT
        else -> DEFAULT_HTTP_PROXY_PORT
    }
