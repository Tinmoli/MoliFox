// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责内容版本筛选、兼容性判断与推荐。

package dev.molifox.core

import com.google.gson.JsonObject
import java.net.URI

/** Browsing filters never change the selected instance or weaken installation checks. */
data class ContentFilter(
    val gameVersion: String = "",
    val loader: LoaderType? = null,
    /** 分类筛选：Modrinth 的 category slug（如 tech、magic），多个分类之间是「或」关系。 */
    val categories: Set<String> = emptySet(),
) {
    init {
        require(gameVersion.length <= 100 && gameVersion.none { it.isISOControl() }) {
            "游戏版本筛选无效"
        }
        require(
            categories.size <= 60 &&
                categories.all { slug ->
                    slug.isNotEmpty() &&
                        slug.length <= 32 &&
                        slug.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
                }
        ) {
            "分类筛选无效"
        }
    }
}

/** 一个可选的资源分类。[id] 是内容源自己的数字分类编号（CurseForge），界面只显示 [name]。 */
data class ContentCategoryOption(val id: String, val name: String, val slug: String = "")

data class ContentLink(val title: String, val url: String)

fun filteredContentVersions(
    versions: List<ContentVersion>,
    filter: ContentFilter,
    channel: String = "all",
): List<ContentVersion> =
    versions
        .filter { version ->
            (filter.gameVersion.isBlank() || filter.gameVersion in version.gameVersions) &&
                (filter.loader == null ||
                    version.loaders.any { it.equals(filter.loader.name, true) }) &&
                (channel == "all" || version.channel == channel)
        }
        .sortedByDescending { it.published }

/** Prefer the publisher's featured stable file; never label a prerelease as stable. */
fun recommendedContentVersion(
    versions: List<ContentVersion>,
    target: GameInstance? = null,
    kind: String = "mod",
): ContentVersion? {
    val newest =
        versions
            .filter {
                target == null || kind == "modpack" || compatibleContentVersion(it, target, kind)
            }
            .sortedByDescending { it.published }
    return newest.firstOrNull { it.featured && it.channel == "release" }
        ?: newest.firstOrNull { it.channel == "release" }
        ?: newest.firstOrNull()
}

fun compatibleContentVersion(version: ContentVersion, instance: GameInstance, kind: String) =
    instance.installed &&
        instance.gameVersion in version.gameVersions &&
        (kind != "mod" || version.loaders.any { it.equals(instance.loader.name, true) })

internal fun contentLinks(
    hit: ContentHit,
    project: JsonObject,
    encyclopediaUrl: String? = null,
): List<ContentLink> = buildList {
    add(
        ContentLink(
            if (hit.id.startsWith("curseforge:")) "CurseForge" else "Modrinth",
            contentWebsite(hit),
        )
    )
    (encyclopediaUrl ?: directEncyclopediaUrl(project.string("wiki_url")))?.let {
        add(ContentLink("MC 百科", it))
    }
    for ((key, title) in
        listOf(
            "wiki_url" to "项目 Wiki",
            "source_url" to "源代码",
            "issues_url" to "问题反馈",
            "discord_url" to "社区",
        )) {
        val url = project.string(key)
        val uri = runCatching { URI(url) }.getOrNull() ?: continue
        if (
            uri.scheme == "https" &&
                !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null &&
                (uri.port == -1 || uri.port == 443) &&
                url.length <= 2048
        )
            add(ContentLink(title, uri.toASCIIString()))
    }
}
    .distinctBy { it.url }
