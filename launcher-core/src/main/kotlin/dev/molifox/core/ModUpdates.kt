// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责已安装模组的识别、更新检查与安全替换。

package dev.molifox.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.io.path.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 更新检查结果：有更新、匹配到项目但已是最新、或无法确认当前版本。 */
enum class ModUpdateStatus(val title: String) {
    Updatable("有更新"),
    Latest("已是最新"),
    Unknown("无法确认"),
}

/** 识别依据：文件指纹最可靠，其次是声明里的项目标识，最后才是按名称搜索。 */
enum class ModMatchSource(val title: String) {
    Fingerprint("文件指纹"),
    ProjectId("项目标识"),
    Name("名称搜索"),
}

data class ModUpdateCandidate(
    val projectId: String,
    val projectTitle: String,
    val versionId: String,
    val versionNumber: String,
    val filename: String,
    val downloadUrl: String,
    val size: Long,
    val sha512: String,
    val sha1: String,
    val gameVersions: List<String>,
    val loaders: List<String>,
    val provider: String,
)

data class ModUpdateItem(
    val filename: String,
    val title: String,
    val currentVersion: String,
    val enabled: Boolean,
    val size: Long,
    val modified: Long,
    val status: ModUpdateStatus = ModUpdateStatus.Unknown,
    val latestVersion: String = "",
    val candidate: ModUpdateCandidate? = null,
    val matchSource: ModMatchSource? = null,
    /** 按名称搜索得到的匹配：界面上标注「可能匹配」，不会批量自动应用。 */
    val possibleMatch: Boolean = false,
    /** 是否可以由用户显式应用；无法确认、多候选名称匹配或缺少下载地址时为 false。 */
    val applicable: Boolean = false,
    val warning: String = "",
)

data class ModUpdateReport(
    val instanceId: String,
    val gameVersion: String,
    val loader: LoaderType,
    val items: List<ModUpdateItem>,
) {
    val updates
        get() = items.count { it.status == ModUpdateStatus.Updatable }

    val latest
        get() = items.count { it.status == ModUpdateStatus.Latest }

    val unknown
        get() = items.count { it.status == ModUpdateStatus.Unknown }
}

/** 一个已安装 JAR 的本地信息；SHA-512 与声明一起按路径 + 大小 + 修改时间缓存。 */
internal data class LocalModFile(
    val path: Path,
    val filename: String,
    val size: Long,
    val modified: Long,
    val enabled: Boolean,
    val sha512: String,
    val metadata: ModMetadata,
)

internal data class RemoteModProject(
    val id: String,
    val slug: String,
    val title: String,
    val provider: String,
)

internal data class RemoteModVersion(
    val project: RemoteModProject,
    val id: String,
    val number: String,
    val name: String,
    val gameVersions: List<String>,
    val loaders: List<String>,
    val versionType: String,
    val downloadUrl: String,
    val filename: String,
    val size: Long,
    val sha512: String,
    val sha1: String,
)

/** 每个内容源的更新查询；真实实现全部走 NetworkClient / CurseForgeBackend，测试可注入替身。 */
internal interface ModUpdateSource {
    val provider: String

    suspend fun fingerprint(files: List<LocalModFile>): Map<String, RemoteModVersion>

    /**
     * 指纹命中后的批量「最新可用版本」查询：一次请求结算多个文件。
     *
     * 返回 null 表示该源没有批量接口，调用方回退到逐项目 [versions]。
     * 返回的映射里缺少某个文件表示「没有适用的更新」，与「没查」区分开。
     */
    suspend fun latestVersions(
        files: List<LocalModFile>,
        instance: GameInstance,
        allowPrerelease: Boolean,
    ): Map<String, RemoteModVersion>? = null

    suspend fun projectBySlug(slug: String, instance: GameInstance): RemoteModProject?

    suspend fun search(name: String, instance: GameInstance): List<RemoteModProject>

    suspend fun versions(project: RemoteModProject, instance: GameInstance): List<RemoteModVersion>
}

/** 批量版本查询的结果：是否已批量结算、以及结算出的最新版本（null 表示没有可用更新）。 */
internal data class BatchLatest(val checked: Boolean, val version: RemoteModVersion?)

/** 逐项目版本查询的并发上限：服务端能承受，又不至于把一条慢连接排成一列。 */
internal const val UPDATE_LOOKUP_CONCURRENCY = 6

internal fun elapsedMillis(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

internal typealias ModUpdateDownloader =
    suspend (version: RemoteModVersion, target: Path, context: JobContext?) -> Unit

private val modFilePattern = Regex("(?i).*\\.jar(?:\\.disabled)?")

/**
 * 模组更新检查与替换。
 *
 * 识别顺序：文件 SHA-512 指纹 → 声明中的 Modrinth / CurseForge 项目标识 → 名称搜索。 指纹与项目标识算确定匹配；名称搜索只作最后手段，结果标记
 * `possibleMatch`，多候选时不提供更新。
 */
class ModUpdateService
internal constructor(
    private val store: LauncherStore,
    private val metadataReader: ModMetadataReader,
    private val sources: List<ModUpdateSource>,
    private val downloader: ModUpdateDownloader,
    /** 摘要实现可注入：测试用它确认重复检查复用了缓存，而不是重新读整颗 jar。 */
    private val hasher: (Path, String) -> String = { path, algorithm -> digest(path, algorithm) },
) {
    private data class Cached(val size: Long, val modified: Long, val file: LocalModFile)

    private val cache =
        object : LinkedHashMap<Path, Cached>(64, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Path, Cached>?) =
                size > 1024
        }

    @Synchronized
    private fun localFile(path: Path): LocalModFile {
        val normalized = path.toAbsolutePath().normalize()
        val size = path.fileSize()
        val modified = path.getLastModifiedTime().toMillis()
        cache[normalized]
            ?.takeIf { it.size == size && it.modified == modified }
            ?.let {
                return it.file
            }
        val file =
            LocalModFile(
                normalized,
                path.name,
                size,
                modified,
                !path.name.endsWith(".disabled", true),
                hasher(path, "SHA-512"),
                metadataReader.read(path),
            )
        cache[normalized] = Cached(size, modified, file)
        return file
    }

    suspend fun check(instanceId: String, includePrerelease: Boolean = false): ModUpdateReport =
        withContext(Dispatchers.IO) {
            val startedAt = System.nanoTime()
            val instance = store.instance(instanceId)
            val directory = store.instancePath(instanceId).resolve("mods")
            if (!directory.exists()) {
                return@withContext ModUpdateReport(
                    instanceId,
                    instance.gameVersion,
                    instance.loader,
                    emptyList(),
                )
            }
            val scanStarted = System.nanoTime()
            val files =
                directory
                    .listDirectoryEntries()
                    .filter {
                        it.isRegularFile() &&
                            !Files.isSymbolicLink(it) &&
                            !it.name.startsWith(".molifox-") &&
                            modFilePattern.matches(it.name)
                    }
                    .sortedBy { it.name.lowercase() }
                    .map {
                        currentCoroutineContext().ensureActive()
                        localFile(it)
                    }
            val scanMs = elapsedMillis(scanStarted)
            // 指纹：每个源一次批量请求；某个源失败只影响它自己，全部失败则报错而不是「没有更新」。
            val fingerprintStarted = System.nanoTime()
            val fingerprints = mutableMapOf<String, Pair<ModUpdateSource, RemoteModVersion>>()
            val failures = mutableListOf<String>()
            var attempted = 0
            for (source in sources) {
                currentCoroutineContext().ensureActive()
                val pending = files.filter { it.filename !in fingerprints }
                if (pending.isEmpty()) break
                attempted++
                try {
                    source.fingerprint(pending).forEach { (filename, version) ->
                        fingerprints[filename] = source to version
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failures +=
                        "${source.provider}：${error.message.orEmpty().take(160).ifBlank { "请求失败" }}"
                }
            }
            if (attempted > 0 && failures.size == attempted && files.isNotEmpty()) {
                throw IllegalStateException("检查更新失败：${failures.joinToString("；")}")
            }
            val fingerprintMs = elapsedMillis(fingerprintStarted)
            val versionStarted = System.nanoTime()
            // 指纹命中的文件先用批量接口一次结算最新版本；只有名称/项目标识匹配才逐项目查询。
            val batch = batchLatest(files, fingerprints, instance, includePrerelease)
            val items =
                coroutineScope {
                    val gate = Semaphore(UPDATE_LOOKUP_CONCURRENCY)
                    files.map { file ->
                            async(Dispatchers.IO) {
                                gate.withPermit {
                                    inspect(
                                        instance,
                                        file,
                                        fingerprints[file.filename],
                                        batch[file.filename],
                                        includePrerelease,
                                    )
                                }
                            }
                        }
                        .awaitAll()
                }
            val versionMs = elapsedMillis(versionStarted)
            store.log(
                "更新检查：扫描 ${files.size} 个文件 $scanMs ms，指纹查询 $fingerprintMs ms，" +
                    "版本查询 $versionMs ms，总计 ${elapsedMillis(startedAt)} ms"
            )
            ModUpdateReport(instanceId, instance.gameVersion, instance.loader, items)
        }

    /**
     * 按「内容源 + 是否接受预发布」分组调用批量接口；不支持的源返回 null，对应文件留待逐项目查询。
     * 分组是为了保持每个文件各自的预发布策略，同时避免为每个项目单独发一次请求。
     */
    private suspend fun batchLatest(
        files: List<LocalModFile>,
        fingerprints: Map<String, Pair<ModUpdateSource, RemoteModVersion>>,
        instance: GameInstance,
        includePrerelease: Boolean,
    ): Map<String, BatchLatest> {
        val matched = files.mapNotNull { file ->
            val hit = fingerprints[file.filename] ?: return@mapNotNull null
            Triple(file, hit.first, hit.second)
        }
        if (matched.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, BatchLatest>()
        val groups =
            matched.groupBy { (_, source, installed) ->
                source.provider to (includePrerelease || isPrereleaseVersion(installed.number))
            }
        for ((key, group) in groups) {
            val (provider, allowPrerelease) = key
            val source = group.first().second
            val latest =
                try {
                    source.latestVersions(group.map { it.first }, instance, allowPrerelease)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    store.log(
                        "$provider 批量版本查询失败，回退到逐项目查询：" +
                            error.message.orEmpty().take(120).ifBlank { "请求失败" },
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
        file: LocalModFile,
        fingerprint: Pair<ModUpdateSource, RemoteModVersion>?,
        batch: BatchLatest?,
        includePrerelease: Boolean,
    ): ModUpdateItem {
        val title =
            file.metadata.title.ifBlank {
                contentBaseFilename(file.filename).removeSuffix(".jar")
            }
        val current =
            file.metadata.entries
                .firstOrNull { it.version.isNotBlank() }
                ?.version
                .orEmpty()
                .trim()
        val base =
            ModUpdateItem(
                filename = file.filename,
                title = title,
                currentVersion = current,
                enabled = file.enabled,
                size = file.size,
                modified = file.modified,
            )
        if (fingerprint != null) {
            val (source, version) = fingerprint
            return resolve(
                instance,
                base,
                source,
                version.project,
                version.number,
                ModMatchSource.Fingerprint,
                false,
                includePrerelease,
                latestKnown = batch?.version,
                batchChecked = batch?.checked == true,
            )
        }
        // 声明中的项目链接：Fabric contact / Forge displayURL / 旧版 mcmod.info url。
        var projectError = ""
        for (ref in file.metadata.projectRefs) {
            val source = sources.firstOrNull { it.provider == ref.provider } ?: continue
            try {
                val project = source.projectBySlug(ref.slug, instance) ?: continue
                return resolve(
                    instance,
                    base,
                    source,
                    project,
                    current,
                    ModMatchSource.ProjectId,
                    false,
                    includePrerelease,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                projectError = error.message.orEmpty().take(120)
            }
        }
        val candidates = mutableListOf<Pair<ModUpdateSource, RemoteModProject>>()
        var searchError = ""
        for (source in sources) {
            currentCoroutineContext().ensureActive()
            try {
                source
                    .search(title, instance)
                    .filter { nameMatches(title, it.title) }
                    .forEach { project -> candidates += source to project }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                searchError = error.message.orEmpty().take(120)
            }
        }
        val distinct = candidates.distinctBy { it.second.id }
        if (distinct.isEmpty()) {
            val reason =
                when {
                    projectError.isNotBlank() -> "读取声明的项目页失败：$projectError"
                    searchError.isNotBlank() -> "名称搜索失败：$searchError"
                    else -> "未找到匹配项目"
                }
            return base.copy(warning = reason)
        }
        if (distinct.size > 1) {
            return base.copy(
                possibleMatch = true,
                warning =
                    "可能匹配到多个项目（" +
                        distinct.take(3).joinToString("、") { it.second.title } +
                        "），未自动选择",
            )
        }
        val (source, project) = distinct.single()
        return resolve(
            instance,
            base,
            source,
            project,
            current,
            ModMatchSource.Name,
            true,
            includePrerelease,
        )
    }

    private suspend fun resolve(
        instance: GameInstance,
        base: ModUpdateItem,
        source: ModUpdateSource,
        project: RemoteModProject,
        currentVersion: String,
        matchSource: ModMatchSource,
        possible: Boolean,
        includePrerelease: Boolean,
        latestKnown: RemoteModVersion? = null,
        batchChecked: Boolean = false,
    ): ModUpdateItem {
        if (currentVersion.isBlank()) {
            return base.copy(
                matchSource = matchSource,
                possibleMatch = possible,
                warning = "无法确认当前版本，未提供更新",
            )
        }
        val pool: List<RemoteModVersion> =
            if (batchChecked) {
                // 批量接口已按游戏版本、加载器与发布类型筛选过，这里只做一次本地复核。
                listOfNotNull(latestKnown?.takeIf { it.number.isNotBlank() && compatible(it, instance) })
            } else {
                val versions =
                    try {
                        source.versions(project, instance)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        return base.copy(
                            matchSource = matchSource,
                            possibleMatch = possible,
                            warning = "读取项目版本失败：${error.message.orEmpty().take(120)}",
                        )
                    }
                val compatible = versions.filter { compatible(it, instance) }
                val allowPrerelease = includePrerelease || isPrereleaseVersion(currentVersion)
                compatible.filter { allowPrerelease || it.versionType.equals("release", true) }
            }
        val latest =
            pool.maxWithOrNull(Comparator { left, right ->
                compareModVersions(left.number, right.number)
            })
        if (latest == null || latest.number.isBlank()) {
            return base.copy(
                status = ModUpdateStatus.Latest,
                matchSource = matchSource,
                possibleMatch = possible,
                warning = "没有适用于当前游戏版本与加载器的其他版本",
            )
        }
        val newer = compareModVersions(latest.number, currentVersion) > 0
        val candidate = if (newer) candidateOf(latest) else null
        return base.copy(
            status = if (newer) ModUpdateStatus.Updatable else ModUpdateStatus.Latest,
            latestVersion = latest.number,
            candidate = candidate,
            matchSource = matchSource,
            possibleMatch = possible,
            applicable = candidate != null && latest.downloadUrl.isNotBlank(),
            warning =
                when {
                    newer && latest.downloadUrl.isBlank() -> "最新版本需要到项目页手动下载"
                    possible -> "按名称匹配，可能不是同一项目"
                    else -> ""
                },
        )
    }

    private fun compatible(version: RemoteModVersion, instance: GameInstance): Boolean =
        instance.gameVersion.isNotBlank() &&
            version.gameVersions.any { it.equals(instance.gameVersion, true) } &&
            version.loaders.any { it.equals(instance.loader.name, true) }

    private fun candidateOf(version: RemoteModVersion): ModUpdateCandidate? {
        val filename =
            runCatching { safeFilename(contentBaseFilename(version.filename.trim())) }
                .getOrNull()
                ?: return null
        if (filename.isBlank() || !filename.endsWith(".jar", true)) return null
        return ModUpdateCandidate(
            projectId = version.project.id,
            projectTitle = version.project.title,
            versionId = version.id,
            versionNumber = version.number,
            filename = filename,
            downloadUrl = version.downloadUrl,
            size = version.size,
            sha512 = version.sha512,
            sha1 = version.sha1,
            gameVersions = version.gameVersions,
            loaders = version.loaders,
            provider = version.project.provider,
        )
    }

    /**
     * 应用一次更新：先下载到 mods 内的临时 JAR 并校验大小/摘要/ZIP 结构，再把旧文件移到回收站。 任何一步失败都不动旧文件；旧文件的
     * `.disabled` 状态沿用新文件名。
     */
    suspend fun apply(instanceId: String, item: ModUpdateItem, context: JobContext? = null) =
        withContext(Dispatchers.IO) {
            require(
                item.status == ModUpdateStatus.Updatable && item.candidate != null && item.applicable
            ) {
                "没有可应用的更新"
            }
            val candidate = requireNotNull(item.candidate)
            val instance = store.instance(instanceId)
            require(candidate.gameVersions.any { it.equals(instance.gameVersion, true) }) {
                "更新文件不支持当前游戏版本"
            }
            require(
                instance.loader != LoaderType.Vanilla &&
                    candidate.loaders.any { it.equals(instance.loader.name, true) }
            ) {
                "更新文件不支持当前加载器"
            }
            val mods = store.instancePath(instanceId).resolve("mods")
            val old = safePath(mods, safeFilename(item.filename))
            require(old.isRegularFile() && !Files.isSymbolicLink(old)) {
                "模组文件已不存在，请重新检查更新"
            }
            require(
                old.fileSize() == item.size &&
                    old.getLastModifiedTime().toMillis() == item.modified
            ) {
                "模组文件已改变，请重新检查更新"
            }
            val disabled = item.filename.endsWith(".disabled", true)
            val newBase = safeFilename(contentBaseFilename(candidate.filename.trim()))
            require(newBase.endsWith(".jar", true)) { "更新文件不是 JAR" }
            val newName = newBase + if (disabled) ".disabled" else ""
            val target = safePath(mods, newName)
            require(!target.exists() || target == old) {
                "目标文件已存在，请先处理重复模组：$newName"
            }
            val temporary = safePath(mods, ".molifox-update-${UUID.randomUUID()}.jar")
            context?.progress(0f, "下载 ${newBase}")
            try {
                downloader(candidate.toRemoteVersion(), temporary, context)
                context?.checkCancelled()
                require(temporary.isRegularFile() && temporary.fileSize() > 0) { "更新文件为空" }
                if (candidate.size > 0)
                    require(temporary.fileSize() == candidate.size) { "更新文件大小不符" }
                if (candidate.sha512.isNotBlank())
                    require(digest(temporary, "SHA-512").equals(candidate.sha512, true)) {
                        "更新文件哈希不符"
                    }
                else if (candidate.sha1.isNotBlank())
                    require(digest(temporary, "SHA-1").equals(candidate.sha1, true)) {
                        "更新文件哈希不符"
                    }
                ZipFile(temporary.toFile()).use { zip ->
                    require(zip.entries().hasMoreElements()) { "更新文件不是有效的 JAR" }
                }
                context?.checkCancelled()
                val backup = safePath(mods, ".molifox-old-${UUID.randomUUID()}")
                // 旧文件先移到同目录暂存名；新文件落位失败时原样搬回。
                Files.move(old, backup)
                try {
                    Files.move(temporary, target)
                } catch (failure: Exception) {
                    val restored =
                        runCatching {
                            Files.move(backup, old, StandardCopyOption.REPLACE_EXISTING)
                        }
                    if (restored.isFailure) {
                        store.log(
                            "更新模组失败且原文件未能复位，备份保留在 ${backup.toAbsolutePath()}",
                            true,
                        )
                        failure.addSuppressed(restored.exceptionOrNull()!!)
                    }
                    throw failure
                }
                try {
                    store.platform.moveToTrash(backup)
                } catch (error: Exception) {
                    store.log(
                        "旧模组未能移入回收站，保留在 ${backup.toAbsolutePath()}：${error.message}",
                        true,
                    )
                }
                store.log("更新模组：${item.title} ${item.currentVersion} → ${candidate.versionNumber}")
                context?.progress(1f, "更新完成")
            } finally {
                temporary.deleteIfExists()
            }
        }
}

private fun ModUpdateCandidate.toRemoteVersion(): RemoteModVersion =
    RemoteModVersion(
        RemoteModProject(projectId, "", projectTitle, provider),
        versionId,
        versionNumber,
        versionNumber,
        gameVersions,
        loaders,
        "release",
        downloadUrl,
        filename,
        size,
        sha512,
        sha1,
    )

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
        for (batch in files.map { it.sha512 }.filter { it.isNotBlank() }.distinct().chunked(100)) {
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
                    if (instance.gameVersion.isNotBlank())
                        add("game_versions", gson.toJsonTree(listOf(instance.gameVersion)))
                    if (instance.loader != LoaderType.Vanilla)
                        add("loaders", gson.toJsonTree(listOf(instance.loader.name.lowercase())))
                    add(
                        "version_types",
                        gson.toJsonTree(
                            if (allowPrerelease) listOf("release", "beta", "alpha")
                            else listOf("release")
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

    override suspend fun projectBySlug(slug: String, instance: GameInstance): RemoteModProject? {
        val info = network.json("$api/project/${encoded(slug)}").asJsonObject
        if (info.string("project_type") != "mod") return null
        return RemoteModProject(
            info.string("id"),
            info.string("slug"),
            info.string("title"),
            provider,
        )
    }

    override suspend fun search(name: String, instance: GameInstance): List<RemoteModProject> {
        val facets = mutableListOf(listOf("project_type:mod"))
        if (instance.gameVersion.isNotBlank())
            facets += listOf("versions:${instance.gameVersion}")
        if (instance.loader != LoaderType.Vanilla)
            facets += listOf("categories:${instance.loader.name.lowercase()}")
        val response =
            network
                .json(
                    "$api/search?query=${encoded(name)}&limit=8&facets=${encoded(gson.toJson(facets))}"
                )
                .asJsonObject
        return response.array("hits").mapNotNull { element ->
            val hit = element.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
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
        val filters = buildString {
            if (instance.gameVersion.isNotBlank())
                append(
                    "&game_versions=${encoded(gson.toJson(listOf(instance.gameVersion)))}"
                )
            if (instance.loader != LoaderType.Vanilla)
                append("&loaders=${encoded(gson.toJson(listOf(instance.loader.name.lowercase())))}")
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
        return files.mapNotNull { file ->
            val info = matched[file.path] ?: return@mapNotNull null
            val modId = info.string("modId")
            if (modId.isBlank() || info.string("id").isBlank()) return@mapNotNull null
            val project = RemoteModProject("curseforge:$modId", "", "", provider)
            val version = remoteVersion(info, project) ?: return@mapNotNull null
            file.filename to version
        }.toMap()
    }

    override suspend fun projectBySlug(slug: String, instance: GameInstance): RemoteModProject? =
        curseForge
            .search(slug, "mod", instance, 0)
            .hits
            .firstOrNull { it.slug.equals(slug, true) }
            ?.let { RemoteModProject(it.id, it.slug, it.title, provider) }

    override suspend fun search(name: String, instance: GameInstance): List<RemoteModProject> =
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

internal fun remoteVersion(info: JsonObject, project: RemoteModProject): RemoteModVersion? {
    val files =
        info.array("files").mapNotNull {
            it.takeIf(JsonElement::isJsonObject)?.asJsonObject
        }
    val file = files.firstOrNull { it.boolean("primary") } ?: files.firstOrNull() ?: return null
    val rawNumber = info.string("version_number").ifBlank { info.string("name") }
    // CurseForge 的 displayName 常带项目名与游戏版本，例如「Fabric API 0.92.3+1.20.1」。
    val number =
        if (project.provider == "curseforge") normalizedRemoteVersionNumber(rawNumber)
        else rawNumber
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

private val remoteVersionToken =
    Regex("""(?i)(?<![\w.])(v?\d+(?:\.\d+)+(?:[-+][0-9a-z][0-9a-z.+_-]*)?)(?![\w.])""")

/**
 * CurseForge 的版本显示名常把项目名和游戏版本拼在一起（「Fabric API 0.92.3+1.20.1」、
 * 「JEI 15.2.0.27 for 1.20.1」）。取第一段完整版本号用于比较与展示，避免把项目名当成版本段。
 */
internal fun normalizedRemoteVersionNumber(raw: String): String {
    val text = raw.trim()
    if (text.isEmpty()) return text
    return remoteVersionToken.find(text)?.groupValues?.getOrNull(1) ?: text
}

/** 语义化版本比较：数值段按整数比较（1.2 < 1.10），预发布后缀低于正式版，构建元数据忽略。 */
internal fun compareModVersions(left: String, right: String): Int {
    val a = parseVersion(left)
    val b = parseVersion(right)
    val core = compareCore(a.core, b.core)
    if (core != 0) return core
    if (a.pre.isEmpty() && b.pre.isEmpty()) return 0
    if (a.pre.isEmpty()) return 1
    if (b.pre.isEmpty()) return -1
    return comparePre(a.pre, b.pre)
}

internal fun isPrereleaseVersion(value: String): Boolean {
    val core = value.trim().substringBefore('+')
    return core.contains('-') && core.substringAfter('-').isNotBlank()
}

/** 常见 Minecraft 版本约束：精确版本、`1.20.x`、`>=1.20 <1.21`、Maven 区间 `[1.20,1.21)`。 */
internal fun minecraftConstraintMatches(constraint: String, version: String): Boolean {
    val text = constraint.trim()
    if (text.isEmpty() || text == "*") return true
    if (text.contains("||")) return text.split("||").any { minecraftConstraintMatches(it, version) }
    if ((text.startsWith("[") || text.startsWith("(")) && text.length > 2) {
        val close = text.indexOfAny(charArrayOf(']', ')'))
        if (close > 0) {
            val parts = text.substring(1, close).split(',')
            if (parts.size == 1) {
                return parts.first().trim().let { it.isEmpty() || sameVersion(version, it) }
            }
            val lower = parts.first().trim()
            val upper = parts.getOrNull(1).orEmpty().trim()
            if (lower.isNotEmpty()) {
                val comparison = compareModVersions(version, lower)
                if (comparison < 0 || (comparison == 0 && text.startsWith("("))) return false
            }
            if (upper.isNotEmpty()) {
                val comparison = compareModVersions(version, upper)
                if (comparison > 0 || (comparison == 0 && text[close] == ')')) return false
            }
            return true
        }
    }
    val clauses = text.split(Regex("[\\s,]+")).filter { it.isNotBlank() }
    if (clauses.isEmpty()) return true
    return clauses.all { clause ->
        when {
            clause.startsWith(">=") -> compareModVersions(version, clause.drop(2)) >= 0
            clause.startsWith("<=") -> compareModVersions(version, clause.drop(2)) <= 0
            clause.startsWith(">") -> compareModVersions(version, clause.drop(1)) > 0
            clause.startsWith("<") -> compareModVersions(version, clause.drop(1)) < 0
            clause.startsWith("=") -> matchesVersionPrefix(version, clause.drop(1))
            clause.startsWith("~") || clause.startsWith("^") ->
                matchesVersionPrefix(version, clause.drop(1))
            else -> matchesVersionPrefix(version, clause)
        }
    }
}

private fun matchesVersionPrefix(version: String, expected: String): Boolean {
    val raw = expected.trim()
    if (raw.isEmpty() || raw == "*") return true
    val prefix =
        raw.removeSuffix(".*")
            .removeSuffix(".x")
            .removeSuffix(".X")
            .removeSuffix("+")
            .trim()
    if (prefix.isEmpty()) return true
    val value = version.trim()
    return sameVersion(value, prefix) || value.startsWith("$prefix.")
}

private fun sameVersion(left: String, right: String) = compareModVersions(left, right) == 0

private data class ParsedVersion(val core: List<String>, val pre: List<String>)

private fun parseVersion(raw: String): ParsedVersion {
    var text = raw.trim().removePrefix("v").removePrefix("V").substringBefore('+')
    if (text.isEmpty()) return ParsedVersion(emptyList(), emptyList())
    val dash = text.indexOf('-')
    val coreText = if (dash >= 0) text.substring(0, dash) else text
    val preText = if (dash >= 0) text.substring(dash + 1) else ""
    return ParsedVersion(
        coreText.split('.', '_').map { it.trim() }.filter { it.isNotEmpty() },
        preText.split('.', '-', '_').map { it.trim() }.filter { it.isNotEmpty() },
    )
}

private fun compareCore(left: List<String>, right: List<String>): Int {
    for (index in 0 until maxOf(left.size, right.size)) {
        val a = left.getOrNull(index) ?: "0"
        val b = right.getOrNull(index) ?: "0"
        val numericA = a.toLongOrNull()
        val numericB = b.toLongOrNull()
        val comparison =
            when {
                numericA != null && numericB != null -> numericA.compareTo(numericB)
                numericA != null -> 1
                numericB != null -> -1
                else -> a.compareTo(b, ignoreCase = true)
            }
        if (comparison != 0) return comparison
    }
    return 0
}

private val prereleaseRank =
    mapOf(
        "snapshot" to 0,
        "dev" to 0,
        "alpha" to 1,
        "a" to 1,
        "beta" to 2,
        "b" to 2,
        "pre" to 3,
        "preview" to 3,
        "rc" to 4,
        "release" to 5,
    )

private fun comparePre(left: List<String>, right: List<String>): Int {
    for (index in 0 until maxOf(left.size, right.size)) {
        if (index >= left.size) return -1
        if (index >= right.size) return 1
        val a = left[index]
        val b = right[index]
        val numericA = a.toLongOrNull()
        val numericB = b.toLongOrNull()
        val comparison =
            when {
                numericA != null && numericB != null -> numericA.compareTo(numericB)
                // 语义化规则：数字标识低于字母标识（1.0.0-alpha < 1.0.0-1）。
                numericA != null -> -1
                numericB != null -> 1
                else -> {
                    val rankA = prereleaseRank[a.lowercase()]
                    val rankB = prereleaseRank[b.lowercase()]
                    when {
                        rankA != null && rankB != null -> rankA.compareTo(rankB)
                        rankA != null -> -1
                        rankB != null -> 1
                        else -> a.compareTo(b, ignoreCase = true)
                    }
                }
            }
        if (comparison != 0) return comparison
    }
    return 0
}

private fun normalizeName(value: String) =
    value.lowercase().filter { it.isLetterOrDigit() }

internal fun nameMatches(query: String, candidate: String): Boolean {
    val left = normalizeName(query)
    val right = normalizeName(candidate)
    if (left.isEmpty() || right.isEmpty()) return false
    return left == right || (left.length >= 4 && (right.startsWith(left) || left.startsWith(right)))
}