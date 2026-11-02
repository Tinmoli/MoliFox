// 为公开 GitHub 资源提供加速候选；仅传输公开地址，不附加账号凭据。
package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.net.URI

private val PUBLIC_GITHUB_HOSTS = setOf("github.com", "api.github.com", "raw.githubusercontent.com")

data class GitHubSourceOptions(
    val source: String = "auto",
    val customPrefix: String = "",
)

enum class GitHubSource(
    val key: String,
    val prefix: String,
    val supportsApi: Boolean,
) {
    Auto("auto", "", true),
    Direct("direct", "", true),
    GhProxy("ghproxy", "https://gh-proxy.org", true),
    GhProxyCompat("ghproxy-com", "https://gh-proxy.com", true),
    GhFast("ghfast", "https://ghfast.top", false),
    Custom("custom", "", true),
}

fun LauncherSettings.gitHubSourceOptions(): GitHubSourceOptions = GitHubSourceOptions(githubSource, githubCustomProxy)

fun validGitHubProxy(value: String): Boolean {
    val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return false
    return uri.scheme == "https" && !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
}

fun gitHubDownloadCandidates(
    official: String,
    customPrefix: String = "",
): List<String> {
    val candidates = gitHubDownloadCandidates(official, GitHubSourceOptions())
    if (candidates == listOf(official)) return candidates
    val custom = customPrefix.trim().trimEnd('/').takeIf(::validGitHubProxy)
    return (listOf(official) + candidates.filterNot { it == official } + listOfNotNull(custom?.let { "$it/$official" }))
        .distinct()
}

/** API 不使用已实测不支持的下载专用站；每次读取当前设置，不保存另一份线路配置。 */
fun gitHubDownloadCandidates(
    official: String,
    options: GitHubSourceOptions,
): List<String> {
    val uri = URI(official)
    if (uri.scheme != "https" || uri.host !in PUBLIC_GITHUB_HOSTS) return listOf(official)
    require(uri.rawUserInfo == null) { I18n.t("networkB1.gitHubDownloadSources.credentialsNotAllowed") }
    val api = uri.host == "api.github.com"
    val selected = GitHubSource.entries.find { it.key == options.source } ?: GitHubSource.Auto
    val custom =
        options.customPrefix
            .trim()
            .trimEnd('/')
            .takeIf(::validGitHubProxy)
    val defaults = GitHubSource.entries.filter { it.prefix.isNotEmpty() && (!api || it.supportsApi) }.map { it.prefix }
    val prefixes =
        when (selected) {
            GitHubSource.Direct -> emptyList()
            GitHubSource.Auto -> defaults + listOfNotNull(custom)
            GitHubSource.Custom -> listOfNotNull(custom)
            else -> if (api && !selected.supportsApi) defaults else listOf(selected.prefix)
        }
    return (prefixes.map { "$it/$official" } + official).distinct()
}
