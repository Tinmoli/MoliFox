// 负责CurseForge 内容搜索、文件查询和下载信息转换。

package dev.molifox.core

import com.google.gson.JsonObject
import java.nio.file.Path
import kotlin.io.path.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal fun curseForgeProjectId(id: String): String {
    require(id.matches(Regex("curseforge:[1-9][0-9]{0,9}"))) { "无效的 CurseForge 项目标识" }
    return id.substringAfter(':').also { require(it.toLong() <= 0xffffffffL) }
}

internal fun curseForgeVersionId(id: String): Pair<String, String> {
    val parts = id.split(':')
    require(parts.size == 3) { "无效的 CurseForge 文件标识" }
    val project = curseForgeProjectId(parts.take(2).joinToString(":"))
    val file = parts[2]
    require(file.matches(Regex("[1-9][0-9]{0,9}")) && file.toLong() <= 0xffffffffL) {
        "无效的 CurseForge 文件标识"
    }
    return project to file
}

internal class CurseForgeBackend(
    private val store: LauncherStore,
    private val network: NetworkClient,
    val api: String = "https://api.curseforge.com/v1",
) : ContentBackend {
    private val classSlugs =
        mapOf(
            "mod" to "mc-mods",
            "modpack" to "modpacks",
            "resourcepack" to "texture-packs",
            "shader" to "shaders",
            "datapack" to "data-packs",
        )

    private suspend fun request(path: String, body: JsonObject? = null) =
        network.curseForgeJson("$api/$path", store.data.settings.curseForgeApiKey, body)

    private suspend fun classes(): Map<Int, String> =
        request("categories?gameId=432&classesOnly=true")
            .array("data")
            .map { it.asJsonObject }
            .filter { it.int("gameId") == 432 && it.boolean("isClass") }
            .mapNotNull { category ->
                classSlugs.entries
                    .find { it.value == category.string("slug") }
                    ?.let { category.int("id") to it.key }
            }
            .toMap()

    private suspend fun rawProject(id: String): JsonObject =
        request("mods/${curseForgeProjectId(id)}").obj("data").also {
            require(it.int("gameId") == 432 && it.string("id") == curseForgeProjectId(id)) {
                "CurseForge 项目不属于 Minecraft 或标识不匹配"
            }
        }

    suspend fun search(
        query: String,
        kind: String,
        instance: GameInstance?,
        offset: Int,
        filter: ContentFilter? = null,
    ): ContentSearch {
        val classId =
            classes().entries.find { it.value == kind }?.key ?: error("CurseForge 暂未提供此资源分类")
        val pageSize = minOf(12, 10000 - offset)
        if (pageSize <= 0) return ContentSearch(emptyList(), 10000)
        val selection = filter ?: ContentFilter(instance?.gameVersion.orEmpty(), instance?.loader)
        // CurseForge 的类别是数字 categoryId，与 Modrinth 的 slug 没有稳定映射；分类筛选在这里忽略。
        val filters = buildString {
            if (selection.gameVersion.isNotBlank())
                append("&gameVersion=${encoded(selection.gameVersion)}")
            if (kind == "mod" && selection.loader != null && selection.loader != LoaderType.Vanilla)
                append("&modLoaderType=${loaderId(selection.loader)}")
        }
        val result =
            request(
                "mods/search?gameId=432&classId=$classId&searchFilter=${encoded(query)}&index=$offset&pageSize=$pageSize&sortField=${if (query.isBlank()) 6 else 2}&sortOrder=desc$filters"
            )
        return ContentSearch(
            result.array("data").map { hit(it.asJsonObject, kind) },
            result.obj("pagination").int("totalCount").coerceIn(0, 10000),
        )
    }

    private fun hit(info: JsonObject, kind: String): ContentHit {
        require(info.int("gameId") == 432) { "CurseForge 搜索结果不属于 Minecraft" }
        val id = "curseforge:${info.string("id")}"
        curseForgeProjectId(id)
        return ContentHit(
            id,
            info.string("slug"),
            info.string("name"),
            info.string("summary"),
            info.array("authors").joinToString(", ") { it.asJsonObject.string("name") },
            info.get("downloadCount")?.asLong ?: 0,
            kind,
            info
                .obj("logo")
                .string("thumbnailUrl")
                .ifBlank { info.obj("logo").string("url") }
                .takeIf { it.isNotBlank() },
        )
    }

    override suspend fun project(id: String): JsonObject {
        val raw = rawProject(id)
        val kind = classes()[raw.int("classId")] ?: error("不支持的 CurseForge 项目分类")
        val hit = hit(raw, kind)
        val description = request("mods/${curseForgeProjectId(id)}/description").string("data")
        return JsonObject().apply {
            addProperty("id", id)
            addProperty("project_type", kind)
            addProperty("slug", hit.slug)
            addProperty("title", hit.title)
            addProperty("description", hit.description)
            addProperty("author", hit.author)
            addProperty("downloads", hit.downloads)
            addProperty("icon_url", hit.iconUrl)
            addProperty("body", plainDescription(description).take(60000))
            addProperty("website", contentWebsite(hit))
            addProperty("wiki_url", raw.obj("links").string("wikiUrl"))
            addProperty("source_url", raw.obj("links").string("sourceUrl"))
            addProperty("issues_url", raw.obj("links").string("issuesUrl"))
        }
    }

    override suspend fun version(id: String): JsonObject {
        val (project, file) = curseForgeVersionId(id)
        val raw = request("mods/$project/files/$file").obj("data")
        val normalized = normalizeFile(raw, rawProject("curseforge:$project"))
        require(normalized.string("id") == id) { "CurseForge 文件标识不匹配" }
        normalized.addProperty(
            "changelog",
            plainDescription(request("mods/$project/files/$file/changelog").string("data"))
                .take(40000),
        )
        return normalized
    }

    override suspend fun versions(
        id: String,
        instance: GameInstance,
        kind: String,
    ): List<JsonObject> =
        available(id, instance.gameVersion, if (kind == "mod") loaderId(instance.loader) else null)

    override suspend fun browseVersions(id: String) = available(id)

    /**
     * Large packs resolve metadata in bounded batches, without fetching unused HTML or changelogs.
     */
    suspend fun packMetadata(versionIds: List<String>): Map<String, Pair<JsonObject, String>> {
        val requested = versionIds.distinct().associateWith(::curseForgeVersionId)
        val projects = mutableMapOf<String, JsonObject>()
        val files = mutableMapOf<String, JsonObject>()
        for (batch in requested.values.map { it.first }.distinct().chunked(50)) {
            val body =
                JsonObject().apply { add("modIds", gson.toJsonTree(batch.map { it.toLong() })) }
            for (entry in request("mods", body).array("data")) {
                val info = entry.asJsonObject
                require(info.string("id") in batch && info.int("gameId") == 432) {
                    "CurseForge 返回不匹配的项目"
                }
                projects[info.string("id")] = info
            }
        }
        for (batch in requested.values.map { it.second }.distinct().chunked(50)) {
            val body =
                JsonObject().apply { add("fileIds", gson.toJsonTree(batch.map { it.toLong() })) }
            for (entry in request("mods/files", body).array("data")) {
                val info = entry.asJsonObject
                require(info.string("id") in batch) { "CurseForge 返回不匹配的文件" }
                files[info.string("id")] = info
            }
        }
        val kinds = if (requested.isEmpty()) emptyMap() else classes()
        return requested.mapValues { (id, pair) ->
            val project = projects[pair.first] ?: error("CurseForge 项目不可用：${pair.first}")
            val file = files[pair.second] ?: error("CurseForge 文件不可用：$id")
            val kind = kinds[project.int("classId")] ?: error("不支持的 CurseForge 项目分类")
            normalizeFile(file, project) to kind
        }
    }

    suspend fun available(
        id: String,
        gameVersion: String = "",
        loader: Int? = null,
    ): List<JsonObject> {
        val project = rawProject(id)
        val filters =
            (if (gameVersion.isNotBlank()) "&gameVersion=${encoded(gameVersion)}" else "") +
                (loader?.let { "&modLoaderType=$it" } ?: "")
        val files = mutableListOf<JsonObject>()
        var offset = 0
        while (offset < 10000) {
            currentCoroutineContext().ensureActive()
            val response =
                request("mods/${curseForgeProjectId(id)}/files?index=$offset&pageSize=50$filters")
            val page = response.array("data").map { it.asJsonObject }
            require(page.size <= 50) { "CurseForge 文件分页格式无效" }
            files +=
                page
                    .filter { it.boolean("isAvailable") && !it.boolean("isServerPack") }
                    .map { normalizeFile(it, project) }
            offset += page.size
            if (page.size < 50 || offset >= response.obj("pagination").int("totalCount", offset))
                break
        }
        return files
            .distinctBy { it.string("id") }
            .sortedByDescending { it.string("date_published") }
    }

    internal fun normalizeFile(file: JsonObject, project: JsonObject): JsonObject {
        val projectId = "curseforge:${project.string("id")}"
        val versionId = "$projectId:${file.string("id")}"
        curseForgeVersionId(versionId)
        require(
            file.int("gameId") == 432 &&
                file.string("modId") == curseForgeProjectId(projectId) &&
                file.boolean("isAvailable") &&
                !file.boolean("isServerPack")
        ) {
            "CurseForge 文件不可用、属于服务端或项目不匹配"
        }
        val tags = file.array("gameVersions").map { it.asString }
        val sha1 =
            file
                .array("hashes")
                .map { it.asJsonObject }
                .find { it.int("algo") == 1 }
                ?.string("value")
                .orEmpty()
        val relations =
            mapOf(
                1 to "embedded",
                2 to "optional",
                3 to "required",
                4 to "tool",
                5 to "incompatible",
                6 to "embedded",
            )
        return JsonObject().apply {
            addProperty("id", versionId)
            addProperty("project_id", projectId)
            addProperty("name", file.string("displayName"))
            addProperty("version_number", file.string("displayName"))
            addProperty(
                "version_type",
                when (file.int("releaseType")) {
                    1 -> "release"
                    2 -> "beta"
                    3 -> "alpha"
                    else -> error("未知的 CurseForge 发布类型")
                },
            )
            addProperty("date_published", file.string("fileDate"))
            // CurseForge mixes loader/Java/platform tags into gameVersions; keep those out of
            // the Minecraft version selector while retaining loader tags separately below.
            add(
                "game_versions",
                gson.toJsonTree(
                    tags.filter {
                        Regex("^(?:[0-9]|[ab][0-9]|rd-|inf-).*").matches(it)
                    }
                ),
            )
            add(
                "loaders",
                gson.toJsonTree(
                    tags
                        .filter { it.lowercase() in setOf("fabric", "quilt", "forge", "neoforge") }
                        .map { it.lowercase() }
                ),
            )
            add(
                "dependencies",
                gson.toJsonTree(
                    file.array("dependencies").map {
                        val dep = it.asJsonObject
                        val dependency =
                            "curseforge:${dep.string("modId")}".also(::curseForgeProjectId)
                        mapOf(
                            "project_id" to dependency,
                            "dependency_type" to
                                (relations[dep.int("relationType")]
                                    ?: error("未知的 CurseForge 依赖类型")),
                        )
                    }
                ),
            )
            val filename = safeFilename(file.string("fileName"))
            val size = file.get("fileLength")?.asLong ?: -1
            require(size >= 0 && sha1.matches(Regex("[a-fA-F0-9]{40}"))) {
                "CurseForge 文件缺少有效大小或 SHA-1"
            }
            val restricted =
                project.get("allowModDistribution")?.takeUnless { it.isJsonNull }?.asBoolean ==
                    false || file.string("downloadUrl").isBlank()
            if (restricted) {
                addProperty("manual_download", true)
                addProperty(
                    "website",
                    "https://www.curseforge.com/minecraft/${classSlugs.values.find { project.obj("links").string("websiteUrl").contains("/$it/") } ?: "mc-mods"}/${encoded(project.string("slug"))}/files/${file.string("id")}",
                )
            }
            add(
                "files",
                gson.toJsonTree(
                    listOf(
                        mapOf(
                            "filename" to filename,
                            "primary" to true,
                            "url" to
                                if (restricted) ""
                                else contentFileUrl(api, file.string("downloadUrl")),
                            "size" to size,
                            "hashes" to mapOf("sha1" to sha1),
                        )
                    )
                ),
            )
        }
    }

    suspend fun fingerprints(files: List<Path>): Map<Path, JsonObject> {
        val local = files.associateWith { curseForgeFingerprint(it) }
        val matches = mutableListOf<JsonObject>()
        for (batch in local.values.distinct().chunked(100)) {
            val body = JsonObject().apply { add("fingerprints", gson.toJsonTree(batch)) }
            matches +=
                request("fingerprints/432", body).obj("data").array("exactMatches").map {
                    it.asJsonObject.obj("file")
                }
        }
        return local
            .mapNotNull { (path, fingerprint) ->
                val candidates =
                    matches
                        .filter {
                            it.get("fileFingerprint")?.asLong == fingerprint &&
                                it.get("fileLength")?.asLong == path.fileSize()
                        }
                        .filter { file ->
                            file.array("hashes").any {
                                it.asJsonObject.int("algo") == 1 &&
                                    it.asJsonObject.string("value").equals(digest(path), true)
                            }
                        }
                if (candidates.map { it.string("id") }.distinct().size != 1) null
                else path to candidates.first()
            }
            .toMap()
    }

    private fun loaderId(loader: LoaderType) =
        when (loader) {
            LoaderType.Forge -> 1
            LoaderType.Fabric -> 4
            LoaderType.Quilt -> 5
            LoaderType.NeoForge -> 6
            else -> 0
        }
}

private fun plainDescription(html: String) =
    html
        .take(250000)
        .replace(Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1>"), "")
        .replace(Regex("(?i)<br\\s*/?>|</(?:p|div|li|h[1-6])>"), "\n")
        .replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .trim()
