// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责CurseForge 内容搜索、文件查询和下载信息转换。

package dev.molifox.core

import com.google.gson.JsonObject
import java.nio.file.Path

/** CurseForge 项目与文件编号上限（32 位无符号整数）。 */
private const val MAX_CURSE_FORGE_ID = 0xffffffffL

/** `curseforge:项目:文件` 标识的段数。 */
private const val CURSE_FORGE_VERSION_ID_PARTS = 3

/** 项目介绍正文保留的最大字符数。 */
private const val PROJECT_BODY_MAX_LENGTH = 60000

/** 单个版本更新日志保留的最大字符数。 */
private const val CHANGELOG_MAX_LENGTH = 40000

internal fun curseForgeProjectId(id: String): String {
    require(id.matches(Regex("curseforge:[1-9][0-9]{0,9}"))) { "无效的 CurseForge 项目标识" }
    return id.substringAfter(':').also { require(it.toLong() <= MAX_CURSE_FORGE_ID) }
}

internal fun curseForgeVersionId(id: String): Pair<String, String> {
    val parts = id.split(':')
    require(parts.size == CURSE_FORGE_VERSION_ID_PARTS) { "无效的 CurseForge 文件标识" }
    val project = curseForgeProjectId(parts.take(2).joinToString(":"))
    val file = parts[2]
    require(file.matches(Regex("[1-9][0-9]{0,9}")) && file.toLong() <= MAX_CURSE_FORGE_ID) {
        "无效的 CurseForge 文件标识"
    }
    return project to file
}

/**
 * CurseForge 内容源的对外门面：搜索、分类、项目/版本读取与更新检查都在这里转发到各自实现，
 * 传输、文件规范化与指纹匹配分别由 [CurseForgeApi]、[CurseForgeFiles]、[CurseForgeFingerprints] 承担。
 */
internal class CurseForgeBackend(
    store: LauncherStore,
    network: NetworkClient,
) : ContentBackend {
    private val transport = CurseForgeApi(store, network)
    private val browsing = CurseForgeBrowsing(transport, store)
    private val fileRecords = CurseForgeFiles(transport)
    private val fingerprintIndex = CurseForgeFingerprints(transport, fileRecords)

    /** 所有 CurseForge API 请求的根地址（含版本段）；社区镜像与官方 API 的路径结构一致。 */
    val api: String
        get() = transport.rootUrl

    suspend fun search(
        query: String,
        kind: String,
        instance: GameInstance?,
        offset: Int,
        filter: ContentFilter? = null,
    ): ContentSearch = browsing.search(query, kind, instance, offset, filter)

    suspend fun categories(kind: String): List<ContentCategoryOption> = browsing.categories(kind)

    override suspend fun project(id: String): JsonObject {
        val raw = transport.rawProject(id)
        val index = transport.classIndex()
        val kind =
            transport.kindOfClassId(index, raw.intOrNull("classId"))
                ?: transport.fail("项目分类映射", "项目 classId 不在镜像分类表内", raw)
        val hit = browsing.hit(raw, kind)
        val description = transport.optionalText("mods/${curseForgeProjectId(id)}/description")
        return JsonObject().apply {
            addProperty("id", id)
            addProperty("project_type", kind)
            addProperty("slug", hit.slug)
            addProperty("title", hit.title)
            addProperty("description", hit.description)
            addProperty("author", hit.author)
            addProperty("downloads", hit.downloads)
            addProperty("icon_url", hit.iconUrl)
            addProperty("body", plainDescription(description).take(PROJECT_BODY_MAX_LENGTH))
            addProperty("website", contentWebsite(hit))
            addProperty("wiki_url", raw.obj("links").string("wikiUrl"))
            addProperty("source_url", raw.obj("links").string("sourceUrl"))
            addProperty("issues_url", raw.obj("links").string("issuesUrl"))
        }
    }

    override suspend fun version(id: String): JsonObject {
        val (project, file) = curseForgeVersionId(id)
        val raw = transport.request("mods/$project/files/$file").obj("data")
        val normalized = fileRecords.normalize(raw, transport.rawProject("curseforge:$project"))
        require(normalized.string("id") == id) { "CurseForge 文件标识不匹配" }
        normalized.addProperty(
            "changelog",
            plainDescription(transport.optionalText("mods/$project/files/$file/changelog"))
                .take(CHANGELOG_MAX_LENGTH),
        )
        return normalized
    }

    override suspend fun versions(
        id: String,
        instance: GameInstance,
        kind: String,
    ): List<JsonObject> =
        fileRecords.available(
            id,
            instance.gameVersion,
            if (kind == "mod") transport.loaderId(instance.loader) else null,
        )

    override suspend fun browseVersions(id: String): List<JsonObject> = fileRecords.available(id)

    suspend fun packMetadata(versionIds: List<String>): Map<String, Pair<JsonObject, String>> =
        fileRecords.packMetadata(versionIds)

    suspend fun available(
        id: String,
        gameVersion: String = "",
        loader: Int? = null,
    ): List<JsonObject> = fileRecords.available(id, gameVersion, loader)

    suspend fun fingerprints(files: List<Path>): Map<Path, JsonObject> =
        fingerprintIndex.fingerprints(files)

    suspend fun fingerprintFiles(
        files: List<Path>,
        kind: String? = null,
    ): Map<Path, JsonObject> = fingerprintIndex.fingerprintFiles(files, kind)
}
