// 负责系统代理与手动代理配置的解析。

package dev.molifox.core

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

fun LauncherSettings.effectiveProxyMode(): String = proxyMode.ifBlank {
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
        if (uri == null || uri.host == null || isLoopbackHost(uri.host))
            return listOf(Proxy.NO_PROXY)
        val nativeSelection = runCatching { native?.select(uri) }.getOrNull().orEmpty()
        if (preferNative && nativeSelection.any { it.type() != Proxy.Type.DIRECT })
            return nativeSelection
        val bypass = environment("no_proxy") ?: environment("NO_PROXY")
        if (
            bypass?.split(',')?.any { entry ->
                val candidate = entry.trim().lowercase()
                val hostname = candidate.trimStart('.')
                candidate == "*" ||
                    uri.host.equals(hostname, true) ||
                    uri.host.endsWith(".$hostname", true) ||
                    "${uri.host}:${if (uri.port > 0) uri.port else if (uri.scheme == "https") 443 else 80}"
                        .equals(candidate, true)
            } == true
        )
            return listOf(Proxy.NO_PROXY)
        val value =
            environment("${uri.scheme.lowercase()}_proxy")
                ?: environment("${uri.scheme.uppercase()}_PROXY")
                ?: environment("all_proxy")
                ?: environment("ALL_PROXY")
        val proxy =
            value
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    runCatching {
                        val address = URI(if (it.contains("://")) it else "http://$it")
                        val type =
                            when (address.scheme.lowercase()) {
                                "http" -> Proxy.Type.HTTP
                                "socks",
                                "socks5",
                                "socks5h" -> Proxy.Type.SOCKS
                                else -> return@runCatching null
                            }
                        if (address.host.isNullOrBlank()) return@runCatching null
                        val port =
                            if (address.port > 0) address.port
                            else if (type == Proxy.Type.SOCKS) 1080 else 80
                        Proxy(type, InetSocketAddress.createUnresolved(address.host, port))
                    }
                        .getOrNull()
                }
        if (proxy != null) return listOf(proxy)
        return nativeSelection.takeIf { it.isNotEmpty() } ?: listOf(Proxy.NO_PROXY)
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, error: IOException?) {
        native?.connectFailed(uri, sa, error)
    }
}
