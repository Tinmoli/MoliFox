// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 集中模组识别和更新查询，让文件替换事务由独立职责处理。

package dev.molifox.core

import com.google.gson.JsonParseException
import dev.molifox.core.i18n.I18n
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
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

private val modFilePattern = Regex("(?i).*\\.jar(?:\\.disabled)?")

private const val FAILURE_MESSAGE_MAX_LENGTH = 160
private const val ERROR_MESSAGE_MAX_LENGTH = 120
private const val MAX_CANDIDATE_TITLES = 3

/** 优先通过指纹或项目标识匹配；名称搜索只作备选，多候选时不提供更新。 */
class ModUpdateService
    internal constructor(
        private val store: LauncherStore,
        metadataReader: ModMetadataReader,
        private val sources: List<ModUpdateSource>,
        downloader: ModUpdateDownloader,
        hasher: ModFileHasher = { path, algorithm -> digest(path, algorithm) },
    ) {
        private val fileReader = LocalModFileReader(metadataReader, hasher)
        private val installer = ModUpdateInstaller(store, downloader)
        private val batchLookup = ModUpdateBatchLookup(store)

        suspend fun check(
            instanceId: String,
            includePrerelease: Boolean = false,
        ): ModUpdateReport =
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
                val files = readModFiles(directory)
                val scanMs = elapsedMillis(scanStarted)
                // 指纹：每个源一次批量请求；某个源失败只影响它自己，全部失败则报错而不是「没有更新」。
                val fingerprintStarted = System.nanoTime()
                val fingerprints = queryFingerprints(files)
                val fingerprintMs = elapsedMillis(fingerprintStarted)
                val versionStarted = System.nanoTime()
                // 指纹命中的文件先用批量接口一次结算最新版本；只有名称/项目标识匹配才逐项目查询。
                val batch =
                    batchLookup.latestVersions(files, fingerprints, instance, includePrerelease)
                val items = inspectAll(files, fingerprints, batch, instance, includePrerelease)
                val versionMs = elapsedMillis(versionStarted)
                store.log(
                    I18n.t(
                        "packsC.updateCheck.scanned",
                        files.size,
                        scanMs,
                        fingerprintMs,
                        versionMs,
                        elapsedMillis(startedAt),
                    ),
                )
                ModUpdateReport(instanceId, instance.gameVersion, instance.loader, items)
            }

        /** 扫描实例的 mods 目录；跳过符号链接、启动器临时文件与非 jar 文件。 */
        private suspend fun readModFiles(directory: Path): List<LocalModFile> =
            directory
                .listDirectoryEntries()
                .filter {
                    it.isRegularFile() &&
                        !Files.isSymbolicLink(it) &&
                        !it.name.startsWith(".molifox-") &&
                        modFilePattern.matches(it.name)
                }.sortedBy { it.name.lowercase() }
                .map {
                    currentCoroutineContext().ensureActive()
                    fileReader.read(it)
                }

        /** 批量指纹查询；单个源失败只记录原因，全部源都失败时抛出，避免把「查不到」当成「没有更新」。 */
        private suspend fun queryFingerprints(
            files: List<LocalModFile>,
        ): Map<String, Pair<ModUpdateSource, RemoteModVersion>> {
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
                } catch (error: IOException) {
                    failures += "${source.provider}：${failureMessage(error)}"
                } catch (error: IllegalStateException) {
                    failures += "${source.provider}：${failureMessage(error)}"
                } catch (error: JsonParseException) {
                    failures += "${source.provider}：${failureMessage(error)}"
                }
            }
            if (attempted > 0 && failures.size == attempted && files.isNotEmpty()) {
                throw IllegalStateException(I18n.t("packsB2.packUpdates.checkFailed", failures.joinToString("；")))
            }
            return fingerprints
        }

        private suspend fun inspectAll(
            files: List<LocalModFile>,
            fingerprints: Map<String, Pair<ModUpdateSource, RemoteModVersion>>,
            batch: Map<String, BatchLatest>,
            instance: GameInstance,
            includePrerelease: Boolean,
        ): List<ModUpdateItem> =
            coroutineScope {
                val gate = Semaphore(UPDATE_LOOKUP_CONCURRENCY)
                files
                    .map { file ->
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
                    }.awaitAll()
            }

        private suspend fun inspect(
            instance: GameInstance,
            file: LocalModFile,
            fingerprint: Pair<ModUpdateSource, RemoteModVersion>?,
            batch: BatchLatest?,
            includePrerelease: Boolean,
        ): ModUpdateItem {
            val base = baseItem(file)
            // 哈希指纹优先：命中即不再查询声明的项目链接。
            val fingerprintMatch =
                resolveFingerprint(instance, base, fingerprint, batch, includePrerelease)
            // 声明中的项目链接：Fabric contact / Forge displayURL / 旧版 mcmod.info url。
            var projectError = ""
            val declaredMatch =
                fingerprintMatch
                    ?: declaredMatchOf(instance, base, file, includePrerelease) {
                        projectError = it
                    }
            if (declaredMatch != null) return declaredMatch
            var searchError = ""
            val distinct =
                searchCandidates(sources, base.title, instance) { searchError = it }
                    .distinctBy { it.second.id }
            return inspectOutcome(
                instance,
                base,
                distinct,
                projectError,
                searchError,
                includePrerelease,
            )
        }

        /** 搜索结果定案：无候选时说明原因，多候选只提示不自动选择，唯一候选才逐项目查询版本。 */
        private suspend fun inspectOutcome(
            instance: GameInstance,
            base: ModUpdateItem,
            distinct: List<Pair<ModUpdateSource, RemoteModProject>>,
            projectError: String,
            searchError: String,
            includePrerelease: Boolean,
        ): ModUpdateItem =
            when {
                distinct.isEmpty() ->
                    base.copy(warning = unmatchedReason(projectError, searchError))
                distinct.size > 1 ->
                    base.copy(
                        possibleMatch = true,
                        warning = multipleMatchWarning(distinct),
                    )
                else -> {
                    val (source, project) = distinct.single()
                    resolve(
                        instance,
                        base,
                        source,
                        project,
                        base.currentVersion,
                        ModMatchSource.Name,
                        true,
                        includePrerelease,
                    )
                }
            }

        private suspend fun resolveFingerprint(
            instance: GameInstance,
            base: ModUpdateItem,
            fingerprint: Pair<ModUpdateSource, RemoteModVersion>?,
            batch: BatchLatest?,
            includePrerelease: Boolean,
        ): ModUpdateItem? {
            val (source, version) = fingerprint ?: return null
            return resolve(
                instance,
                base,
                source,
                version.project,
                version.number,
                ModMatchSource.Fingerprint,
                false,
                includePrerelease,
                batch,
            )
        }

        /** 逐个候选项目链接解析；记录最后一次读取失败的原因供提示使用。 */
        private suspend fun declaredMatchOf(
            instance: GameInstance,
            base: ModUpdateItem,
            file: LocalModFile,
            includePrerelease: Boolean,
            onError: (String) -> Unit,
        ): ModUpdateItem? {
            val candidates =
                file.metadata.projectRefs.mapNotNull { ref ->
                    sources.firstOrNull { it.provider == ref.provider }?.let { it to ref }
                }
            for ((source, ref) in candidates) {
                val project = projectOrNull(source, ref.slug, instance, onError) ?: continue
                return resolve(
                    instance,
                    base,
                    source,
                    project,
                    base.currentVersion,
                    ModMatchSource.ProjectId,
                    false,
                    includePrerelease,
                )
            }
            return null
        }

        /** 读取项目页；失败时记录原因并返回 null，让调用方继续尝试下一个候选链接。 */
        private suspend fun projectOrNull(
            source: ModUpdateSource,
            slug: String,
            instance: GameInstance,
            onError: (String) -> Unit,
        ): RemoteModProject? =
            try {
                source.projectBySlug(slug, instance)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                onError(shortErrorMessage(error))
                null
            } catch (error: IllegalStateException) {
                onError(shortErrorMessage(error))
                null
            } catch (error: JsonParseException) {
                onError(shortErrorMessage(error))
                null
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
            batch: BatchLatest? = null,
        ): ModUpdateItem {
            if (currentVersion.isBlank()) {
                return base.copy(
                    matchSource = matchSource,
                    possibleMatch = possible,
                    warning = I18n.t("packsC.updateCheck.unknownVersion"),
                )
            }
            val pool =
                versionPool(instance, source, project, currentVersion, includePrerelease, batch)
            val failure = pool.failure
            if (failure != null) {
                return base.copy(
                    matchSource = matchSource,
                    possibleMatch = possible,
                    warning = failure,
                )
            }
            return latestItem(base, pool.versions, currentVersion, matchSource, possible)
        }

        suspend fun apply(
            instanceId: String,
            item: ModUpdateItem,
            context: JobContext? = null,
        ) = installer.apply(instanceId, item, context)
    }

private fun baseItem(file: LocalModFile): ModUpdateItem {
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
    return ModUpdateItem(
        filename = file.filename,
        title = title,
        currentVersion = current,
        enabled = file.enabled,
        size = file.size,
        modified = file.modified,
    )
}

private suspend fun searchCandidates(
    sources: List<ModUpdateSource>,
    title: String,
    instance: GameInstance,
    onError: (String) -> Unit,
): List<Pair<ModUpdateSource, RemoteModProject>> {
    val candidates = mutableListOf<Pair<ModUpdateSource, RemoteModProject>>()
    for (source in sources) {
        currentCoroutineContext().ensureActive()
        try {
            source
                .search(title, instance)
                .filter { nameMatches(title, it.title) }
                .forEach { project -> candidates += source to project }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            onError(shortErrorMessage(error))
        } catch (error: IllegalStateException) {
            onError(shortErrorMessage(error))
        } catch (error: JsonParseException) {
            onError(shortErrorMessage(error))
        }
    }
    return candidates
}

private fun unmatchedReason(
    projectError: String,
    searchError: String,
): String =
    when {
        projectError.isNotBlank() -> I18n.t("packsC.updateCheck.projectPageFailed", projectError)
        searchError.isNotBlank() -> I18n.t("packsC.updateCheck.searchFailed", searchError)
        else -> I18n.t("packsC.updateCheck.noMatch")
    }

private fun multipleMatchWarning(distinct: List<Pair<ModUpdateSource, RemoteModProject>>): String =
    I18n.t(
        "packsC.updateCheck.multipleMatch",
        distinct.take(MAX_CANDIDATE_TITLES).joinToString("、") { it.second.title },
    )

private fun compatible(
    version: RemoteModVersion,
    instance: GameInstance,
): Boolean =
    instance.gameVersion.isNotBlank() &&
        version.gameVersions.any { it.equals(instance.gameVersion, true) } &&
        version.loaders.any { it.equals(instance.loader.name, true) }

private suspend fun versionPool(
    instance: GameInstance,
    source: ModUpdateSource,
    project: RemoteModProject,
    currentVersion: String,
    includePrerelease: Boolean,
    batch: BatchLatest?,
): VersionPool {
    if (batch?.checked == true) {
        // 批量接口已按游戏版本、加载器与发布类型筛选过，这里只做一次本地复核。
        val latest = batch?.version?.takeIf { it.number.isNotBlank() && compatible(it, instance) }
        return VersionPool(listOfNotNull(latest))
    }
    val versions =
        try {
            source.versions(project, instance)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            return VersionPool(emptyList(), I18n.t("packsB2.packUpdates.readVersionsFailed", shortErrorMessage(error)))
        } catch (error: IllegalStateException) {
            return VersionPool(emptyList(), I18n.t("packsB2.packUpdates.readVersionsFailed", shortErrorMessage(error)))
        } catch (error: JsonParseException) {
            return VersionPool(emptyList(), I18n.t("packsB2.packUpdates.readVersionsFailed", shortErrorMessage(error)))
        }
    val supported = versions.filter { compatible(it, instance) }
    val allowPrerelease = includePrerelease || isPrereleaseVersion(currentVersion)
    return VersionPool(
        supported.filter { allowPrerelease || it.versionType.equals("release", true) },
    )
}

private fun latestItem(
    base: ModUpdateItem,
    pool: List<RemoteModVersion>,
    currentVersion: String,
    matchSource: ModMatchSource,
    possible: Boolean,
): ModUpdateItem {
    val latest =
        pool.maxWithOrNull(
            Comparator { left, right ->
                compareModVersions(left.number, right.number)
            },
        )
    if (latest == null || latest.number.isBlank()) {
        return base.copy(
            status = ModUpdateStatus.Latest,
            matchSource = matchSource,
            possibleMatch = possible,
            warning = I18n.t("packsC.updateCheck.noCompatibleVersion"),
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
                newer && latest.downloadUrl.isBlank() -> I18n.t("packsC.updateCheck.manualDownload")
                possible -> I18n.t("packsC.updateCheck.nameMatch")
                else -> ""
            },
    )
}

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

/** 候选版本池：`failure` 非空表示读取版本失败，调用方原样提示给界面。 */
private data class VersionPool(
    val versions: List<RemoteModVersion>,
    val failure: String? = null,
)

internal fun shortErrorMessage(error: Exception): String = error.message.orEmpty().take(ERROR_MESSAGE_MAX_LENGTH)

private fun failureMessage(error: Exception): String =
    error.message
        .orEmpty()
        .take(FAILURE_MESSAGE_MAX_LENGTH)
        .ifBlank { I18n.t("packsB2.packUpdates.requestFailed") }
