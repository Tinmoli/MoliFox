// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责内容搜索与安装计划。

package dev.molifox.core

import com.google.gson.JsonObject
import kotlin.io.path.exists

/** 内容搜索、分类与安装计划。 */
internal interface ContentSearchApi {
    suspend fun search(
        query: String,
        kind: String,
        instanceId: String? = null,
        offset: Int = 0,
        filter: ContentFilter? = null,
    ): ContentSearch

    /** 内容源的分类列表：CurseForge 用镜像自己的编号与名称，Modrinth 由界面内置的 slug 列表提供。 */
    suspend fun contentCategories(kind: String): List<ContentCategoryOption>

    suspend fun planContent(
        hit: ContentHit,
        instanceId: String,
        selectedOptional: Set<String> = emptySet(),
        versionId: String = "",
        sources: List<ContentSource> = emptyList(),
    ): ContentInstallPlan

    suspend fun planUpdates(
        report: ContentUpdateReport,
        selected: Set<String>,
    ): ContentInstallPlan

    suspend fun installPlan(
        plan: ContentInstallPlan,
        context: JobContext,
    )
}

private const val SEARCH_QUERY_MAX_LENGTH = 200
private const val SEARCH_OFFSET_MAX = 10000

/** 实现由 [ContentService] 委托调用；方法体与原实现一致。 */
internal abstract class ContentSearchActions(
    core: ContentServiceCore,
) : ContentActionsBase(core),
    ContentSearchApi {
    override suspend fun search(
        query: String,
        kind: String,
        instanceId: String?,
        offset: Int,
        filter: ContentFilter?,
    ): ContentSearch {
        require(
            query.length <= SEARCH_QUERY_MAX_LENGTH &&
                kind in listOf("mod", "modpack", "resourcepack", "shader", "datapack") &&
                offset in 0..SEARCH_OFFSET_MAX,
        ) {
            "搜索参数无效"
        }
        if (store.data.settings.contentDownloadSource == "curseforge") {
            return curseForge.search(query, kind, instanceId?.let(store::instance), offset, filter)
        }
        val selection = filter ?: defaultContentFilter(kind, instanceId)
        val facets = searchFacets(kind, selection)
        val searchIndex = if (query.isBlank()) "downloads" else "relevance"
        val parameters =
            "query=${encoded(query)}&facets=${encoded(gson.toJson(facets))}" +
                "&limit=12&offset=$offset&index=$searchIndex"
        val result = network.json("$api/search?$parameters").asJsonObject
        return ContentSearch(contentHits(result), result.int("total_hits"))
    }

    /** 内容源的分类列表：CurseForge 用镜像自己的编号与名称，Modrinth 由界面内置的 slug 列表提供。 */
    override suspend fun contentCategories(kind: String): List<ContentCategoryOption> =
        if (store.data.settings.contentDownloadSource == "curseforge") {
            curseForge.categories(kind)
        } else {
            emptyList()
        }

    private fun defaultContentFilter(
        kind: String,
        instanceId: String?,
    ): ContentFilter =
        instanceId
            ?.takeIf { kind != "modpack" }
            ?.let {
                store.instance(it).let { instance ->
                    ContentFilter(instance.gameVersion, instance.loader)
                }
            }
            ?: ContentFilter()

    /** 构造 Modrinth facets：同一子列表内是「或」，子列表之间是「与」。 */
    private fun searchFacets(
        kind: String,
        selection: ContentFilter,
    ): List<List<String>> {
        val facets = mutableListOf(listOf("project_type:$kind"))
        // 版本筛选只发送真实版本号：实例目录名（TwilightMC-26.3v1.0）解析成 26.3，解析不到就不筛。
        val gameVersion = minecraftVersionTag(selection.gameVersion)
        if (selection.gameVersion.isNotBlank()) {
            if (gameVersion == null) {
                store.log("Modrinth 已忽略无法识别的游戏版本筛选：${selection.gameVersion}", true)
            } else {
                facets += listOf("versions:${gson.toJson(gameVersion)}")
            }
        }
        if (kind == "mod" && selection.loader != null && selection.loader != LoaderType.Vanilla) {
            facets += listOf("categories:${selection.loader.name.lowercase()}")
        }
        // 分类：同一子列表内的条件是「或」，与其他子列表之间是「与」，所以多选分类合成一组。
        if (selection.categories.isNotEmpty()) {
            facets += selection.categories.sorted().map { "categories:${gson.toJson(it)}" }
        }
        return facets
    }

    private fun contentHits(result: JsonObject): List<ContentHit> =
        result.array("hits").map { element ->
            val item = element.asJsonObject
            ContentHit(
                item.string("project_id"),
                item.string("slug"),
                item.string("title"),
                item.string("description"),
                item.string("author"),
                item.get("downloads")?.asLong ?: 0,
                item.string("project_type"),
                item.string("icon_url").takeIf { it.isNotBlank() },
            )
        }

    override suspend fun planContent(
        hit: ContentHit,
        instanceId: String,
        selectedOptional: Set<String>,
        versionId: String,
        sources: List<ContentSource>,
    ): ContentInstallPlan =
        ContentInstallPlanner(store, network, api, backend)
            .plan(hit, instanceId, selectedOptional, versionId, sources)

    override suspend fun planUpdates(
        report: ContentUpdateReport,
        selected: Set<String>,
    ): ContentInstallPlan {
        val root = store.instancePath(report.instanceId)
        val registryFile = root.resolve(".molifox-content.json")
        require(
            contentSignature(store, store.instance(report.instanceId)) == report.signature &&
                (if (registryFile.exists()) digest(registryFile) else "") == report.registryHash,
        ) {
            "资源列表已改变，请重新检查更新"
        }
        val items = report.items.filter { it.key in selected }
        require(
            items.isNotEmpty() &&
                items.size == selected.size &&
                items.all { it.update != null && it.source != null && !it.source.locked },
        ) {
            "更新选择无效"
        }
        val requests =
            items.map { item ->
                val source = requireNotNull(item.source)
                ContentRequest(
                    ContentHit(source.projectId, "", source.title, "", "", 0, source.kind, null),
                    requireNotNull(item.update).id,
                )
            }
        return ContentInstallPlanner(store, network, api, backend)
            .planMany(
                requests,
                report.instanceId,
                emptySet(),
                report.items.mapNotNull { it.source },
            )
    }

    override suspend fun installPlan(
        plan: ContentInstallPlan,
        context: JobContext,
    ) = ContentInstallTransaction(
        store,
        network,
        if (plan.files.all { it.projectId.startsWith("curseforge:") }) {
            curseForge.api
        } else {
            api
        },
    ).install(plan, context)
}
