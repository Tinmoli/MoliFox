// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责内容搜索与安装计划。

package dev.molifox.core

import com.google.gson.JsonObject
import dev.molifox.core.i18n.I18n
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

/**
 * Modrinth 项目/搜索对象的分类列表：categories 混有 fabric 等加载器 slug，不能直接展示；
 * display_categories 已由 Modrinth 排除加载器，取它并丢弃空白项，缺失时保持空列表。
 */
internal fun JsonObject.modrinthDisplayCategories(): List<String> =
    array("display_categories")
        .mapNotNull { element -> element.takeIf { it.isJsonPrimitive }?.asString?.trim() }
        .filter { it.isNotEmpty() }

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
                kind in listOf("mod", "modpack", "resourcepack", "shader", "datapack", "world") &&
                offset in 0..SEARCH_OFFSET_MAX,
        ) {
            I18n.t("contentA2.contentSearchActions.invalidSearch")
        }
        // 世界只有 CurseForge 提供：即使设置里选了 Modrinth，世界分类也固定走 CurseForge。
        if (kind == "world" || store.data.settings.contentDownloadSource == "curseforge") {
            return curseForge.search(query, kind, instanceId?.let(store::instance), offset, filter)
        }
        val selection = filter ?: defaultContentFilter(kind, instanceId)
        val facets = searchFacets(kind, selection)
        val searchIndex = if (query.isBlank()) "downloads" else "relevance"
        val parameters =
            "query=${encoded(query)}&facets=${encoded(gson.toJson(facets))}" +
                "&limit=12&offset=$offset&index=$searchIndex"
        val result = network.json("$api/search?$parameters").asJsonObject
        return ContentSearch(contentHits(result, kind), result.int("total_hits"))
    }

    /** 内容源的分类列表：CurseForge 用镜像自己的编号与名称，Modrinth 由界面内置的 slug 列表提供。 */
    override suspend fun contentCategories(kind: String): List<ContentCategoryOption> =
        if (kind == "world" || store.data.settings.contentDownloadSource == "curseforge") {
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
                store.log(
                    I18n.t("contentA2.contentSearchActions.versionFilterIgnored", selection.gameVersion),
                    true,
                )
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

    private fun contentHits(
        result: JsonObject,
        kind: String,
    ): List<ContentHit> =
        result.array("hits").map { element ->
            val item = element.asJsonObject
            ContentHit(
                item.string("project_id"),
                item.string("slug"),
                item.string("title"),
                item.string("description"),
                item.string("author"),
                item.get("downloads")?.asLong ?: 0,
                // Modrinth 可能把「模组 + 数据包」双格式项目报成 project_type=mod；
                // 以用户正在浏览的分类为准，数据包才能落到存档而不是 mods。
                kind,
                item.string("icon_url").takeIf { it.isNotBlank() },
                categories = item.modrinthDisplayCategories(),
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
            I18n.t("contentA2.contentSearchActions.contentChanged")
        }
        val items = report.items.filter { it.key in selected }
        require(
            items.isNotEmpty() &&
                items.size == selected.size &&
                items.all { it.update != null && it.source != null && !it.source.locked },
        ) {
            I18n.t("contentA2.contentSearchActions.invalidUpdateSelection")
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
