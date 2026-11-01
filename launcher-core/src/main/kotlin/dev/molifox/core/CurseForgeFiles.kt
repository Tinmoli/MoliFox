// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责 CurseForge 项目版本列表、文件记录规范化与批量元数据。

package dev.molifox.core

import com.google.gson.JsonObject
import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 文件分页每页条数：镜像按该大小返回，用于判断是否还有下一页。 */
private const val FILE_PAGE_SIZE = 50

/** CurseForge 发布类型编号：1=正式、2=测试、3=先行。 */
private const val RELEASE_TYPE_RELEASE = 1
private const val RELEASE_TYPE_BETA = 2
private const val RELEASE_TYPE_ALPHA = 3

internal class CurseForgeFiles(
    private val api: CurseForgeApi,
) {
    suspend fun available(
        id: String,
        gameVersion: String = "",
        loader: Int? = null,
    ): List<JsonObject> {
        val project = api.rawProject(id)
        // 同样只发送真实版本号；解析不到就不按版本过滤，绝不用目录名换来 0 个文件。
        val resolvedVersion = api.resolvedGameVersion(gameVersion)
        val filters =
            (resolvedVersion?.let { "&gameVersion=${encoded(it)}" } ?: "") +
                (loader?.let { "&modLoaderType=$it" } ?: "")
        val files = mutableListOf<JsonObject>()
        var offset = 0
        while (offset < CURSE_FORGE_RESULT_LIMIT) {
            currentCoroutineContext().ensureActive()
            val path =
                "mods/${curseForgeProjectId(id)}/files?index=$offset" +
                    "&pageSize=$FILE_PAGE_SIZE$filters"
            val response = api.request(path)
            val page = response.array("data").map { it.asJsonObject }
            require(page.size <= FILE_PAGE_SIZE) { I18n.t("packsB1.curseForgeFiles.invalidPage") }
            files +=
                page
                    .filter { it.boolean("isAvailable") && !it.boolean("isServerPack") }
                    .map { normalize(it, project) }
            offset += page.size
            val reachedEnd =
                page.size < FILE_PAGE_SIZE ||
                    offset >= response.obj("pagination").int("totalCount", offset)
            if (reachedEnd) break
        }
        return files
            .distinctBy { it.string("id") }
            .sortedByDescending { it.string("date_published") }
    }

    /**
     * Large packs resolve metadata in bounded batches, without fetching unused HTML or changelogs.
     */
    suspend fun packMetadata(versionIds: List<String>): Map<String, Pair<JsonObject, String>> {
        val requested = versionIds.distinct().associateWith(::curseForgeVersionId)
        val projects = loadProjects(requested.values.map { it.first })
        val files = loadFiles(requested.values.map { it.second })
        val index = if (requested.isEmpty()) null else api.classIndex()
        return requested.mapValues { (id, pair) ->
            val project =
                projects[pair.first]
                    ?: error(I18n.t("packsB1.curseForgeFiles.projectUnavailable", pair.first))
            val file = files[pair.second] ?: error(I18n.t("packsB1.curseForgeFiles.fileUnavailable", id))
            val kind =
                index?.kinds?.get(project.intOrNull("classId") ?: 0)
                    ?: api.fail(
                        I18n.t("packsB1.curseForgeFiles.stagePackClassMapping"),
                        I18n.t("packsB1.curseForgeBackend.projectClassIdUnknown"),
                        project,
                    )
            normalize(file, project) to kind
        }
    }

    private suspend fun loadProjects(projectIds: Collection<String>): MutableMap<String, JsonObject> {
        val projects = mutableMapOf<String, JsonObject>()
        for (batch in projectIds.distinct().chunked(CURSE_FORGE_BATCH_SIZE)) {
            val body =
                JsonObject().apply { add("modIds", gson.toJsonTree(batch.map { it.toLong() })) }
            for (entry in api.request("mods", body).array("data")) {
                val info = entry.asJsonObject
                require(info.string("id") in batch && info.isMinecraftProject()) {
                    I18n.t("packsB1.curseForgeFiles.projectMismatch")
                }
                projects[info.string("id")] = info
            }
        }
        return projects
    }

    private suspend fun loadFiles(fileIds: Collection<String>): MutableMap<String, JsonObject> {
        val files = mutableMapOf<String, JsonObject>()
        for (batch in fileIds.distinct().chunked(CURSE_FORGE_BATCH_SIZE)) {
            val body =
                JsonObject().apply { add("fileIds", gson.toJsonTree(batch.map { it.toLong() })) }
            for (entry in api.request("mods/files", body).array("data")) {
                val info = entry.asJsonObject
                require(info.string("id") in batch) { I18n.t("packsB1.curseForgeFiles.fileMismatch") }
                files[info.string("id")] = info
            }
        }
        return files
    }

    fun normalize(
        file: JsonObject,
        project: JsonObject,
    ): JsonObject {
        val projectId = "curseforge:${project.string("id")}"
        val versionId = "$projectId:${file.string("id")}"
        curseForgeVersionId(versionId)
        require(
            file.isMinecraftProject() &&
                file.string("modId") == curseForgeProjectId(projectId) &&
                file.boolean("isAvailable") &&
                !file.boolean("isServerPack"),
        ) {
            I18n.t("packsB1.curseForgeFiles.fileNotUsable")
        }
        val tags = file.array("gameVersions").map { it.asString }
        val sha1 =
            file
                .array("hashes")
                .map { it.asJsonObject }
                .find { it.int("algo") == 1 }
                ?.string("value")
                .orEmpty()
        return JsonObject().apply {
            addProperty("id", versionId)
            addProperty("project_id", projectId)
            addProperty("name", file.string("displayName"))
            addProperty("version_number", file.string("displayName"))
            addReleaseType(this, file)
            addProperty("date_published", file.string("fileDate"))
            addTags(this, tags)
            addDependencies(this, file)
            addFiles(this, project, file, sha1)
        }
    }

    private fun addReleaseType(
        target: JsonObject,
        file: JsonObject,
    ) {
        target.addProperty(
            "version_type",
            when (file.int("releaseType")) {
                RELEASE_TYPE_RELEASE -> "release"
                RELEASE_TYPE_BETA -> "beta"
                RELEASE_TYPE_ALPHA -> "alpha"
                else -> error(I18n.t("packsB1.curseForgeFiles.unknownReleaseType"))
            },
        )
    }

    private fun addTags(
        target: JsonObject,
        tags: List<String>,
    ) {
        // CurseForge mixes loader/Java/platform tags into gameVersions; keep those out of
        // the Minecraft version selector while retaining loader tags separately below.
        target.add(
            "game_versions",
            gson.toJsonTree(
                tags.filter {
                    Regex("^(?:[0-9]|[ab][0-9]|rd-|inf-).*").matches(it)
                },
            ),
        )
        target.add(
            "loaders",
            gson.toJsonTree(
                tags
                    .filter { it.lowercase() in setOf("fabric", "quilt", "forge", "neoforge") }
                    .map { it.lowercase() },
            ),
        )
    }

    private fun addDependencies(
        target: JsonObject,
        file: JsonObject,
    ) {
        val relations =
            mapOf(
                1 to "embedded",
                2 to "optional",
                3 to "required",
                4 to "tool",
                5 to "incompatible",
                6 to "embedded",
            )
        target.add(
            "dependencies",
            gson.toJsonTree(
                file.array("dependencies").map {
                    val dep = it.asJsonObject
                    val dependency =
                        "curseforge:${dep.string("modId")}".also(::curseForgeProjectId)
                    val relation =
                        relations[dep.int("relationType")]
                            ?: error(I18n.t("packsB1.curseForgeFiles.unknownDependencyType"))
                    mapOf(
                        "project_id" to dependency,
                        "dependency_type" to relation,
                    )
                },
            ),
        )
    }

    private fun addFiles(
        target: JsonObject,
        project: JsonObject,
        file: JsonObject,
        sha1: String,
    ) {
        val filename = safeFilename(file.string("fileName"))
        val size = file.get("fileLength")?.asLong ?: -1
        require(size >= 0 && sha1.matches(Regex("[a-fA-F0-9]{40}"))) {
            I18n.t("packsB1.curseForgeFiles.missingSizeOrHash")
        }
        val restricted =
            project.get("allowModDistribution")?.takeUnless { it.isJsonNull }?.asBoolean ==
                false || file.string("downloadUrl").isBlank()
        if (restricted) {
            target.addProperty("manual_download", true)
            target.addProperty("website", websiteUrl(project, file))
        }
        val fileUrl =
            if (restricted) "" else contentFileUrl(api.rootUrl, file.string("downloadUrl"))
        target.add(
            "files",
            gson.toJsonTree(
                listOf(
                    mapOf(
                        "filename" to filename,
                        "primary" to true,
                        "url" to fileUrl,
                        "size" to size,
                        "hashes" to mapOf("sha1" to sha1),
                    ),
                ),
            ),
        )
    }

    /** 需要手动下载时指向项目页面；分类路径沿用镜像自己的 slug 表。 */
    private fun websiteUrl(
        project: JsonObject,
        file: JsonObject,
    ): String {
        val kindPath =
            curseForgeClassSlugs.values.find {
                project.obj("links").string("websiteUrl").contains("/$it/")
            } ?: "mc-mods"
        return "https://www.curseforge.com/minecraft/$kindPath/${encoded(
            project.string("slug"),
        )}/files/${file.string("id")}"
    }
}
