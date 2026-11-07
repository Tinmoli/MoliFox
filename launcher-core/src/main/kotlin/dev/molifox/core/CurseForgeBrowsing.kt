// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责 CurseForge 搜索、分类筛选与搜索条目映射。

package dev.molifox.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import dev.molifox.core.i18n.I18n

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
        val classId = api.classId(index, kind, I18n.t("packsB1.curseForgeBrowsing.stageSearchClass"))
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
        val entries = result.array("data").toList()
        val (hits, failures) = collectHits(entries, kind)
        reportCollectOutcome(result, entries, hits.size, failures)
        val total =
            result.obj("pagination").int("totalCount").coerceIn(0, CURSE_FORGE_RESULT_LIMIT)
        if (entries.isEmpty() && total > 0) {
            store.log(I18n.t("packsB1.curseForgeBrowsing.emptyPage", total, offset), true)
        }
        return ContentSearch(hits, total)
    }

    /** 映射问题不能变成「没有结果」：一条都没认出来时按错误上报，并带上响应片段。 */
    private fun reportCollectOutcome(
        result: JsonObject,
        entries: List<JsonElement>,
        hitCount: Int,
        failures: List<String>,
    ) {
        if (hitCount == 0 && entries.isNotEmpty()) {
            api.fail(
                I18n.t("packsB1.curseForgeBrowsing.stageSearchMapping"),
                I18n.t(
                    "packsB1.curseForgeBrowsing.allEntriesUnrecognized",
                    entries.size,
                    failures.first(),
                ),
                result,
            )
        }
        if (failures.isNotEmpty()) {
            store.log(
                I18n.t(
                    "packsB1.curseForgeBrowsing.entriesSkipped",
                    failures.size,
                    failures.first(),
                ),
                true,
            )
        }
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
        entries: List<JsonElement>,
        kind: String,
    ): Pair<List<ContentHit>, List<String>> {
        val hits = mutableListOf<ContentHit>()
        val failures = mutableListOf<String>()
        for (element in entries) {
            val project = element.takeIf { it.isJsonObject }?.asJsonObject
            if (project == null) {
                failures += I18n.t("packsB1.curseForgeBrowsing.entryNotJson")
                continue
            }
            val outcome = runCatching { hitOrNull(project, kind) }
            val hit = outcome.getOrNull()
            if (hit != null) {
                hits += hit
            } else {
                failures +=
                    outcome.exceptionOrNull()?.message
                        ?: I18n.t("packsB1.curseForgeBrowsing.invalidProjectOrGameId")
            }
        }
        return hits to failures
    }

    /** 搜索结果中的一条。缺失的可选字段按空值处理，只有标识矛盾才跳过（不抛异常拖垮整页）。 */
    fun hitOrNull(
        project: JsonObject,
        kind: String,
    ): ContentHit? {
        project.intOrNull("gameId")?.let {
            require(it == MINECRAFT_GAME_ID) {
                I18n.t("packsB1.curseForgeBrowsing.searchNotMinecraft")
            }
        }
        val rawId = project.string("id")
        val id = "curseforge:$rawId"
        curseForgeProjectId(id)
        return ContentHit(
            id,
            project.string("slug"),
            project.string("name"),
            project.string("summary"),
            project.array("authors").joinToString(", ") { author ->
                runCatching { author.asJsonObject.string("name") }.getOrDefault("")
            },
            project
                .get("downloadCount")
                ?.takeUnless { it.isJsonNull }
                ?.let { runCatching { it.asLong }.getOrNull() }
                ?: 0,
            kind,
            project
                .obj("logo")
                .string("thumbnailUrl")
                .ifBlank { project.obj("logo").string("url") }
                .takeIf { it.isNotBlank() },
            categories = curseForgeCategoryNames(project),
        )
    }

    /** CurseForge 的 categories 是对象数组，界面只取 name 展示；空缺或非文本名称按没有该分类处理。 */
    private fun curseForgeCategoryNames(project: JsonObject): List<String> =
        project
            .array("categories")
            .mapNotNull { element ->
                element
                    .takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?.get("name")
                    ?.takeUnless { it.isJsonNull }
                    ?.takeIf { it.isJsonPrimitive }
                    ?.asString
                    ?.trim()
            }.filter { it.isNotEmpty() }

    fun hit(
        project: JsonObject,
        kind: String,
    ): ContentHit =
        requireNotNull(hitOrNull(project, kind)) {
            I18n.t("packsB1.curseForgeBrowsing.invalidProjectId")
        }

    /**
     * 某个资源类型下的可选分类。编号与名称都取自内容源本身，镜像新增的分类自动出现。
     */
    suspend fun categories(kind: String): List<ContentCategoryOption> {
        val index = api.classIndex()
        val classId = api.classId(index, kind, I18n.t("packsB1.curseForgeApi.stageClassMapping"))
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
            store.log(
                I18n.t(
                    "packsB1.curseForgeBrowsing.categoryFilterIgnored",
                    selected.joinToString(),
                ),
                true,
            )
            return null
        }
        if (matched.size > 1) {
            store.log(I18n.t("packsB1.curseForgeBrowsing.singleCategoryFilter", matched.first()))
        }
        return matched.first()
    }
}
