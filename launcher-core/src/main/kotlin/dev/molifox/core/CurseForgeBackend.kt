// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

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
) : ContentBackend {
    /** 设置变更后立即生效；空值/非法值回落默认镜像，只记录一次日志。 */
    private var fallbackLoggedFor: String? = null

    private fun baseUrl(): String {
        val raw = store.data.settings.curseForgeBaseUrl.orEmpty()
        val effective = normalizedCurseForgeBaseUrl(raw)
        if (effective == DEFAULT_CURSE_FORGE_BASE_URL && raw.trim().trimEnd('/') != effective) {
            if (fallbackLoggedFor != raw) {
                store.log("CurseForge 服务地址无效，已使用默认镜像：$DEFAULT_CURSE_FORGE_BASE_URL", true)
                fallbackLoggedFor = raw
            }
        } else fallbackLoggedFor = null
        return effective
    }

    /** 所有 CurseForge API 请求的根地址（含版本段）；社区镜像与官方 API 的路径结构一致。 */
    val api: String
        get() = "${baseUrl()}/v1"

    private val classSlugs =
        mapOf(
            "mod" to "mc-mods",
            "modpack" to "modpacks",
            "resourcepack" to "texture-packs",
            "shader" to "shaders",
            "datapack" to "data-packs",
        )

    /** 分类按资源类型缓存：只存内存，切换类型时按 classId 重新取，不落盘。 */
    private val categoryCache = mutableMapOf<Int, List<ContentCategoryOption>>()

    private suspend fun request(path: String, body: JsonObject? = null) =
        network.curseForgeJson("$api/$path", body)

    /** 失败时只带响应开头，供日志和界面定位是哪一级、哪段数据出的问题。 */
    private fun snippet(payload: Any): String =
        payload.toString().take(200).replace('\n', ' ').replace('\r', ' ')

    private fun fail(stage: String, detail: String, payload: Any): Nothing {
        val message = "CurseForge $stage 失败：$detail；响应片段：${snippet(payload)}"
        store.log(message, true)
        throw IllegalStateException(message)
    }

    private class ClassIndex(val kinds: Map<Int, String>, val payload: JsonObject)

    /** 顶层资源类型（游戏 ID 432 的 class 分类）。镜像字段可能缺失，这里按顶层项容错识别。 */
    private suspend fun classIndex(): ClassIndex {
        val payload = request("categories?gameId=432&classesOnly=true")
        val kinds =
            payload
                .array("data")
                .mapNotNull { element -> runCatching { element.asJsonObject }.getOrNull() }
                .filter { it.isMinecraftProject() }
                // 社区镜像可能不返回 isClass：没有父分类的顶层项同样当作资源类型。
                .filter { category ->
                    category.flag("isClass")
                        ?: (category.get("classId") == null || category.get("classId").isJsonNull)
                }
                .mapNotNull { category ->
                    classSlugs.entries
                        .find { it.value == category.string("slug") }
                        ?.let { category.int("id") to it.key }
                }
                .toMap()
        if (kinds.isEmpty())
            fail("分类映射", "镜像没有返回可识别的资源类型（mc-mods、modpacks 等）", payload)
        return ClassIndex(kinds, payload)
    }

    /**
     * 某个资源类型下的可选分类。编号与名称都取自内容源本身，镜像新增的分类自动出现。
     */
    suspend fun categories(kind: String): List<ContentCategoryOption> {
        val index = classIndex()
        val classId =
            index.kinds.entries.find { it.value == kind }?.key
                ?: fail("分类映射", "镜像没有 $kind 对应的资源类型", index.payload)
        return categoryOptions(classId)
    }

    private suspend fun categoryOptions(classId: Int): List<ContentCategoryOption> =
        categoryCache[classId]
            ?: run {
                val payload = request("categories?gameId=432&classId=$classId")
                val options =
                    payload
                        .array("data")
                        .mapNotNull { element -> runCatching { element.asJsonObject }.getOrNull() }
                        .filter { it.intOrNull("id") != null && it.string("name").isNotBlank() }
                        .map {
                            ContentCategoryOption(
                                it.string("id"),
                                it.string("name"),
                                it.string("slug"),
                            )
                        }
                        .distinctBy { it.id }
                // 空列表不缓存，镜像恢复后下一次搜索能重新取到。
                if (options.isNotEmpty()) categoryCache[classId] = options
                options
            }

    /** 选中的分类必须能在镜像自己的分类表里找到，否则不发 categoryId，避免静默 0 结果。 */
    private suspend fun resolveCategory(classId: Int, selected: Set<String>): Int? {
        if (selected.isEmpty()) return null
        val known = categoryOptions(classId).map { it.id }.toSet()
        val matched =
            selected.mapNotNull { it.trim().toIntOrNull() }.filter { it.toString() in known }
        if (matched.isEmpty()) {
            store.log("CurseForge 分类筛选无法映射到当前资源类型，已忽略：${selected.joinToString()}", true)
            return null
        }
        if (matched.size > 1)
            store.log("CurseForge 一次只按一个分类筛选，已使用分类 ${matched.first()}")
        return matched.first()
    }

    /**
     * 版本筛选只接受真实的 Minecraft 版本：实例目录名（`TwilightMC-26.3v1.0`）会解析成 `26.3`，
     * 解析不到就不发 gameVersion 并记一条日志，而不是让服务端返回空列表。
     */
    private fun resolvedGameVersion(requested: String): String? {
        val value = requested.trim()
        if (value.isEmpty()) return null
        val resolved = minecraftVersionTag(value)
        when {
            resolved == null ->
                store.log("CurseForge 已忽略无法识别的游戏版本筛选：$value", true)
            resolved != value -> store.log("CurseForge 游戏版本筛选按识别结果发送：$value → $resolved")
        }
        return resolved
    }

    /** 社区镜像不提供介绍/更新日志端点（404）；它们只是可选展示内容，不影响安装。 */
    private suspend fun optionalText(path: String): String =
        try {
            request(path).string("data")
        } catch (missing: CurseForgeHttpException) {
            if (missing.statusCode == 404) "" else throw missing
        }

    private suspend fun rawProject(id: String): JsonObject =
        request("mods/${curseForgeProjectId(id)}").obj("data").also {
            require(it.isMinecraftProject() && it.string("id") == curseForgeProjectId(id)) {
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
        val index = classIndex()
        val classId =
            index.kinds.entries.find { it.value == kind }?.key
                ?: fail("搜索分类", "镜像没有 $kind 对应的资源类型", index.payload)
        val pageSize = minOf(12, 10000 - offset)
        if (pageSize <= 0) return ContentSearch(emptyList(), 10000)
        val selection = filter ?: ContentFilter(instance?.gameVersion.orEmpty(), instance?.loader)
        // CurseForge 的类别是数字 categoryId；只发送镜像自己确认存在的编号。
        val filters = buildString {
            resolvedGameVersion(selection.gameVersion)?.let {
                append("&gameVersion=${encoded(it)}")
            }
            if (kind == "mod") loaderId(selection.loader)?.let { append("&modLoaderType=$it") }
            resolveCategory(classId, selection.categories)?.let { append("&categoryId=$it") }
        }
        val result =
            request(
                "mods/search?gameId=432&classId=$classId&searchFilter=${encoded(query)}&index=$offset&pageSize=$pageSize&sortField=${if (query.isBlank()) 6 else 2}&sortOrder=desc$filters"
            )
        val data = result.array("data").toList()
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
            if (hit != null) hits += hit
            else failures += outcome.exceptionOrNull()?.message ?: "项目标识或游戏 ID 无效"
        }
        // 映射问题不能变成「没有结果」：一条都没认出来时按错误上报，并带上响应片段。
        if (hits.isEmpty() && data.isNotEmpty())
            fail("搜索结果映射", "全部 ${data.size} 条无法识别（${failures.first()}）", result)
        if (failures.isNotEmpty())
            store.log(
                "CurseForge 搜索结果有 ${failures.size} 条无法识别，已跳过：${failures.first()}",
                true,
            )
        val total = result.obj("pagination").int("totalCount").coerceIn(0, 10000)
        if (data.isEmpty() && total > 0)
            store.log("CurseForge 返回 totalCount=$total 但没有条目，请稍后重试：$offset", true)
        return ContentSearch(hits, total)
    }

    /** 搜索结果中的一条。缺失的可选字段按空值处理，只有标识矛盾才跳过（不抛异常拖垮整页）。 */
    private fun hitOrNull(info: JsonObject, kind: String): ContentHit? {
        info.intOrNull("gameId")?.let { require(it == 432) { "搜索结果不属于 Minecraft" } }
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
            info.get("downloadCount")
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

    private fun hit(info: JsonObject, kind: String): ContentHit =
        requireNotNull(hitOrNull(info, kind)) { "CurseForge 项目标识无效" }

    override suspend fun project(id: String): JsonObject {
        val raw = rawProject(id)
        val index = classIndex()
        val kind =
            index.kinds[raw.intOrNull("classId") ?: 0]
                ?: fail("项目分类映射", "项目 classId 不在镜像分类表内", raw)
        val hit = hit(raw, kind)
        val description = optionalText("mods/${curseForgeProjectId(id)}/description")
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
            plainDescription(optionalText("mods/$project/files/$file/changelog")).take(40000),
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
                require(info.string("id") in batch && info.isMinecraftProject()) {
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
        val index = if (requested.isEmpty()) null else classIndex()
        return requested.mapValues { (id, pair) ->
            val project = projects[pair.first] ?: error("CurseForge 项目不可用：${pair.first}")
            val file = files[pair.second] ?: error("CurseForge 文件不可用：$id")
            val kind =
                index?.kinds?.get(project.intOrNull("classId") ?: 0)
                    ?: fail("整合包项目分类映射", "项目 classId 不在镜像分类表内", project)
            normalizeFile(file, project) to kind
        }
    }

    suspend fun available(
        id: String,
        gameVersion: String = "",
        loader: Int? = null,
    ): List<JsonObject> {
        val project = rawProject(id)
        // 同样只发送真实版本号；解析不到就不按版本过滤，绝不用目录名换来 0 个文件。
        val resolvedVersion = resolvedGameVersion(gameVersion)
        val filters =
            (resolvedVersion?.let { "&gameVersion=${encoded(it)}" } ?: "") +
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
            file.isMinecraftProject() &&
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

    /** 本地文件的 CurseForge 指纹；按路径 + 大小 + 修改时间缓存，重复检查不再重读整颗 jar。 */
    private data class MurmurEntry(val size: Long, val modified: Long, val fingerprint: Long)

    private val murmurCache =
        object : LinkedHashMap<String, MurmurEntry>(64, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MurmurEntry>?) =
                size > 1024
        }

    private suspend fun fingerprintOf(path: Path): Pair<Long, Long>? {
        val size = runCatching { path.fileSize() }.getOrNull() ?: return null
        val modified = runCatching { path.getLastModifiedTime().toMillis() }.getOrNull() ?: return null
        val key = path.toAbsolutePath().normalize().toString()
        synchronized(murmurCache) {
            murmurCache[key]?.takeIf { it.size == size && it.modified == modified }
        }
            ?.let {
                return it.fingerprint to size
            }
        val fingerprint = runCatching { curseForgeFingerprint(path) }.getOrNull() ?: return null
        synchronized(murmurCache) { murmurCache[key] = MurmurEntry(size, modified, fingerprint) }
        return fingerprint to size
    }

    /**
     * 批量指纹匹配：一次 `POST /fingerprints/432`，返回每个本地文件命中的指纹记录。
     *
     * 本地 Murmur 指纹按路径 + 大小 + 修改时间缓存，SHA-1 每个文件只算一次
     * （原先每一对候选都会把整颗 jar 重算一遍）。
     */
    private suspend fun matchFiles(files: List<Path>): Map<Path, JsonObject> {
        if (files.isEmpty()) return emptyMap()
        val local = LinkedHashMap<Path, Pair<Long, Long>>()
        for (path in files) {
            currentCoroutineContext().ensureActive()
            fingerprintOf(path)?.let { local[path] = it }
        }
        if (local.isEmpty()) return emptyMap()
        val exact = mutableMapOf<Pair<Long, Long>, MutableList<JsonObject>>()
        for (batch in local.values.map { it.first }.distinct().chunked(1000)) {
            val body = JsonObject().apply { add("fingerprints", gson.toJsonTree(batch)) }
            for (element in request("fingerprints/432", body).obj("data").array("exactMatches")) {
                val file =
                    runCatching { element.asJsonObject.obj("file") }.getOrNull() ?: continue
                val fingerprint =
                    file.get("fileFingerprint")?.takeUnless { it.isJsonNull }?.asLong ?: continue
                val length = file.get("fileLength")?.takeUnless { it.isJsonNull }?.asLong ?: continue
                exact.getOrPut(fingerprint to length) { mutableListOf() } += file
            }
        }
        // 同一指纹可能对应多条记录：按 SHA-1 精确校验，只有唯一文件才算确定匹配。
        val matched = mutableMapOf<Path, JsonObject>()
        for ((path, key) in local) {
            currentCoroutineContext().ensureActive()
            val candidates = exact[key] ?: continue
            val sha1 = runCatching { digest(path, "SHA-1") }.getOrDefault("")
            if (sha1.isBlank()) continue
            val unique =
                candidates
                    .filter { file ->
                        file.array("hashes").any {
                            val hash = runCatching { it.asJsonObject }.getOrNull()
                            hash != null &&
                                hash.int("algo") == 1 &&
                                hash.string("value").equals(sha1, true)
                        }
                    }
                    .distinctBy { it.string("id") }
            if (unique.size == 1 && unique.first().string("id").isNotBlank())
                matched[path] = unique.first()
        }
        return matched
    }

    /** 批量文件记录：`POST /mods/files`，只在指纹记录字段不全时补一次。 */
    private suspend fun filesById(ids: List<String>): Map<String, JsonObject> {
        val result = mutableMapOf<String, JsonObject>()
        for (batch in ids.distinct().chunked(50)) {
            val body =
                JsonObject().apply {
                    add("fileIds", gson.toJsonTree(batch.mapNotNull { it.toLongOrNull() }))
                }
            for (entry in request("mods/files", body).array("data")) {
                val info = runCatching { entry.asJsonObject }.getOrNull() ?: continue
                result[info.string("id")] = info
            }
        }
        return result
    }

    /** 本地文件 → CurseForge 指纹记录；请求数与文件数无关。 */
    suspend fun fingerprints(files: List<Path>): Map<Path, JsonObject> = matchFiles(files)

    /**
     * 本地文件 → 规范化后的 CurseForge 版本记录（已合并项目信息）。
     *
     * 用于模组、资源包与光影的更新检查：指纹与项目各一次批量请求，指纹记录字段不全时再用
     * 一次批量文件请求补齐；[kind] 非空时用镜像自己的分类表过滤资源类型。
     */
    suspend fun fingerprintFiles(files: List<Path>, kind: String? = null): Map<Path, JsonObject> {
        val matches = matchFiles(files)
        if (matches.isEmpty()) return emptyMap()
        val projects = mutableMapOf<String, JsonObject>()
        for (batch in
            matches.values.map { it.string("modId") }.filter { it.isNotBlank() }.distinct().chunked(50)) {
            val body =
                JsonObject().apply {
                    add("modIds", gson.toJsonTree(batch.mapNotNull { it.toLongOrNull() }))
                }
            for (entry in request("mods", body).array("data")) {
                val info = runCatching { entry.asJsonObject }.getOrNull() ?: continue
                projects[info.string("id")] = info
            }
        }
        val kinds = if (kind == null) null else classIndex().kinds
        val result = mutableMapOf<Path, JsonObject>()
        val incomplete = mutableListOf<Pair<Path, String>>()
        for ((path, file) in matches) {
            val project = projects[file.string("modId")] ?: continue
            if (kinds != null && kinds[project.intOrNull("classId") ?: 0] != kind) continue
            val normalized = runCatching { normalizeFile(file, project) }.getOrNull()
            if (normalized != null) result[path] = normalized
            else incomplete += path to file.string("id")
        }
        if (incomplete.isNotEmpty()) {
            val full = filesById(incomplete.map { it.second })
            for ((path, id) in incomplete) {
                val file = full[id] ?: continue
                val project = projects[file.string("modId")] ?: continue
                val normalized = runCatching { normalizeFile(file, project) }.getOrNull() ?: continue
                result[path] = normalized
            }
        }
        return result
    }

    /** 没有对应编号的加载器返回 null：绝不能发 `modLoaderType=0`，镜像会把它当作匹配不到任何模组的筛选。 */
    private fun loaderId(loader: LoaderType?): Int? =
        when (loader) {
            LoaderType.Forge -> 1
            LoaderType.Fabric -> 4
            LoaderType.Quilt -> 5
            LoaderType.NeoForge -> 6
            else -> null
        }
}

/** 镜像省略 gameId 时按 Minecraft 处理；明确给了别的游戏 ID 才算不匹配。 */
private fun JsonObject.isMinecraftProject(): Boolean =
    intOrNull("gameId")?.let { it == 432 } != false

/** 缺失或为 null 的数字字段按「没有」处理，不把 0 当成有效值。 */
private fun JsonObject.intOrNull(key: String): Int? =
    get(key)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asInt }.getOrNull() }

/** 缺失或为 null 的布尔字段返回 null，便于区分「没给」和「false」。 */
private fun JsonObject.flag(key: String): Boolean? =
    get(key)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asBoolean }.getOrNull() }

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
