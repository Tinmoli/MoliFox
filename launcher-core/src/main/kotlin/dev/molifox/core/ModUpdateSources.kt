// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 适配不同资源服务的查询格式，避免后端差异扩散到更新规则。

package dev.molifox.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlin.io.path.name

/** 内容源默认顺序：Modrinth 优先，CurseForge 公共镜像兜底；两者都不需要 API Key。 */
internal fun defaultModUpdateSources(
    network: NetworkClient,
    api: String,
    curseForge: CurseForgeBackend,
): List<ModUpdateSource> =
    listOf(ModrinthModUpdateSource(network, api), CurseForgeModUpdateSource(curseForge))

internal fun defaultModDownloader(
    network: NetworkClient,
    modrinthApi: String,
    curseForgeApi: () -> String,
): ModUpdateDownloader =
    { version, target, context ->
        val api = if (version.project.provider == "curseforge") curseForgeApi() else modrinthApi
        val artifact =
            DownloadArtifact(
                contentFileUrl(api, version.downloadUrl),
                target,
                sha1 = version.sha512.ifBlank { version.sha1 },
                size = version.size,
                hashAlgorithm = if (version.sha512.isNotBlank()) "SHA-512" else "SHA-1",
            )
        network.download(artifact) { bytes, total ->
            context?.progress(
                if (total > 0) bytes.toFloat() / total else 0f,
                "下载 ${version.filename}",
            )
        }
    }

internal class ModrinthModUpdateSource(
    private val network: NetworkClient,
    private val api: String,
) : ModUpdateSource {
    override val provider = "modrinth"

    override suspend fun fingerprint(files: List<LocalModFile>): Map<String, RemoteModVersion> {
        val result = mutableMapOf<String, RemoteModVersion>()
        for (batch in files
            .map { it.sha512 }
            .filter { it.isNotBlank() }
            .distinct()
            .chunked(100)) {
            val body =
                JsonObject().apply {
                    add("hashes", gson.toJsonTree(batch))
                    addProperty("algorithm", "sha512")
                }
            network.post("$api/version_files", body).entrySet().forEach { (hash, value) ->
                val matching = files.filter { it.sha512.equals(hash, true) }
                if (matching.isEmpty()) return@forEach
                val info = value.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@forEach
                val project =
                    RemoteModProject(
                        info.string("project_id"),
                        "",
                        "",
                        provider,
                    )
                if (project.id.isBlank()) return@forEach
                val version = remoteVersion(info, project) ?: return@forEach
                matching.forEach { result[it.filename] = version }
            }
        }
        return result
    }

    /**
     * 一次 `POST /version_files/update` 结算整批文件的最新版本；服务端按游戏版本、加载器与
     * 发布类型筛选，因此常见实例的版本查询只有这一条请求。
     */
    override suspend fun latestVersions(
        files: List<LocalModFile>,
        instance: GameInstance,
        allowPrerelease: Boolean,
    ): Map<String, RemoteModVersion> {
        val result = mutableMapOf<String, RemoteModVersion>()
        val hashes = files.map { it.sha512 }.filter { it.isNotBlank() }.distinct()
        if (hashes.isEmpty()) return result
        for (batch in hashes.chunked(100)) {
            val body =
                JsonObject().apply {
                    add("hashes", gson.toJsonTree(batch))
                    addProperty("algorithm", "sha512")
                    if (instance.gameVersion.isNotBlank()) {
                        add("game_versions", gson.toJsonTree(listOf(instance.gameVersion)))
                    }
                    if (instance.loader != LoaderType.Vanilla) {
                        add("loaders", gson.toJsonTree(listOf(instance.loader.name.lowercase())))
                    }
                    add(
                        "version_types",
                        gson.toJsonTree(
                            if (allowPrerelease) {
                                listOf("release", "beta", "alpha")
                            } else {
                                listOf("release")
                            },
                        ),
                    )
                }
            network.post("$api/version_files/update", body).entrySet().forEach { (hash, value) ->
                val info = value.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@forEach
                val projectId = info.string("project_id")
                if (projectId.isBlank()) return@forEach
                val version =
                    remoteVersion(info, RemoteModProject(projectId, "", "", provider))
                        ?: return@forEach
                files.filter { it.sha512.equals(hash, true) }.forEach {
                    result[it.filename] = version
                }
            }
        }
        return result
    }

    override suspend fun projectBySlug(
        slug: String,
        instance: GameInstance,
    ): RemoteModProject? {
        val info = network.json("$api/project/${encoded(slug)}").asJsonObject
        if (info.string("project_type") != "mod") return null
        return RemoteModProject(
            info.string("id"),
            info.string("slug"),
            info.string("title"),
            provider,
        )
    }

    override suspend fun search(
        name: String,
        instance: GameInstance,
    ): List<RemoteModProject> {
        val facets = mutableListOf(listOf("project_type:mod"))
        if (instance.gameVersion.isNotBlank()) {
            facets += listOf("versions:${instance.gameVersion}")
        }
        if (instance.loader != LoaderType.Vanilla) {
            facets += listOf("categories:${instance.loader.name.lowercase()}")
        }
        val response =
            network
                .json(
                    "$api/search?query=${encoded(
                        name,
                    )}&limit=8&facets=${encoded(gson.toJson(facets))}",
                ).asJsonObject
        return response.array("hits").mapNotNull { element ->
            val hit =
                element.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
            val id = hit.string("project_id").ifBlank { hit.string("id") }
            val type = hit.string("project_type")
            if (id.isBlank() || (type.isNotBlank() && type != "mod")) return@mapNotNull null
            RemoteModProject(id, hit.string("slug"), hit.string("title"), provider)
        }
    }

    override suspend fun versions(
        project: RemoteModProject,
        instance: GameInstance,
    ): List<RemoteModVersion> {
        val filters =
            buildString {
                if (instance.gameVersion.isNotBlank()) {
                    append(
                        "&game_versions=${encoded(gson.toJson(listOf(instance.gameVersion)))}",
                    )
                }
                if (instance.loader != LoaderType.Vanilla) {
                    append(
                        "&loaders=${encoded(
                            gson.toJson(listOf(instance.loader.name.lowercase())),
                        )}",
                    )
                }
            }
        return network
            .json("$api/project/${encoded(project.id)}/version?include_changelog=false$filters")
            .asJsonArray
            .mapNotNull { element ->
                element.takeIf(JsonElement::isJsonObject)?.asJsonObject?.let {
                    remoteVersion(it, project)
                }
            }
    }
}

internal class CurseForgeModUpdateSource(
    private val curseForge: CurseForgeBackend,
) : ModUpdateSource {
    override val provider = "curseforge"

    /** 走批量指纹接口：一次指纹 + 一次文件批量 + 一次项目批量，不再按文件逐个查询版本。 */
    override suspend fun fingerprint(files: List<LocalModFile>): Map<String, RemoteModVersion> {
        if (files.isEmpty()) return emptyMap()
        val matched = curseForge.fingerprintFiles(files.map { it.path })
        return files
            .mapNotNull { file ->
                val info = matched[file.path] ?: return@mapNotNull null
                val modId = info.string("modId")
                if (modId.isBlank() || info.string("id").isBlank()) return@mapNotNull null
                val project = RemoteModProject("curseforge:$modId", "", "", provider)
                val version = remoteVersion(info, project) ?: return@mapNotNull null
                file.filename to version
            }.toMap()
    }

    override suspend fun projectBySlug(
        slug: String,
        instance: GameInstance,
    ): RemoteModProject? =
        curseForge
            .search(slug, "mod", instance, 0)
            .hits
            .firstOrNull { it.slug.equals(slug, true) }
            ?.let { RemoteModProject(it.id, it.slug, it.title, provider) }

    override suspend fun search(
        name: String,
        instance: GameInstance,
    ): List<RemoteModProject> =
        curseForge
            .search(name, "mod", instance, 0)
            .hits
            .map { RemoteModProject(it.id, it.slug, it.title, provider) }

    override suspend fun versions(
        project: RemoteModProject,
        instance: GameInstance,
    ): List<RemoteModVersion> =
        curseForge
            .versions(project.id, instance, "mod")
            .mapNotNull { remoteVersion(it, project) }
}

internal fun remoteVersion(
    info: JsonObject,
    project: RemoteModProject,
): RemoteModVersion? {
    val files =
        info.array("files").mapNotNull {
            it.takeIf(JsonElement::isJsonObject)?.asJsonObject
        }
    val file = files.firstOrNull { it.boolean("primary") } ?: files.firstOrNull() ?: return null
    val rawNumber = info.string("version_number").ifBlank { info.string("name") }
    // CurseForge 的 displayName 常带项目名与游戏版本，例如「Fabric API 0.92.3+1.20.1」。
    val number =
        if (project.provider == "curseforge") {
            normalizedRemoteVersionNumber(rawNumber)
        } else {
            rawNumber
        }
    return RemoteModVersion(
        project,
        info.string("id"),
        number,
        info.string("name"),
        info.array("game_versions").mapNotNull {
            it.takeIf(JsonElement::isJsonPrimitive)?.asString
        },
        info.array("loaders").mapNotNull {
            it.takeIf(JsonElement::isJsonPrimitive)?.asString?.lowercase()
        },
        info.string("version_type", "release"),
        file.string("url"),
        file.string("filename"),
        file.get("size")?.takeUnless { it.isJsonNull }?.asLong ?: 0,
        file.obj("hashes").string("sha512"),
        file.obj("hashes").string("sha1"),
    )
}
