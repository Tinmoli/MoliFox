// 识别与项目身份匹配的 MC 百科详情页；查询失败或没有可靠对应关系时不提供链接。

package dev.molifox.core

import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal class EncyclopediaLinks(
    network: NetworkClient,
    private val fetch: suspend (String) -> String = network::encyclopediaText,
) {
    private data class Entry(val expires: Long, val url: String?)

    private val entries = linkedMapOf<String, Entry>()
    private val lock = Mutex()

    suspend fun resolve(hit: ContentHit, declared: String = ""): String? {
        directEncyclopediaUrl(declared)?.let {
            return it
        }
        if (hit.type !in setOf("mod", "modpack")) return null
        return lock.withLock {
            val key = "${hit.id}:${hit.slug}"
            entries[key]
                ?.takeIf { it.expires > System.currentTimeMillis() }
                ?.let {
                    return@withLock it.url
                }
            val result =
                try {
                    withTimeoutOrNull(6000) { find(hit) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            if (entries.size >= 512) entries.remove(entries.keys.first())
            entries[key] =
                Entry(
                    System.currentTimeMillis() + if (result == null) 600_000L else 86_400_000L,
                    result,
                )
            result
        }
    }

    private suspend fun find(hit: ContentHit): String? {
        val html = fetch("https://search.mcmod.cn/s?key=${encoded(hit.title)}")
        val type = if (hit.type == "modpack") "modpack" else "class"
        val candidates =
            Regex("(?:https?://www\\.mcmod\\.cn)?/$type/([1-9][0-9]*)\\.html")
                .findAll(html)
                .map { "https://www.mcmod.cn/$type/${it.groupValues[1]}.html" }
                .distinct()
                .take(4)
        val project = URI(contentWebsite(hit))
        for (url in candidates) {
            val page = fetch(url)
            val links =
                Regex("https?://[^\\s\"'<>]+").findAll(page).map { it.value.replace("&amp;", "&") }
            val redirected =
                Regex("(?:(?:https?:)?//)link\\.mcmod\\.cn/target/([^\\s\"'<>]+)")
                    .findAll(page)
                    .mapNotNull { match ->
                        runCatching {
                            val encoded =
                                URI("https://link.mcmod.cn/target/${match.groupValues[1]}")
                                    .path
                                    .substringAfter("/target/")
                            require(encoded.length <= 4096)
                            val bytes = runCatching {
                                java.util.Base64.getDecoder().decode(encoded)
                            }
                                .getOrElse { java.util.Base64.getUrlDecoder().decode(encoded) }
                            require(bytes.size <= 2048)
                            bytes.toString(Charsets.UTF_8)
                        }
                            .getOrNull()
                    }
            if (
                (links + redirected).any { raw ->
                    val actual = runCatching { URI(raw) }.getOrNull()
                    actual != null &&
                        actual.host
                            ?.removePrefix("www.")
                            .equals(project.host.removePrefix("www."), true) &&
                        actual.path.trimEnd('/') == project.path.trimEnd('/')
                }
            )
                return url
        }
        return null
    }
}

internal fun directEncyclopediaUrl(value: String): String? {
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    return if (
        uri.scheme == "https" &&
            uri.host == "www.mcmod.cn" &&
            uri.rawUserInfo == null &&
            uri.port == -1 &&
            uri.rawQuery == null &&
            uri.rawFragment == null &&
            uri.path.matches(Regex("/(class|modpack)/[1-9][0-9]*\\.html"))
    )
        uri.toASCIIString()
    else null
}
