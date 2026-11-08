// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 识别与项目身份匹配的 MC 百科详情页；查询失败或没有可靠对应关系时不提供链接。

package dev.molifox.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI

private const val MAX_ENCYCLOPEDIA_ENTRIES = 512
private const val ENCYCLOPEDIA_MISS_TTL_MS = 600_000L
private const val ENCYCLOPEDIA_HIT_TTL_MS = 86_400_000L

internal class EncyclopediaLinks(
    network: NetworkClient,
    private val fetch: suspend (String) -> String = network::encyclopediaText,
) {
    private data class Entry(
        val expires: Long,
        val url: String?,
    )

    private val entries = linkedMapOf<String, Entry>()
    private val lock = Mutex()

    suspend fun resolve(
        hit: ContentHit,
        declared: String = "",
    ): String? {
        val direct = directEncyclopediaUrl(declared)
        val supported = hit.type in setOf("mod", "modpack")
        return when {
            direct != null -> direct
            !supported -> null
            else ->
                lock.withLock {
                    val key = "${hit.id}:${hit.slug}"
                    val cached = entries[key]?.takeIf { it.expires > System.currentTimeMillis() }
                    if (cached != null) {
                        cached.url
                    } else {
                        val result =
                            try {
                                withTimeoutOrNull(6000) { find(hit) }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                null
                            }
                        if (entries.size >= MAX_ENCYCLOPEDIA_ENTRIES) {
                            entries.remove(entries.keys.first())
                        }
                        val ttl =
                            if (result == null) {
                                ENCYCLOPEDIA_MISS_TTL_MS
                            } else {
                                ENCYCLOPEDIA_HIT_TTL_MS
                            }
                        entries[key] = Entry(System.currentTimeMillis() + ttl, result)
                        result
                    }
                }
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
            val links = encyclopediaPageLinks(page)
            if (
                links.any { raw ->
                    val actual = runCatching { URI(raw) }.getOrNull()
                    actual != null &&
                        actual.host
                            ?.removePrefix("www.")
                            .equals(project.host.removePrefix("www."), true) &&
                        actual.path.trimEnd('/') == project.path.trimEnd('/')
                }
            ) {
                return url
            }
        }
        return null
    }

    private fun encyclopediaPageLinks(page: String): Sequence<String> {
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
                        val bytes =
                            runCatching {
                                java.util.Base64
                                    .getDecoder()
                                    .decode(encoded)
                            }.getOrElse {
                                java.util.Base64
                                    .getUrlDecoder()
                                    .decode(encoded)
                            }
                        require(bytes.size <= 2048)
                        bytes.toString(Charsets.UTF_8)
                    }.getOrNull()
                }
        return links + redirected
    }
}

internal fun directEncyclopediaUrl(value: String): String? {
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    val trustedOrigin = uri.scheme == "https" && uri.host == "www.mcmod.cn"
    val plainUrl = uri.rawUserInfo == null && uri.port == -1
    val withoutQuery = uri.rawQuery == null && uri.rawFragment == null
    val entryPage = uri.path.matches(Regex("/(class|modpack)/[1-9][0-9]*\\.html"))
    val plainAddress = plainUrl && withoutQuery
    return if (trustedOrigin && plainAddress && entryPage) {
        uri.toASCIIString()
    } else {
        null
    }
}
