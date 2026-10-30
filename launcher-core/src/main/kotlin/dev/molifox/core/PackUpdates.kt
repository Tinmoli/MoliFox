// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责已安装资源包与光影的更新检查（指纹优先，其次按名称匹配）。

package dev.molifox.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** 实例内容目录：资源包与光影共用同一套更新检查，只是内容源的类型不同。 */
enum class PackFolder(
    val directory: String,
    val kind: String,
    val title: String,
) {
    ResourcePacks("resourcepacks", "resourcepack", "资源包"),
    ShaderPacks("shaderpacks", "shader", "光影"),
    ;

    companion object {
        fun of(directory: String): PackFolder? = entries.find { it.directory == directory }
    }
}

/** 与模组一致的三态：有更新、已是最新、无法确认当前版本。 */
enum class PackUpdateStatus(
    val title: String,
) {
    Updatable("有更新"),
    Latest("已是最新"),
    Unknown("无法确认"),
}

/** 识别依据：文件指纹最可靠，名称搜索只作兜底。 */
enum class PackMatchSource(
    val title: String,
) {
    Fingerprint("文件指纹"),
    Name("名称搜索"),
}

data class PackUpdateCandidate(
    val projectId: String,
    val projectTitle: String,
    val versionId: String,
    val versionNumber: String,
    val filename: String,
    val downloadUrl: String,
    val provider: String,
    val gameVersions: List<String>,
)

data class PackUpdateItem(
    val filename: String,
    val folder: PackFolder,
    val currentVersion: String = "",
    val status: PackUpdateStatus = PackUpdateStatus.Unknown,
    val latestVersion: String = "",
    val candidate: PackUpdateCandidate? = null,
    val matchSource: PackMatchSource? = null,
    /** 名称搜索得到唯一候选但无法确认当前版本时标注，供界面提示。 */
    val possibleMatch: Boolean = false,
    val warning: String = "",
)

data class PackUpdateReport(
    val instanceId: String,
    val gameVersion: String,
    val items: List<PackUpdateItem>,
) {
    fun items(folder: PackFolder): List<PackUpdateItem> = items.filter { it.folder == folder }

    fun updatable(folder: PackFolder): Int =
        items(folder).count { it.status == PackUpdateStatus.Updatable }

    fun latest(folder: PackFolder): Int =
        items(folder).count { it.status == PackUpdateStatus.Latest }

    fun unknown(folder: PackFolder): Int =
        items(folder).count { it.status == PackUpdateStatus.Unknown }

    /** 该目录里「没找到更新」的数量：已是最新 + 无法确认。 */
    fun unchanged(folder: PackFolder): Int = latest(folder) + unknown(folder)
}

/** 一个已安装资源包/光影的本地信息；SHA-512 按路径 + 大小 + 修改时间缓存。 */
internal data class LocalPackFile(
    val path: Path,
    val filename: String,
    val size: Long,
    val modified: Long,
    val sha512: String,
)

/**
 * 资源包与光影的更新查询。
 *
 * 指纹接口按「一批本地文件 + 目录类型」返回命中的版本；实现方必须只返回该目录对应类型的项目。 名称搜索与版本列表不区分目录，由 [PackUpdateService]
 * 统一按游戏版本规则过滤。
 */
internal interface PackUpdateSource {
    val provider: String

    suspend fun fingerprint(
        files: List<LocalPackFile>,
        folder: PackFolder,
    ): Map<String, RemoteModVersion>

    /**
     * 指纹命中后的批量「最新可用版本」查询；返回 null 表示该源没有批量接口，调用方回退到
     * 逐项目 [versions]。映射里缺少某个文件表示「没有适用的更新」。
     */
    suspend fun latestVersions(
        files: List<LocalPackFile>,
        folder: PackFolder,
        instance: GameInstance,
        allowPrerelease: Boolean,
    ): Map<String, RemoteModVersion>? = null

    suspend fun search(
        name: String,
        folder: PackFolder,
        instance: GameInstance,
    ): List<RemoteModProject>

    suspend fun versions(
        project: RemoteModProject,
        folder: PackFolder,
        instance: GameInstance,
    ): List<RemoteModVersion>
}

/**
 * 资源包与光影的更新检查。
 *
 * 识别顺序：文件 SHA-512 指纹 → 名称搜索（去掉扩展名与版本号）。指纹能拿到确切版本， 因此可以判断是否有更新；名称匹配只有在项目版本里找到同名文件时才算得出当前版本，
 * 否则标记「无法确认」并说明原因。结果只读，不下载、不替换文件。
 */
class PackUpdateService
    internal constructor(
        private val store: LauncherStore,
        private val sources: List<PackUpdateSource>,
    ) {
        private data class Cached(
            val size: Long,
            val modified: Long,
            val sha512: String,
        )

        private val cache =
            object : LinkedHashMap<Path, Cached>(64, .75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Path, Cached>?) =
                    size > 512
            }

        @Synchronized
        private fun localFile(path: Path): LocalPackFile {
            val normalized = path.toAbsolutePath().normalize()
            val directory = runCatching { path.isDirectory() }.getOrDefault(false)
            val size = runCatching { if (directory) 0L else path.fileSize() }.getOrDefault(0L)
            val modified = runCatching { path.getLastModifiedTime().toMillis() }.getOrDefault(0L)
            // 文件夹形式的包没有单文件指纹，只能按名称匹配。
            val hash =
                if (directory) {
                    ""
                } else {
                    cache[normalized]
                        ?.takeIf { it.size == size && it.modified == modified }
                        ?.sha512
                        ?: runCatching { digest(path, "SHA-512") }
                            .getOrDefault("")
                            .also { cache[normalized] = Cached(size, modified, it) }
                }
            return LocalPackFile(normalized, path.name, size, modified, hash)
        }

        suspend fun check(
            instanceId: String,
            includePrerelease: Boolean = false,
        ): PackUpdateReport =
            withContext(Dispatchers.IO) {
                val startedAt = System.nanoTime()
                val instance = store.instance(instanceId)
                val root = store.instancePath(instanceId)
                val items = mutableListOf<PackUpdateItem>()
                val failures = mutableListOf<String>()
                var attempted = 0
                var scanned = 0
                var scanMs = 0L
                var fingerprintMs = 0L
                val versionStarted = System.nanoTime()
                for (folder in PackFolder.entries) {
                    currentCoroutineContext().ensureActive()
                    val scanStarted = System.nanoTime()
                    val files = readLocalPacks(root.resolve(folder.directory))
                    scanMs += elapsedMillis(scanStarted)
                    scanned += files.size
                    if (files.isEmpty()) continue
                    val fingerprints =
                        mutableMapOf<String, Pair<PackUpdateSource, RemoteModVersion>>()
                    // 某个源失败只影响它自己；两个源都失败时整次检查报错，而不是当成「没有更新」。
                    val fingerprintStarted = System.nanoTime()
                    for (source in sources) {
                        currentCoroutineContext().ensureActive()
                        val pending = files.filter { it.filename !in fingerprints }
                        if (pending.isEmpty()) break
                        attempted++
                        try {
                            source.fingerprint(pending, folder).forEach { (filename, version) ->
                                fingerprints[filename] = source to version
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            failures +=
                                "${source.provider}：${
                                    error.message.orEmpty().take(160).ifBlank { "请求失败" }
                                }"
                        }
                    }
                    fingerprintMs += elapsedMillis(fingerprintStarted)
                    // 指纹命中的包先用批量接口结算最新版本，只有名称匹配才逐项目查询。
                    val batch =
                        batchLatest(files, fingerprints, folder, instance, includePrerelease)
                    val inspected =
                        coroutineScope {
                            val gate = Semaphore(UPDATE_LOOKUP_CONCURRENCY)
                            files
                                .map { file ->
                                    async(Dispatchers.IO) {
                                        gate.withPermit {
                                            inspect(
                                                instance,
                                                folder,
                                                file,
                                                fingerprints[file.filename],
                                                batch[file.filename],
                                                includePrerelease,
                                            )
                                        }
                                    }
                                }.awaitAll()
                        }
                    items += inspected
                }
                val versionMs = elapsedMillis(versionStarted) - scanMs - fingerprintMs
                if (attempted > 0 && failures.size == attempted && scanned > 0) {
                    throw IllegalStateException("检查更新失败：${failures.joinToString("；")}")
                }
                store.log(
                    "资源包与光影更新检查：扫描 $scanned 个文件 $scanMs ms，指纹查询 $fingerprintMs ms，" +
                        "版本查询 $versionMs ms，总计 ${elapsedMillis(startedAt)} ms",
                )
                PackUpdateReport(instanceId, instance.gameVersion, items)
            }

        /**
         * 按「内容源 + 是否接受预发布」分组调用批量接口；不支持的源返回 null，对应文件留待逐项目查询。
         */
        private suspend fun batchLatest(
            files: List<LocalPackFile>,
            fingerprints: Map<String, Pair<PackUpdateSource, RemoteModVersion>>,
            folder: PackFolder,
            instance: GameInstance,
            includePrerelease: Boolean,
        ): Map<String, BatchLatest> {
            val result = mutableMapOf<String, BatchLatest>()
            val groups =
                files
                    .mapNotNull { file ->
                        val hit = fingerprints[file.filename] ?: return@mapNotNull null
                        Triple(file, hit.first, hit.second)
                    }.groupBy { (_, source, installed) ->
                        source.provider to
                            (includePrerelease || isPrereleaseVersion(installed.number))
                    }
            for ((key, group) in groups) {
                val (provider, allowPrerelease) = key
                val latest =
                    try {
                        group.first().second.latestVersions(
                            group.map { it.first },
                            folder,
                            instance,
                            allowPrerelease,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        store.log(
                            "$provider 批量版本查询失败，回退到逐项目查询：" +
                                error.message
                                    .orEmpty()
                                    .take(120)
                                    .ifBlank { "请求失败" },
                            true,
                        )
                        null
                    } ?: continue
                group.forEach { (file, _, _) ->
                    result[file.filename] = BatchLatest(true, latest[file.filename])
                }
            }
            return result
        }

        private suspend fun inspect(
            instance: GameInstance,
            folder: PackFolder,
            file: LocalPackFile,
            fingerprint: Pair<PackUpdateSource, RemoteModVersion>?,
            batch: BatchLatest?,
            includePrerelease: Boolean,
        ): PackUpdateItem {
            val base =
                PackUpdateItem(
                    filename = file.filename,
                    folder = folder,
                    currentVersion = fingerprint?.second?.number.orEmpty(),
                )
            if (fingerprint != null) {
                val (source, version) = fingerprint
                return resolve(
                    instance,
                    folder,
                    file,
                    base,
                    source,
                    version.project,
                    version.number,
                    PackMatchSource.Fingerprint,
                    false,
                    includePrerelease,
                    latestKnown = batch?.version,
                    batchChecked = batch?.checked == true,
                )
            }
            // 名称搜索：去掉扩展名，把 . - _ 换成空格，只保留较长的名字片段。
            val query = packSearchName(file.filename)
            val candidates = mutableListOf<Pair<PackUpdateSource, RemoteModProject>>()
            var searchError = ""
            for (source in sources) {
                currentCoroutineContext().ensureActive()
                try {
                    source.search(query, folder, instance).forEach { project ->
                        if (nameMatches(query, project.title) || nameMatches(query, project.slug)) {
                            candidates += source to project
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    searchError = error.message.orEmpty().take(120)
                }
            }
            val distinct = candidates.distinctBy { it.second.id }
            if (distinct.isEmpty()) {
                // 搜索失败只留在技术日志里；界面上这是「无法确认」，不能读成检查失败。
                if (searchError.isNotBlank()) {
                    store.log("光影/资源包名称搜索未返回结果：${file.filename} · $searchError")
                }
                return base.copy(
                    status = PackUpdateStatus.Unknown,
                    warning = "未收录或名称无法匹配，无法确认更新",
                )
            }
            if (distinct.size > 1) {
                return base.copy(
                    status = PackUpdateStatus.Unknown,
                    possibleMatch = true,
                    warning =
                        "可能匹配到多个项目（" +
                            distinct.take(3).joinToString("、") { it.second.title } +
                            "），请在项目页确认",
                )
            }
            val (source, project) = distinct.single()
            return resolve(
                instance,
                folder,
                file,
                base,
                source,
                project,
                "",
                PackMatchSource.Name,
                true,
                includePrerelease,
            )
        }

        private suspend fun resolve(
            instance: GameInstance,
            folder: PackFolder,
            file: LocalPackFile,
            base: PackUpdateItem,
            source: PackUpdateSource,
            project: RemoteModProject,
            currentVersion: String,
            matchSource: PackMatchSource,
            possible: Boolean,
            includePrerelease: Boolean,
            latestKnown: RemoteModVersion? = null,
            batchChecked: Boolean = false,
        ): PackUpdateItem {
            val versions =
                if (batchChecked) {
                    emptyList()
                } else {
                    try {
                        source.versions(project, folder, instance)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        return base.copy(
                            matchSource = matchSource,
                            possibleMatch = possible,
                            warning = "读取项目版本失败：${error.message.orEmpty().take(120)}",
                        )
                    }
                }
            val pool =
                if (batchChecked) {
                    // 批量接口已按游戏版本与发布类型筛选，这里只做一次本地复核。
                    listOfNotNull(
                        latestKnown?.takeIf {
                            it.number.isNotBlank() &&
                                packVersionMatches(folder, it.gameVersions, instance.gameVersion)
                        },
                    )
                } else {
                    val compatible =
                        versions.filter {
                            packVersionMatches(folder, it.gameVersions, instance.gameVersion)
                        }
                    val allowPrerelease = includePrerelease || isPrereleaseVersion(currentVersion)
                    compatible.filter { allowPrerelease || it.versionType.equals("release", true) }
                }
            val latest =
                pool.maxWithOrNull(
                    Comparator { left, right ->
                        compareModVersions(left.number, right.number)
                    },
                )
            // 名称匹配时，项目版本里与本地文件同名的那个就是当前安装的版本。
            val current =
                currentVersion.ifBlank {
                    versions
                        .firstOrNull {
                            it.filename.equals(
                                file.filename,
                                true,
                            )
                        }?.number
                        .orEmpty()
                }
            if (current.isBlank() || latest == null || latest.number.isBlank()) {
                return base.copy(
                    status = PackUpdateStatus.Unknown,
                    matchSource = matchSource,
                    possibleMatch = possible,
                    warning =
                        if (latest == null) {
                            "没有适用于当前游戏版本的版本"
                        } else {
                            "无法确认当前版本，请到项目页核对"
                        },
                )
            }
            val newer = compareModVersions(latest.number, current) > 0
            return base.copy(
                status = if (newer) PackUpdateStatus.Updatable else PackUpdateStatus.Latest,
                currentVersion = current,
                latestVersion = latest.number,
                candidate = if (newer) candidateOf(latest) else null,
                matchSource = matchSource,
                possibleMatch = possible,
                warning =
                    when {
                        newer && latest.downloadUrl.isBlank() -> "最新版本需要到项目页手动下载"
                        possible -> "按名称匹配，可能不是同一项目"
                        else -> ""
                    },
            )
        }

        private fun candidateOf(version: RemoteModVersion): PackUpdateCandidate? {
            if (version.filename.isBlank() || !version.filename.endsWith(".zip", true)) return null
            return PackUpdateCandidate(
                projectId = version.project.id,
                projectTitle = version.project.title,
                versionId = version.id,
                versionNumber = version.number,
                filename = version.filename,
                downloadUrl = version.downloadUrl,
                provider = version.project.provider,
                gameVersions = version.gameVersions,
            )
        }

        /** 只列直接子项：`.zip` 文件与文件夹；跳过隐藏文件、临时文件与符号链接。 */
        private fun readLocalPacks(directory: Path): List<LocalPackFile> {
            if (!directory.isDirectory() || Files.isSymbolicLink(directory)) return emptyList()
            return runCatching {
                directory
                    .listDirectoryEntries()
                    .filter {
                        !it.name.startsWith(".") &&
                            !Files.isSymbolicLink(it) &&
                            (it.isRegularFile() || it.isDirectory())
                    }.filter { it.isDirectory() || it.name.endsWith(".zip", true) }
                    .sortedBy { it.name.lowercase() }
                    .map { localFile(it) }
            }.getOrElse { emptyList() }
        }
    }

/**
 * 候选版本的 Minecraft 版本规则。
 *
 * 资源包必须声明当前游戏版本；光影包经常不声明版本，声明为空时按「不限版本」接受。
 */
internal fun packVersionMatches(
    folder: PackFolder,
    gameVersions: Collection<String>,
    gameVersion: String,
): Boolean {
    if (gameVersion.isBlank()) return true
    val declared = gameVersions.filter { it.isNotBlank() }
    return when (folder) {
        PackFolder.ResourcePacks -> declared.any { it.equals(gameVersion, true) }
        PackFolder.ShaderPacks ->
            declared.isEmpty() || declared.any { it.equals(gameVersion, true) }
    }
}

/** 由文件名得到搜索词：去掉 `.zip`、把 `.`/`_`/`-` 换成空格、丢掉纯版本段。 */
internal fun packSearchName(filename: String): String {
    val base =
        contentBaseFilename(filename)
            .removeSuffix(".zip")
            .replace(Regex("(?i)\\.zip$"), "")
    val cleaned =
        base
            .replace(Regex("[._-]+"), " ")
            .split(' ')
            .filter { it.isNotBlank() && !it.matches(Regex("(?i)v?\\d+(?:\\.\\d+)*[a-z]?")) }
            .joinToString(" ")
            .trim()
    return cleaned.ifBlank { base.trim() }
}

/** 默认内容源：Modrinth 优先，CurseForge 公共镜像兜底；资源包 classId 12、光影 6552 由镜像分类表解析。 */
internal fun defaultPackUpdateSources(
    network: NetworkClient,
    api: String,
    curseForge: CurseForgeBackend,
): List<PackUpdateSource> =
    listOf(ModrinthPackUpdateSource(network, api), CurseForgePackUpdateSource(curseForge))

internal class ModrinthPackUpdateSource(
    private val network: NetworkClient,
    private val api: String,
) : PackUpdateSource {
    override val provider = "modrinth"

    private val projectKinds = mutableMapOf<String, String?>()

    /** 一次 `GET /projects?ids=[...]` 取回整批项目类型，取代每个文件一次 `/project/{id}`。 */
    private suspend fun projectKinds(ids: List<String>): Map<String, String?> {
        val missing = ids.distinct().filter { it.isNotBlank() && it !in projectKinds }
        if (missing.isNotEmpty()) {
            for (batch in missing.chunked(50)) {
                runCatching {
                    network
                        .json("$api/projects?ids=${encoded(gson.toJson(batch))}")
                        .asJsonArray
                        .forEach { element ->
                            val info =
                                runCatching { element.asJsonObject }.getOrNull()
                                    ?: return@forEach
                            val id = info.string("id")
                            if (id.isNotBlank()) projectKinds[id] = info.string("project_type")
                        }
                }.onFailure { missing.forEach { id -> projectKinds.putIfAbsent(id, null) } }
            }
        }
        return ids.associateWith { projectKinds[it] }
    }

    override suspend fun fingerprint(
        files: List<LocalPackFile>,
        folder: PackFolder,
    ): Map<String, RemoteModVersion> {
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
                currentCoroutineContext().ensureActive()
                val matching = files.filter { it.sha512.equals(hash, true) }
                if (matching.isEmpty()) return@forEach
                val info = value.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@forEach
                val projectId = info.string("project_id")
                if (projectId.isBlank()) return@forEach
                val kinds = projectKinds(listOf(projectId))
                if (kinds[projectId] != folder.kind) return@forEach
                val project = RemoteModProject(projectId, "", "", provider)
                val version = remoteVersion(info, project) ?: return@forEach
                matching.forEach { result[it.filename] = version }
            }
        }
        return result
    }

    /** 一次 `POST /version_files/update` 结算整批资源包/光影的最新版本。 */
    override suspend fun latestVersions(
        files: List<LocalPackFile>,
        folder: PackFolder,
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
                    // 资源包必须匹配游戏版本；光影常不声明版本，交给本地规则判断。
                    if (folder == PackFolder.ResourcePacks && instance.gameVersion.isNotBlank()) {
                        add("game_versions", gson.toJsonTree(listOf(instance.gameVersion)))
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

    override suspend fun search(
        name: String,
        folder: PackFolder,
        instance: GameInstance,
    ): List<RemoteModProject> {
        val facets = mutableListOf(listOf("project_type:${folder.kind}"))
        // 资源包必须匹配游戏版本；光影常不声明版本，因此不按版本筛，交给统一规则判断。
        if (folder == PackFolder.ResourcePacks && instance.gameVersion.isNotBlank()) {
            facets += listOf("versions:${instance.gameVersion}")
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
            if (id.isBlank() || (type.isNotBlank() && type != folder.kind)) return@mapNotNull null
            RemoteModProject(id, hit.string("slug"), hit.string("title"), provider)
        }
    }

    override suspend fun versions(
        project: RemoteModProject,
        folder: PackFolder,
        instance: GameInstance,
    ): List<RemoteModVersion> =
        network
            .json("$api/project/${encoded(project.id)}/version?include_changelog=false")
            .asJsonArray
            .mapNotNull { element ->
                element.takeIf(JsonElement::isJsonObject)?.asJsonObject?.let {
                    remoteVersion(it, project)
                }
            }
}

internal class CurseForgePackUpdateSource(
    private val curseForge: CurseForgeBackend,
) : PackUpdateSource {
    override val provider = "curseforge"

    /** 批量指纹 + 批量文件/项目查询：请求数与包数量无关，并按镜像分类表过滤资源类型。 */
    override suspend fun fingerprint(
        files: List<LocalPackFile>,
        folder: PackFolder,
    ): Map<String, RemoteModVersion> {
        if (files.isEmpty()) return emptyMap()
        val matched = curseForge.fingerprintFiles(files.map { it.path }, folder.kind)
        val result = mutableMapOf<String, RemoteModVersion>()
        for (file in files) {
            currentCoroutineContext().ensureActive()
            val info = matched[file.path] ?: continue
            val modId = info.string("modId")
            if (modId.isBlank() || info.string("id").isBlank()) continue
            val project = RemoteModProject("curseforge:$modId", "", "", provider)
            val version = remoteVersion(info, project) ?: continue
            result[file.filename] = version
        }
        return result
    }

    override suspend fun search(
        name: String,
        folder: PackFolder,
        instance: GameInstance,
    ): List<RemoteModProject> =
        curseForge
            .search(name, folder.kind, instance, 0, ContentFilter())
            .hits
            .map { RemoteModProject(it.id, it.slug, it.title, provider) }

    override suspend fun versions(
        project: RemoteModProject,
        folder: PackFolder,
        instance: GameInstance,
    ): List<RemoteModVersion> =
        curseForge.available(project.id).mapNotNull { remoteVersion(it, project) }
}
