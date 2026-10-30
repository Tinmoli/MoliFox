// 为公开 GitHub 资源提供加速候选；仅传输公开地址，不附加账号凭据。
package dev.molifox.core

import java.net.URI

private val GITHUB_ACCELERATORS = listOf("https://ghfast.top", "https://gh-proxy.com")
private val PUBLIC_GITHUB_HOSTS = setOf("github.com", "api.github.com", "raw.githubusercontent.com")

fun gitHubDownloadCandidates(
    official: String,
    customPrefix: String = "",
): List<String> {
    val uri = URI(official)
    if (uri.scheme != "https" || uri.host !in PUBLIC_GITHUB_HOSTS) return listOf(official)
    require(uri.rawUserInfo == null) { "公开下载地址不能包含账号信息" }
    val custom = customPrefix.trim().trimEnd('/').takeIf(String::isNotEmpty)
    val prefixes = GITHUB_ACCELERATORS + listOfNotNull(custom)
    return (listOf(official) + prefixes.map { "$it/$official" }).distinct()
}
