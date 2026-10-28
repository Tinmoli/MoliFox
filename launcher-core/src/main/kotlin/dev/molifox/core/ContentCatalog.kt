// 负责内容文件索引、更新检查和版本锁定。

package dev.molifox.core

import com.google.gson.JsonObject
import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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
    if (filename.endsWith(".disabled", true)) filename.dropLast(9) else filename

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

internal fun verifyContentSource(store: LauncherStore, instanceId: String, source: ContentSource) {
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
            digest(file, "SHA-512").equals(source.sha512, true)
    ) {
        "本地文件已改变，请重新检查：${source.filename}"
    }
}

class ContentCatalog
internal constructor(
    private val store: LauncherStore,
    private val network: NetworkClient,
    private val api: String,
    private val backend: ContentBackend = ModrinthBackend(network, api),
    private val curseForge: CurseForgeBackend? = null,
) {
    private val encyclopedia = EncyclopediaLinks(network)

    /** Lazy changelog loading avoids one extra request per version in a large project. */
    suspend fun browse(projectId: String): ContentDetails {
        val project = backend.project(projectId)
        val kind = project.string("project_type")
        require(kind in setOf("mod", "modpack", "resourcepack", "shader", "datapack")) {
            "不支持的资源类型"
        }
        val hit =
            ContentHit(
                projectId,
                project.string("slug"),
                project.string("title"),
                project.string("description"),
                project.string("author"),
                project.get("downloads")?.asLong ?: 0,
                kind,
                project.string("icon_url").takeIf { it.isNotBlank() },
            )
        val versions =
            backend
                .browseVersions(projectId)
                .filter { it.string("project_id") == projectId }
                .map(::version)
                .distinctBy { it.id }
                .sortedByDescending { it.published }
        return ContentDetails(
            hit,
            project.string("body").take(60000),
            project.obj("license").string("id"),
            versions,
            contentLinks(
                hit,
                project,
                // 私有内容服务的项目名称不发送给第三方百科。
                if (java.net.URI(api).host == "api.modrinth.com")
                    encyclopedia.resolve(hit, project.string("wiki_url"))
                else directEncyclopediaUrl(project.string("wiki_url")),
            ),
        )
    }

    suspend fun browseVersion(projectId: String, versionId: String): ContentVersion {
        val info = backend.version(versionId)
        require(info.string("project_id") == projectId && info.string("id") == versionId) {
            "资源版本不属于当前项目"
        }
        return version(info)
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
                compatible(info, store.instance(instanceId), project.string("project_type"))
        ) {
            "资源版本与项目、游戏或加载器不匹配"
        }
        return version(info)
    }

    suspend fun details(projectId: String, instanceId: String): ContentDetails {
        val project = backend.project(projectId)
        val kind = project.string("project_type")
        require(kind in setOf("mod", "resourcepack", "shader")) { "此项目类型暂不支持版本管理" }
        val hit =
            ContentHit(
                projectId,
                project.string("slug"),
                project.string("title"),
                project.string("description"),
                project.string("author"),
                project.get("downloads")?.asLong ?: 0,
                kind,
                project.string("icon_url").takeIf { it.isNotBlank() },
            )
        val instance = store.instance(instanceId)
        val versions =
            backend
                .versions(projectId, instance, kind)
                .filter { compatible(it, instance, kind) && it.string("project_id") == projectId }
                .map(::version)
                .sortedByDescending { it.published }
        return ContentDetails(
            hit,
            project.string("body").take(60000),
            project.obj("license").string("id"),
            versions,
        )
    }

    suspend fun checkUpdates(
        instanceId: String,
        includePrerelease: Boolean = false,
    ): ContentUpdateReport {
        val instance = store.instance(instanceId)
        val root = store.instancePath(instanceId)
        val registryFile = root.resolve(".molifox-content.json")
        val registryHash = if (registryFile.exists()) digest(registryFile) else ""
        val registry = if (registryFile.exists()) readObject(registryFile) else JsonObject()
        data class Local(val folder: String, val filename: String, val hash: String)
        val local = mutableListOf<Local>()
        for (folder in listOf("mods", "resourcepacks", "shaderpacks")) {
            val directory = root.resolve(folder)
            if (!directory.exists()) continue
            for (file in directory.listDirectoryEntries().sortedBy { it.name }) {
                currentCoroutineContext().ensureActive()
                if (
                    !file.isRegularFile() ||
                        Files.isSymbolicLink(file) ||
                        !Regex(
                                if (folder == "mods") "(?i).*\\.jar(?:\\.disabled)?"
                                else "(?i).*\\.zip"
                            )
                            .matches(file.name)
                )
                    continue
                local += Local(folder, file.name, digest(file, "SHA-512"))
            }
        }
        val recognized = JsonObject()
        val latest = JsonObject()
        val useCurseForge = store.data.settings.contentDownloadSource == "curseforge"
        if (useCurseForge) {
            val provider = requireNotNull(curseForge) { "CurseForge 内容源未初始化" }
            val matches =
                provider.fingerprints(local.map { safePath(root.resolve(it.folder), it.filename) })
            val available = mutableMapOf<String, List<JsonObject>>()
            for (file in local) {
                val matched =
                    matches[safePath(root.resolve(file.folder), file.filename)] ?: continue
                val projectId = "curseforge:${matched.string("modId")}"
                val current = backend.version("$projectId:${matched.string("id")}")
                recognized.add(file.hash, current)
                val kind =
                    when (file.folder) {
                        "mods" -> "mod"
                        "resourcepacks" -> "resourcepack"
                        else -> "shader"
                    }
                val versions =
                    available[projectId]
                        ?: backend.versions(projectId, instance, kind).also {
                            available[projectId] = it
                        }
                versions
                    .firstOrNull {
                        compatible(it, instance, kind) &&
                            (includePrerelease || it.string("version_type") == "release")
                    }
                    ?.let { latest.add(file.hash, it) }
            }
        }
        // Bound request sizes; a failed batch must not turn into a false 'no updates' result.
        for (batch in
            (if (useCurseForge) emptyList() else local.map { it.hash }.distinct().chunked(100))) {
            val body =
                JsonObject().apply {
                    add("hashes", gson.toJsonTree(batch))
                    addProperty("algorithm", "sha512")
                }
            network.post("$api/version_files", body).entrySet().forEach { (key, value) ->
                recognized.add(key, value)
            }
            body.add(
                "loaders",
                gson.toJsonTree(
                    listOf(instance.loader.name.lowercase(), "minecraft", "iris", "optifine")
                ),
            )
            body.add("game_versions", gson.toJsonTree(listOf(instance.gameVersion)))
            body.add(
                "version_types",
                gson.toJsonTree(
                    if (includePrerelease) listOf("release", "beta", "alpha") else listOf("release")
                ),
            )
            network.post("$api/version_files/update", body).entrySet().forEach { (key, value) ->
                latest.add(key, value)
            }
        }
        val items = local.map { file ->
            val current = recognized.get(file.hash)?.takeIf { it.isJsonObject }?.asJsonObject
            val registered =
                registry
                    .entrySet()
                    .find {
                        it.value.isJsonObject &&
                            it.value.asJsonObject.string("folder") == file.folder &&
                            it.value.asJsonObject.string("file") ==
                                contentBaseFilename(file.filename)
                    }
                    ?.key
            if (registered != null && registered.startsWith("curseforge:") != useCurseForge)
                ContentUpdate(file.filename, file.folder, warning = "此文件登记在另一内容源，请切换下载来源后检查")
            else if (current == null)
                ContentUpdate(file.filename, file.folder, warning = "未识别来源，无法判断更新")
            else {
                val projectId = current.string("project_id")
                val record = registry.obj(projectId)
                val locked =
                    record.boolean("locked") &&
                        record.string("folder") == file.folder &&
                        record.string("file") == contentBaseFilename(file.filename) &&
                        record.string("versionId") == current.string("id")
                val kind =
                    when (file.folder) {
                        "mods" -> "mod"
                        "resourcepacks" -> "resourcepack"
                        else -> "shader"
                    }
                val source =
                    ContentSource(
                        projectId,
                        current.string("id"),
                        file.folder,
                        file.filename,
                        file.hash,
                        current.string("name", projectId),
                        kind,
                        locked,
                    )
                val candidate = latest.get(file.hash)?.takeIf { it.isJsonObject }?.asJsonObject
                val newer = candidate?.takeIf {
                    it.string("project_id") == projectId &&
                        it.string("id") != current.string("id") &&
                        compatible(it, instance, kind) &&
                        (includePrerelease || it.string("version_type") == "release") &&
                        runCatching {
                                Instant.parse(it.string("date_published")) >
                                    Instant.parse(current.string("date_published"))
                            }
                            .getOrDefault(false)
                }
                ContentUpdate(
                    file.filename,
                    file.folder,
                    source,
                    current.string("version_number", current.string("name")),
                    if (locked) null else newer?.let(::version),
                    if (locked) "版本已锁定" else "",
                )
            }
        }
        require(
            contentSignature(store, store.instance(instanceId)) ==
                contentSignature(store, instance) &&
                (if (registryFile.exists()) digest(registryFile) else "") == registryHash
        ) {
            "检查期间实例或资源列表已改变，请重试"
        }
        return ContentUpdateReport(
            instanceId,
            contentSignature(store, instance),
            registryHash,
            items,
        )
    }

    fun setLocked(instanceId: String, source: ContentSource, locked: Boolean) {
        verifyContentSource(store, instanceId, source)
        val registryFile = store.instancePath(instanceId).resolve(".molifox-content.json")
        val registry = if (registryFile.exists()) readObject(registryFile) else JsonObject()
        require(
            registry.obj(source.projectId).string("file").let {
                it.isBlank() || it == contentBaseFilename(source.filename)
            }
        ) {
            "同一项目有多个本地文件，请先整理重复版本"
        }
        registry.add(source.projectId, contentSourceRecord(source.copy(locked = locked)))
        writeJson(registryFile, registry)
    }

    private fun compatible(info: JsonObject, instance: GameInstance, kind: String) =
        info.array("game_versions").any { it.asString == instance.gameVersion } &&
            (kind != "mod" ||
                info.array("loaders").any { it.asString.equals(instance.loader.name, true) })

    private fun version(info: JsonObject) =
        ContentVersion(
            info.string("id"),
            info.string("name"),
            info.string("version_number"),
            info.string("version_type"),
            info.string("date_published"),
            info.string("changelog").take(40000),
            info.array("game_versions").map { it.asString },
            info.array("loaders").map { it.asString.lowercase() },
            info.boolean("featured"),
        )
}
