// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责内容文件索引、更新检查和版本锁定。

package dev.molifox.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

private const val DISABLED_FILE_SUFFIX_CHARS = 9
private const val MAX_PROJECT_DESCRIPTION_CHARS = 60000
private const val MAX_CHANGELOG_CHARS = 40000

/** 项目详情允许的资源类型；与搜索、安装入口使用同一套字符串标识。 */
private val supportedContentKinds =
    setOf("mod", "modpack", "resourcepack", "shader", "datapack", "world")

data class ContentVersion(
    val id: String,
    val name: String,
    val number: String,
    val channel: String,
    val published: String,
    val changelog: String,
    val gameVersions: List<String> = emptyList(),
    val loaders: List<String> = emptyList(),
    val featured: Boolean = false,
)

data class ContentDetails(
    val hit: ContentHit,
    val body: String,
    val license: String,
    val versions: List<ContentVersion>,
    val links: List<ContentLink> = emptyList(),
) {
    val providerName: String
        get() = if (hit.id.startsWith("curseforge:")) "CurseForge" else "Modrinth"
}

/** A hash verified local file; recognition is read-only until a user installs or locks it. */
data class ContentSource(
    val projectId: String,
    val versionId: String,
    val folder: String,
    val filename: String,
    val sha512: String,
    val title: String,
    val kind: String,
    val locked: Boolean = false,
)

data class ContentUpdate(
    val filename: String,
    val folder: String,
    val source: ContentSource? = null,
    val currentVersion: String = "",
    val update: ContentVersion? = null,
    val warning: String = "",
) {
    val key
        get() = "$folder/$filename"
}

data class ContentUpdateReport(
    val instanceId: String,
    val signature: String,
    val registryHash: String,
    val items: List<ContentUpdate>,
)

internal fun contentBaseFilename(filename: String) =
    if (filename.endsWith(".disabled", true)) {
        filename.dropLast(DISABLED_FILE_SUFFIX_CHARS)
    } else {
        filename
    }

internal fun contentSourceRecord(source: ContentSource) =
    JsonObject().apply {
        addProperty("file", contentBaseFilename(source.filename))
        addProperty("folder", source.folder)
        addProperty("versionId", source.versionId)
        addProperty("hash", source.sha512)
        addProperty("hashAlgorithm", "SHA-512")
        addProperty("locked", source.locked)
        addProperty("title", source.title)
    }

internal fun JsonObject.adoptContentSource(source: ContentSource) {
    add(
        source.projectId,
        contentSourceRecord(source.copy(locked = source.locked || obj(source.projectId).boolean("locked"))),
    )
}

private fun JsonObject.toContentHit(
    projectId: String,
    kind: String,
): ContentHit =
    ContentHit(
        projectId,
        string("slug"),
        string("title"),
        string("description"),
        string("author"),
        get("downloads")?.asLong ?: 0,
        kind,
        string("icon_url").takeIf { it.isNotBlank() },
    )

internal fun verifyContentSource(
    store: LauncherStore,
    instanceId: String,
    source: ContentSource,
) {
    require(source.folder in setOf("mods", "resourcepacks", "shaderpacks"))
    require(source.sha512.matches(Regex("[a-fA-F0-9]{128}")))
    val file =
        safePath(
            store.instancePath(instanceId).resolve(source.folder),
            safeFilename(source.filename),
        )
    require(
        file.isRegularFile() &&
            !Files.isSymbolicLink(file) &&
            digest(file, "SHA-512").equals(source.sha512, true),
    ) {
        I18n.t("contentA1.catalog.localFileChanged", source.filename)
    }
}

internal fun contentCompatible(
    info: JsonObject,
    instance: GameInstance,
    kind: String,
) = info.array("game_versions").any { it.asString == instance.gameVersion } &&
    (
        kind != "mod" ||
            info.array("loaders").any { it.asString.equals(instance.loader.name, true) }
    )

internal fun contentVersionOf(info: JsonObject) =
    ContentVersion(
        info.string("id"),
        info.string("name"),
        info.string("version_number"),
        info.string("version_type"),
        info.string("date_published"),
        info.string("changelog").take(MAX_CHANGELOG_CHARS),
        info.array("game_versions").map { it.asString },
        info.array("loaders").map { it.asString.lowercase() },
        info.boolean("featured"),
    )

class ContentCatalog
    internal constructor(
        private val store: LauncherStore,
        private val network: NetworkClient,
        private val api: String,
        private val backend: ContentBackend = ModrinthBackend(network, api),
        private val curseForge: CurseForgeBackend? = null,
    ) {
        private val encyclopedia = EncyclopediaLinks(network)

        /** 百科条目页：项目自己声明的地址优先，否则按项目名解析一次并缓存。 */
        private suspend fun encyclopediaFor(
            hit: ContentHit,
            project: JsonObject,
        ): String? {
            val declared = project.string("wiki_url")
            // 私有内容服务的项目名称不发送给第三方百科。
            val publicApi = runCatching { java.net.URI(api).host == "api.modrinth.com" }.getOrDefault(false)
            return if (publicApi) encyclopedia.resolve(hit, declared) else directEncyclopediaUrl(declared)
        }

        /** Lazy changelog loading avoids one extra request per version in a large project. */
        suspend fun browse(projectId: String): ContentDetails {
            val project = backend.project(projectId)
            val kind = project.string("project_type")
            require(kind in supportedContentKinds) { I18n.t("contentA1.catalog.unsupportedKind") }
            val hit = project.toContentHit(projectId, kind)
            val versions =
                backend
                    .browseVersions(projectId)
                    .filter { it.string("project_id") == projectId }
                    .map(::contentVersionOf)
                    .distinctBy { it.id }
                    .sortedByDescending { it.published }
            return ContentDetails(
                hit,
                project.string("body").take(MAX_PROJECT_DESCRIPTION_CHARS),
                project.obj("license").string("id"),
                versions,
                contentLinks(hit, project, encyclopediaFor(hit, project)),
            )
        }

        /**
         * 按文件 SHA-512 指纹认定项目：本地 jar 大多不带项目声明，只有指纹能对上下载页。
         *
         * 离线、镜像地址或未收录的项目一律返回 null，由调用方退回名称搜索。
         */
        suspend fun projectIdByHash(file: Path): String? =
            withContext(Dispatchers.IO) {
                val body =
                    JsonObject().apply {
                        add("hashes", gson.toJsonTree(listOf(digest(file, "SHA-512"))))
                        addProperty("algorithm", "sha512")
                    }
                val response =
                    try {
                        network.post("$api/version_files", body)
                    } catch (_: IOException) {
                        return@withContext null
                    } catch (_: JsonParseException) {
                        return@withContext null
                    } catch (_: IllegalStateException) {
                        return@withContext null
                    }
                response
                    .entrySet()
                    .firstNotNullOfOrNull { (_, value) ->
                        value
                            .takeIf(JsonElement::isJsonObject)
                            ?.asJsonObject
                            ?.string("project_id")
                            ?.takeIf { it.isNotBlank() }
                    }
            }

        suspend fun browseVersion(
            projectId: String,
            versionId: String,
        ): ContentVersion {
            val info = backend.version(versionId)
            require(info.string("project_id") == projectId && info.string("id") == versionId) {
                I18n.t("contentA1.catalog.versionNotInProject")
            }
            return contentVersionOf(info)
        }

        suspend fun versionDetails(
            projectId: String,
            versionId: String,
            instanceId: String,
        ): ContentVersion {
            val project = backend.project(projectId)
            val info = backend.version(versionId)
            require(
                info.string("project_id") == projectId &&
                    contentCompatible(
                        info,
                        store.instance(instanceId),
                        project.string("project_type"),
                    ),
            ) {
                I18n.t("contentA1.catalog.versionMismatch")
            }
            return contentVersionOf(info)
        }

        suspend fun details(
            projectId: String,
            instanceId: String,
        ): ContentDetails {
            val project = backend.project(projectId)
            val kind = project.string("project_type")
            require(kind in setOf("mod", "resourcepack", "shader")) {
                I18n.t("contentA1.catalog.versionManagementUnsupported")
            }
            val hit = project.toContentHit(projectId, kind)
            val instance = store.instance(instanceId)
            val versions =
                backend
                    .versions(projectId, instance, kind)
                    .filter {
                        contentCompatible(
                            it,
                            instance,
                            kind,
                        ) && it.string("project_id") == projectId
                    }.map(::contentVersionOf)
                    .sortedByDescending { it.published }
            return ContentDetails(
                hit,
                project.string("body").take(MAX_PROJECT_DESCRIPTION_CHARS),
                project.obj("license").string("id"),
                versions,
                // 与下载页同一套链接；百科页按项目名查一次并缓存，尽量给到条目页而不是搜索页。
                contentLinks(hit, project, encyclopediaFor(hit, project)),
            )
        }

        suspend fun checkUpdates(
            instanceId: String,
            includePrerelease: Boolean = false,
        ): ContentUpdateReport =
            ContentUpdateChecker(store, network, api, backend, curseForge)
                .check(instanceId, includePrerelease)

        fun setLocked(
            instanceId: String,
            source: ContentSource,
            locked: Boolean,
        ) {
            verifyContentSource(store, instanceId, source)
            val registryFile = store.instancePath(instanceId).resolve(".molifox-content.json")
            val registry = if (registryFile.exists()) readObject(registryFile) else JsonObject()
            require(
                registry.obj(source.projectId).string("file").let {
                    it.isBlank() || it == contentBaseFilename(source.filename)
                },
            ) {
                I18n.t("contentA1.catalog.duplicateLocalFiles")
            }
            registry.add(source.projectId, contentSourceRecord(source.copy(locked = locked)))
            writeJson(registryFile, registry)
        }
    }
