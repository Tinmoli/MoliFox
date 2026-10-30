// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 集中模组识别和更新查询，让文件替换事务由独立职责处理。

package dev.molifox.core

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
import java.util.Comparator
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

private val modFilePattern = Regex("(?i).*\\.jar(?:\\.disabled)?")

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
                val files =
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
                            "${source.provider}：${error.message.orEmpty().take(
                                160,
                            ).ifBlank { "请求失败" }}"
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
                val versionMs = elapsedMillis(versionStarted)
                store.log(
                    "更新检查：扫描 ${files.size} 个文件 $scanMs ms，指纹查询 $fingerprintMs ms，" +
                        "版本查询 $versionMs ms，总计 ${elapsedMillis(startedAt)} ms",
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
            val matched =
                files.mapNotNull { file ->
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
                    listOfNotNull(
                        latestKnown?.takeIf {
                            it.number.isNotBlank() &&
                                compatible(it, instance)
                        },
                    )
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

        private fun compatible(
            version: RemoteModVersion,
            instance: GameInstance,
        ): Boolean =
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

        suspend fun apply(
            instanceId: String,
            item: ModUpdateItem,
            context: JobContext? = null,
        ) = installer.apply(instanceId, item, context)
    }
