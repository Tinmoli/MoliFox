// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责 CurseForge 搜索、分类筛选与搜索条目映射。

package dev.molifox.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** 搜索单页最多返回的条目数。 */
private const val SEARCH_PAGE_SIZE = 12

internal class CurseForgeBrowsing(
    private val api: CurseForgeApi,
    private val store: LauncherStore,
) {
    suspend fun search(
        query: String,
        kind: String,
        instance: GameInstance?,
        offset: Int,
        filter: ContentFilter? = null,
    ): ContentSearch {
        val index = api.classIndex()
        val classId = api.classId(index, kind, "搜索分类")
        val pageSize = minOf(SEARCH_PAGE_SIZE, CURSE_FORGE_RESULT_LIMIT - offset)
        if (pageSize <= 0) return ContentSearch(emptyList(), CURSE_FORGE_RESULT_LIMIT)
        val selection = filter ?: ContentFilter(instance?.gameVersion.orEmpty(), instance?.loader)
        // CurseForge 的类别是数字 categoryId；只发送镜像自己确认存在的编号。
        val filters = searchFilters(classId, selection, kind)
        val result =
            api.request(
                "mods/search?gameId=432&classId=$classId&searchFilter=" +
                    "${encoded(query)}&index=$offset&pageSize=$pageSize" +
                    "&sortField=${if (query.isBlank()) 6 else 2}&sortOrder=desc$filters",
            )
        val data = result.array("data").toList()
        val (hits, failures) = collectHits(data, kind)
        // 映射问题不能变成「没有结果」：一条都没认出来时按错误上报，并带上响应片段。
        if (hits.isEmpty() && data.isNotEmpty()) {
            api.fail("搜索结果映射", "全部 ${data.size} 条无法识别（${failures.first()}）", result)
        }
        if (failures.isNotEmpty()) {
            store.log(
                "CurseForge 搜索结果有 ${failures.size} 条无法识别，已跳过：${failures.first()}",
                true,
            )
        }
        val total =
            result.obj("pagination").int("totalCount").coerceIn(0, CURSE_FORGE_RESULT_LIMIT)
        if (data.isEmpty() && total > 0) {
            store.log("CurseForge 返回 totalCount=$total 但没有条目，请稍后重试：$offset", true)
        }
        return ContentSearch(hits, total)
    }

    private suspend fun searchFilters(
        classId: Int,
        selection: ContentFilter,
        kind: String,
    ): String {
        val filters = StringBuilder()
        api.resolvedGameVersion(selection.gameVersion)?.let {
            filters.append("&gameVersion=${encoded(it)}")
        }
        if (kind == "mod") {
            api.loaderId(selection.loader)?.let { filters.append("&modLoaderType=$it") }
        }
        resolveCategory(classId, selection.categories)
            ?.let { filters.append("&categoryId=$it") }
        return filters.toString()
    }

    /** 逐条映射搜索响应；失败的原因只记录，不打断整页。 */
    private fun collectHits(
        data: List<JsonElement>,
        kind: String,
    ): Pair<List<ContentHit>, List<String>> {
        val hits = mutableListOf<ContentHit>()
        val failures = mutableListOf<String>()
        for (element in data) {
            val info = element.takeIf { it.isJsonObject }?.asJsonObject
            if (info == null) {
                failures += "条目不是 JSON 对象"
                continue
            }
            val outcome = runCatching { hitOrNull(info, kind) }
            val hit = outcome.getOrNull()
            if (hit != null) {
                hits += hit
            } else {
                failures += outcome.exceptionOrNull()?.message ?: "项目标识或游戏 ID 无效"
            }
        }
        return hits to failures
    }

    /** 搜索结果中的一条。缺失的可选字段按空值处理，只有标识矛盾才跳过（不抛异常拖垮整页）。 */
    fun hitOrNull(
        info: JsonObject,
        kind: String,
    ): ContentHit? {
        info.intOrNull("gameId")?.let {
            require(it == MINECRAFT_GAME_ID) { "搜索结果不属于 Minecraft" }
        }
        val rawId = info.string("id")
        val id = "curseforge:$rawId"
        curseForgeProjectId(id)
        return ContentHit(
            id,
            info.string("slug"),
            info.string("name"),
            info.string("summary"),
            info.array("authors").joinToString(", ") { author ->
                runCatching { author.asJsonObject.string("name") }.getOrDefault("")
            },
            info
                .get("downloadCount")
                ?.takeUnless { it.isJsonNull }
                ?.let { runCatching { it.asLong }.getOrNull() }
                ?: 0,
            kind,
            info
                .obj("logo")
                .string("thumbnailUrl")
                .ifBlank { info.obj("logo").string("url") }
                .takeIf { it.isNotBlank() },
        )
    }

    fun hit(
        info: JsonObject,
        kind: String,
    ): ContentHit = requireNotNull(hitOrNull(info, kind)) { "CurseForge 项目标识无效" }

    /**
     * 某个资源类型下的可选分类。编号与名称都取自内容源本身，镜像新增的分类自动出现。
     */
    suspend fun categories(kind: String): List<ContentCategoryOption> {
        val index = api.classIndex()
        val classId = api.classId(index, kind, "分类映射")
        return categoryOptions(classId)
    }

    private suspend fun categoryOptions(classId: Int): List<ContentCategoryOption> {
        val payload = api.request("categories?gameId=432&classId=$classId")
        return payload
            .array("data")
            .mapNotNull { element -> runCatching { element.asJsonObject }.getOrNull() }
            .filter { it.intOrNull("id") != null && it.string("name").isNotBlank() }
            .map {
                ContentCategoryOption(
                    it.string("id"),
                    it.string("name"),
                    it.string("slug"),
                )
            }.distinctBy { it.id }
    }

    /** 选中的分类必须能在镜像自己的分类表里找到，否则不发 categoryId，避免静默 0 结果。 */
    private suspend fun resolveCategory(
        classId: Int,
        selected: Set<String>,
    ): Int? {
        if (selected.isEmpty()) return null
        val known = categoryOptions(classId).map { it.id }.toSet()
        val matched =
            selected.mapNotNull { it.trim().toIntOrNull() }.filter { it.toString() in known }
        if (matched.isEmpty()) {
            store.log("CurseForge 分类筛选无法映射到当前资源类型，已忽略：${selected.joinToString()}", true)
            return null
        }
        if (matched.size > 1) {
            store.log("CurseForge 一次只按一个分类筛选，已使用分类 ${matched.first()}")
        }
        return matched.first()
    }
}
