// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责已安装资源包与光影的更新检查（指纹优先，其次按名称匹配）。

package dev.molifox.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.Dispatchers
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
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

private const val FAILURE_DETAIL_MAX_CHARS = 120
private const val FINGERPRINT_LOG_MAX_CHARS = 160
private const val POSSIBLE_MATCH_TITLE_LIMIT = 3
private const val PROJECT_KIND_BATCH_SIZE = 50
private const val HASH_BATCH_SIZE = 100

/** 实例内容目录：资源包与光影共用同一套更新检查，只是内容源的类型不同。 */
enum class PackFolder(
    val directory: String,
    val kind: String,
    private val titleKey: String,
) {
    ResourcePacks("resourcepacks", "resourcepack", "core.packFolder.resourcePacks"),
    ShaderPacks("shaderpacks", "shader", "core.packFolder.shaderPacks"),
    ;

    /** 显示名在读取时翻译，切换语言后重新取值。 */
    val title: String
        get() = I18n.t(titleKey)

    companion object {
        fun of(directory: String): PackFolder? = entries.find { it.directory == directory }
    }
}

/** 与模组一致的三态：有更新、已是最新、无法确认当前版本。 */
enum class PackUpdateStatus(
    private val titleKey: String,
) {
    Updatable("core.packUpdateStatus.updatable"),
    Latest("core.packUpdateStatus.latest"),
    Unknown("core.packUpdateStatus.unknown"),
    ;

    /** 显示名在读取时翻译，切换语言后重新取值。 */
    val title: String
        get() = I18n.t(titleKey)
}

/** 识别依据：文件指纹最可靠，名称搜索只作兜底。 */
enum class PackMatchSource(
    private val titleKey: String,
) {
    Fingerprint("core.packMatchSource.fingerprint"),
    Name("core.packMatchSource.name"),
    ;

    /** 显示名在读取时翻译，切换语言后重新取值。 */
    val title: String
        get() = I18n.t(titleKey)
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
    val size: Long,
    val sha512: String,
    val sha1: String,
) {
    /** 下载前的最低要求：地址、大小与至少一种校验值齐全，否则无法安全校验下载结果。 */
    val downloadable: Boolean
        get() =
            downloadUrl.isNotBlank() &&
                size > 0 &&
                (sha512.isNotBlank() || sha1.isNotBlank())
}

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
    /** 是否可以由用户显式应用：缺少下载地址或校验信息时为 false。 */
    val applicable: Boolean = false,
    /** 检查时记录的大小与修改时间，替换前用来确认文件没有被其他程序改动。 */
    val size: Long = 0,
    val modified: Long = 0,
    val warning: String = "",
) {
    /** 批量更新只能应用已确认、可下载且不是名称推测匹配的项目。 */
    val batchApplicable: Boolean
        get() = status == PackUpdateStatus.Updatable && applicable && !possibleMatch
}

data class PackUpdateReport(
    val instanceId: String,
    val gameVersion: String,
    val items: List<PackUpdateItem>,
) {
    fun items(folder: PackFolder): List<PackUpdateItem> = items.filter { it.folder == folder }

    fun updatable(folder: PackFolder): Int = items(folder).count { it.status == PackUpdateStatus.Updatable }

    fun latest(folder: PackFolder): Int = items(folder).count { it.status == PackUpdateStatus.Latest }

    fun unknown(folder: PackFolder): Int = items(folder).count { it.status == PackUpdateStatus.Unknown }

    /** 该目录里「没找到更新」的数量：已是最新 + 无法确认。 */
    fun unchanged(folder: PackFolder): Int = latest(folder) + unknown(folder)

    fun batchItems(
        folder: PackFolder,
        filenames: Set<String>,
    ): List<PackUpdateItem> = items(folder).filter { it.filename in filenames && it.batchApplicable }
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
        /** 应用更新时的下载器；只做检查的测试可以不提供。 */
        private val downloader: PackUpdateDownloader? = null,
    ) {
        private val local = PackUpdateLocalIndex()

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
                    val folderScan = scanFolder(instance, root, folder, includePrerelease, failures)
                    items += folderScan.items
                    attempted += folderScan.attempted
                    scanned += folderScan.scanned
                    scanMs += folderScan.scanMs
                    fingerprintMs += folderScan.fingerprintMs
                }
                val versionMs = elapsedMillis(versionStarted) - scanMs - fingerprintMs
                if (attempted > 0 && failures.size == attempted && scanned > 0) {
                    throw IllegalStateException(
                        I18n.t("packsB2.packUpdates.checkFailed", failures.joinToString("；")),
                    )
                }
                store.log(
                    I18n.t(
                        "packsB2.packUpdates.scanSummary",
                        scanned,
                        scanMs,
                        fingerprintMs,
                        versionMs,
                        elapsedMillis(startedAt),
                    ),
                )
                PackUpdateReport(instanceId, instance.gameVersion, items)
            }

        /**
         * 应用一条资源包/光影更新。
         *
         * 与模组更新一致：先下载到同目录临时文件并校验大小、哈希与 ZIP 结构，全部通过后才替换旧文件； 替换失败会恢复旧文件，任务取消时也保留已完成的部分。
         */
        suspend fun apply(
            instanceId: String,
            item: PackUpdateItem,
            context: JobContext? = null,
        ) {
            val active = checkNotNull(downloader) { I18n.t("packsB2.packUpdates.downloaderMissing") }
            PackUpdateInstaller(store, active).apply(instanceId, item, context)
        }

        /** 扫描一个内容目录：先取指纹，再批量结算最新版本，最后按文件并发确认。 */
        private suspend fun scanFolder(
            instance: GameInstance,
            root: Path,
            folder: PackFolder,
            includePrerelease: Boolean,
            failures: MutableList<String>,
        ): FolderScan {
            currentCoroutineContext().ensureActive()
            val scanStarted = System.nanoTime()
            val files = readLocalPacks(root.resolve(folder.directory))
            val scanMs = elapsedMillis(scanStarted)
            if (files.isEmpty()) return FolderScan(emptyList(), 0, 0, scanMs, 0L)
            val fingerprints = mutableMapOf<String, Pair<PackUpdateSource, RemoteModVersion>>()
            // 某个源失败只影响它自己；两个源都失败时整次检查报错，而不是当成「没有更新」。
            val fingerprintStarted = System.nanoTime()
            val attempted = collectFingerprints(files, folder, fingerprints, failures)
            val fingerprintMs = elapsedMillis(fingerprintStarted)
            // 指纹命中的包先用批量接口结算最新版本，只有名称匹配才逐项目查询。
            val batch = batchLatest(files, fingerprints, folder, instance, includePrerelease)
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
            return FolderScan(inspected, attempted, files.size, scanMs, fingerprintMs)
        }

        /** 逐个内容源查询文件指纹；返回实际发起的查询次数，供失败判定使用。 */
        private suspend fun collectFingerprints(
            files: List<LocalPackFile>,
            folder: PackFolder,
            fingerprints: MutableMap<String, Pair<PackUpdateSource, RemoteModVersion>>,
            failures: MutableList<String>,
        ): Int {
            var attempted = 0
            for (source in sources) {
                currentCoroutineContext().ensureActive()
                val pending = files.filter { it.filename !in fingerprints }
                if (pending.isEmpty()) break
                attempted++
                packSourceAttempt {
                    source.fingerprint(pending, folder).forEach { (filename, version) ->
                        fingerprints[filename] = source to version
                    }
                }.onFailure { error ->
                    failures += packSourceFailure(source.provider, error)
                }
            }
            return attempted
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
                    latestForGroup(provider, group, folder, instance, allowPrerelease)
                        ?: continue
                group.forEach { (file, _, _) ->
                    result[file.filename] = BatchLatest(true, latest[file.filename])
                }
            }
            return result
        }

        /** 查询一个「内容源 + 发布类型」分组的最新版本；失败时该组回退到逐项目查询。 */
        private suspend fun latestForGroup(
            provider: String,
            group: List<Triple<LocalPackFile, PackUpdateSource, RemoteModVersion>>,
            folder: PackFolder,
            instance: GameInstance,
            allowPrerelease: Boolean,
        ): Map<String, RemoteModVersion>? =
            packSourceAttempt {
                group.first().second.latestVersions(
                    group.map { it.first },
                    folder,
                    instance,
                    allowPrerelease,
                )
            }.getOrElse { error ->
                logBatchFailure(store, provider, error)
                null
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
                    size = file.size,
                    modified = file.modified,
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
            return resolveByName(instance, folder, file, base, includePrerelease)
        }

        /** 名称搜索匹配：没有候选记为「无法确认」，多个候选提示可能不是同一项目。 */
        private suspend fun resolveByName(
            instance: GameInstance,
            folder: PackFolder,
            file: LocalPackFile,
            base: PackUpdateItem,
            includePrerelease: Boolean,
        ): PackUpdateItem {
            // 名称搜索：去掉扩展名，把 . - _ 换成空格，只保留较长的名字片段。
            val query = packSearchName(file.filename)
            val found = packSearchCandidates(sources, query, folder, instance)
            val distinct = found.candidates.distinctBy { it.second.id }
            return when {
                distinct.isEmpty() -> unmatchedPack(store, base, file.filename, found.error)
                distinct.size > 1 ->
                    base.copy(
                        status = PackUpdateStatus.Unknown,
                        possibleMatch = true,
                        warning =
                            I18n.t(
                                "packsB2.packUpdates.ambiguousProject",
                                distinct
                                    .take(POSSIBLE_MATCH_TITLE_LIMIT)
                                    .joinToString("、") { it.second.title },
                            ),
                    )
                else -> {
                    val (source, project) = distinct.single()
                    resolve(
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
            }
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
            val loaded =
                if (batchChecked) null else loadPackVersions(source, project, folder, instance)
            val failure = loaded?.exceptionOrNull()
            if (failure != null) {
                return base.copy(
                    matchSource = matchSource,
                    possibleMatch = possible,
                    warning = I18n.t("packsB2.packUpdates.readVersionsFailed", packFailureDetail(failure)),
                )
            }
            val versions = loaded?.getOrNull().orEmpty()
            val pool =
                candidateVersions(
                    versions,
                    folder,
                    instance,
                    currentVersion,
                    includePrerelease,
                    latestKnown,
                    batchChecked,
                )
            val latest =
                pool.maxWithOrNull(
                    Comparator { left, right ->
                        compareModVersions(left.number, right.number)
                    },
                )
            // 名称匹配时，项目版本里与本地文件同名的那个就是当前安装的版本。
            val current = currentVersion.ifBlank { sameNamePackVersion(versions, file) }
            return packResolutionItem(base, matchSource, possible, current, latest)
        }

        /** 批量接口与单个请求两条路径的版本池筛选，条件与顺序与原先一致。 */
        private fun candidateVersions(
            versions: List<RemoteModVersion>,
            folder: PackFolder,
            instance: GameInstance,
            currentVersion: String,
            includePrerelease: Boolean,
            latestKnown: RemoteModVersion?,
            batchChecked: Boolean,
        ): List<RemoteModVersion> =
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
                    .map { local.of(it) }
            }.getOrElse { emptyList() }
        }
    }

/** 单个内容目录的扫描结果；计数与耗时用于汇总日志和失败判定。 */
private data class FolderScan(
    val items: List<PackUpdateItem>,
    val attempted: Int,
    val scanned: Int,
    val scanMs: Long,
    val fingerprintMs: Long,
)

/** 名称搜索的候选与失败原因；失败不阻断流程，只影响界面提示。 */
private data class PackSearchResult(
    val candidates: List<Pair<PackUpdateSource, RemoteModProject>>,
    val error: String,
)

/** 在各内容源里按名称搜索候选；某个源失败只记录原因，不影响其它源。 */
private suspend fun packSearchCandidates(
    sources: List<PackUpdateSource>,
    query: String,
    folder: PackFolder,
    instance: GameInstance,
): PackSearchResult {
    val candidates = mutableListOf<Pair<PackUpdateSource, RemoteModProject>>()
    var error = ""
    for (source in sources) {
        currentCoroutineContext().ensureActive()
        val found = packSourceAttempt { source.search(query, folder, instance) }
        val failure = found.exceptionOrNull()
        if (failure != null) {
            error = packFailureDetail(failure)
            continue
        }
        val matched =
            found.getOrNull().orEmpty().filter {
                nameMatches(query, it.title) || nameMatches(query, it.slug)
            }
        matched.forEach { project -> candidates += source to project }
    }
    return PackSearchResult(candidates, error)
}

/** 名称搜索无结果：失败原因只写技术日志，界面统一为「无法确认」。 */
private fun unmatchedPack(
    store: LauncherStore,
    base: PackUpdateItem,
    filename: String,
    error: String,
): PackUpdateItem {
    if (error.isNotBlank()) {
        // 搜索失败只留在技术日志里；界面上这是「无法确认」，不能读成检查失败。
        store.log(I18n.t("packsB2.packUpdates.searchNoResult", filename, error))
    }
    return base.copy(
        status = PackUpdateStatus.Unknown,
        warning = I18n.t("packsB2.packUpdates.unmatched"),
    )
}

/** 依据当前版本与最新版本决定状态、候选与提示。 */
private fun packResolutionItem(
    base: PackUpdateItem,
    matchSource: PackMatchSource,
    possible: Boolean,
    current: String,
    latest: RemoteModVersion?,
): PackUpdateItem {
    if (current.isBlank() || latest == null || latest.number.isBlank()) {
        return base.copy(
            status = PackUpdateStatus.Unknown,
            matchSource = matchSource,
            possibleMatch = possible,
            warning =
                if (latest == null) {
                    I18n.t("packsB2.packUpdates.noVersionForGame")
                } else {
                    I18n.t("packsB2.packUpdates.unknownCurrentVersion")
                },
        )
    }
    val newer = compareModVersions(latest.number, current) > 0
    val candidate = if (newer) packUpdateCandidate(latest) else null
    val warning =
        when {
            !newer -> if (possible) I18n.t("packsB2.packUpdates.nameMatchedOnly") else ""
            candidate == null -> I18n.t("packsB2.packUpdates.latestNoZip")
            !candidate.downloadable -> I18n.t("packsB2.packUpdates.latestNoDownload")
            possible -> I18n.t("packsB2.packUpdates.nameMatchedOnly")
            else -> ""
        }
    return base.copy(
        status = if (newer) PackUpdateStatus.Updatable else PackUpdateStatus.Latest,
        currentVersion = current,
        latestVersion = latest.number,
        candidate = candidate,
        matchSource = matchSource,
        possibleMatch = possible,
        applicable = candidate?.downloadable == true,
        warning = warning,
    )
}

private fun packUpdateCandidate(version: RemoteModVersion): PackUpdateCandidate? {
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
        size = version.size,
        sha512 = version.sha512,
        sha1 = version.sha1,
    )
}

/** 错误摘要统一截断，避免把整段服务端返回写进界面提示。 */
private fun packFailureDetail(error: Throwable): String = error.message.orEmpty().take(FAILURE_DETAIL_MAX_CHARS)

/** 指纹接口失败只影响该内容源；日志保留截断后的错误摘要。 */
private fun packSourceFailure(
    provider: String,
    error: Throwable,
): String {
    val detail =
        error.message
            .orEmpty()
            .take(FINGERPRINT_LOG_MAX_CHARS)
            .ifBlank { I18n.t("packsB2.packUpdates.requestFailed") }
    return "$provider：$detail"
}

/** 批量接口失败只影响该分组，回退到逐项目查询并留下技术日志。 */
private fun logBatchFailure(
    store: LauncherStore,
    provider: String,
    error: Throwable,
) {
    store.log(
        I18n.t(
            "packsB2.packUpdates.batchFailed",
            provider,
            packFailureDetail(error).ifBlank { I18n.t("packsB2.packUpdates.requestFailed") },
        ),
        true,
    )
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
): List<PackUpdateSource> = listOf(ModrinthPackUpdateSource(network, api), CurseForgePackUpdateSource(curseForge))

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
            for (batch in missing.chunked(PROJECT_KIND_BATCH_SIZE)) {
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
            .chunked(HASH_BATCH_SIZE)) {
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
        val hashes = files.map { it.filename to it.sha512 }
        return network.latestModrinthFiles(
            api,
            hashes,
            if (folder == PackFolder.ResourcePacks) instance.gameVersion else "",
            "",
            allowPrerelease,
        )
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
        return modrinthSearchProjects(network, api, name, facets, folder.kind, provider)
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
            val info = matched[file.path]
            val modId = info?.string("modId").orEmpty()
            val version =
                if (info == null || modId.isBlank() || info.string("id").isBlank()) {
                    null
                } else {
                    remoteVersion(info, RemoteModProject("curseforge:$modId", "", "", provider))
                }
            if (version != null) result[file.filename] = version
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
    ): List<RemoteModVersion> = curseForge.available(project.id).mapNotNull { remoteVersion(it, project) }
}

/** 资源包/光影本地指纹的短期缓存；与更新检查服务分开，避免服务类继续膨胀。 */
private class PackUpdateLocalIndex {
    private data class Cached(
        val size: Long,
        val modified: Long,
        val sha512: String,
    )

    private val cache =
        object : LinkedHashMap<Path, Cached>(64, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Path, Cached>?) = size > 512
        }

    @Synchronized
    fun of(path: Path): LocalPackFile {
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
}
