// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责整合包导出的格式、可选内容与写盘流程。

package dev.molifox.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.name

data class MrpackExportMetadata(
    val name: String,
    val version: String,
    val summary: String = "",
)

/** 导出目标格式：前四项可被对应启动器导入，最后一项只是完整备份。 */
enum class PackExportFormat(
    val title: String,
    val extension: String,
    val importable: Boolean,
    val purpose: String,
) {
    Modrinth(
        "Modrinth · .mrpack（可导入）",
        "mrpack",
        true,
        "只写下载链接的标准包；Modrinth App、Prism、HMCL 都能导入，包体最小。",
    ),
    CurseForge(
        "CurseForge · .zip（可导入）",
        "zip",
        true,
        "能匹配到 projectID/fileID 的文件写入受管清单，其余放进 overrides/；CurseForge App 与 Prism 可导入。",
    ),
    MultiMc(
        "MultiMC / Prism · .zip（可导入）",
        "zip",
        true,
        "MultiMC 实例目录：instance.cfg + mmc-pack.json + .minecraft/，可直接作为 Prism 实例导入。",
    ),
    Mcbbs(
        "MCBBS · .zip（PCL / HMCL 可导入）",
        "zip",
        true,
        "HMCL / MCBBS 清单，内容全部放进 overrides/；PCL 与 HMCL 可导入。",
    ),
    Backup(
        "完整备份 · .zip（仅备份，不能当整合包导入）",
        "zip",
        false,
        "完整备份：保留存档、截图和可选日志，只用于还原或留档；其他启动器不会把它识别成整合包。",
    ),
}

/**
 * 可选内容开关。每个开关都直接决定写入包里的条目：
 * 存档/截图/options.txt 默认不进整合包，备份则默认带上存档与截图。
 * 日志与崩溃报告只属于完整备份，且默认关闭。
 */
data class PackExportOptions(
    /** 确认有权分发本地模组/资源包/光影；关闭时这些未走下载清单的文件不写入。 */
    val includeLocalResources: Boolean = false,
    val includeSaves: Boolean = false,
    val includeScreenshots: Boolean = false,
    /** options.txt / optionsof.txt / optionsshaders.txt：默认不含，需显式勾选。 */
    val includeOptionsTxt: Boolean = false,
    /** 排除 mods/ 下以 .disabled 结尾的模组（不会改名为启用）。 */
    val enabledModsOnly: Boolean = false,
    /** logs/ 与 crash-reports/，仅完整备份有效。 */
    val includeLogs: Boolean = false,
) {
    companion object {
        /** 切换格式时使用各格式的合理默认值，避免备份漏掉存档。 */
        fun defaultsFor(format: PackExportFormat): PackExportOptions =
            if (format == PackExportFormat.Backup) {
                PackExportOptions(
                    includeLocalResources = true,
                    includeSaves = true,
                    includeScreenshots = true,
                    includeOptionsTxt = true,
                    includeLogs = false,
                )
            } else {
                PackExportOptions()
            }
    }
}

/** A download reference never includes the resource bytes in the archive. */
data class MrpackExportFile(
    val path: String,
    val size: Long,
    val sha1: String,
    val sha512: String,
    val downloadUrl: String?,
    val selectedByDefault: Boolean,
    val requiresPermission: Boolean,
    /** 已匹配到的 CurseForge 项目与文件编号；两项都有时才会写进受管清单。 */
    val curseForgeProjectId: Long? = null,
    val curseForgeFileId: Long? = null,
)

/** Fixed preview; exporting rechecks both the instance identity and selected file contents. */
class MrpackExportPlan
    internal constructor(
        val instanceId: String,
        val metadata: MrpackExportMetadata,
        val files: List<MrpackExportFile>,
        val warnings: List<String>,
        /** 预览时是否扫描了 logs/ 与 crash-reports/（只有完整备份会）。 */
        val includesLogs: Boolean,
        internal val root: Path,
        internal val realRoot: Path,
        internal val components: InstallRequest,
    ) {
        val defaultSelection: Set<String>
            get() = files.filter { it.selectedByDefault }.map { it.path }.toSet()
    }

/** 该文件会原样写进包内（而不是写下载/受管清单）。 */
internal fun MrpackExportFile.embeddedIn(format: PackExportFormat): Boolean =
    when (format) {
        PackExportFormat.Modrinth -> downloadUrl == null
        PackExportFormat.CurseForge ->
            curseForgeProjectId == null || curseForgeFileId == null
        PackExportFormat.MultiMc,
        PackExportFormat.Mcbbs,
        PackExportFormat.Backup,
        -> true
    }

/** 会被原样打包的本地资源：需要用户确认分发权限。备份只留在本机，不做这层限制。 */
fun exportLocalResourcePaths(
    plan: MrpackExportPlan,
    format: PackExportFormat,
): Set<String> =
    if (format == PackExportFormat.Backup) {
        emptySet()
    } else {
        plan.files
            .filter { it.requiresPermission && it.embeddedIn(format) }
            .map { it.path }
            .toSet()
    }

/**
 * 与界面共用的默认勾选集：默认内容 + 开关控制的可选内容，并剔除禁用模组。
 * 导出端再按同一规则过滤，避免界面计数与实际写入不一致。
 */
fun exportDefaultSelection(
    plan: MrpackExportPlan,
    format: PackExportFormat,
    options: PackExportOptions,
): Set<String> {
    val all = plan.files
    val selected =
        if (format == PackExportFormat.Backup) {
            all.map { it.path }.toMutableSet()
        } else {
            plan.defaultSelection.toMutableSet()
        }
    if (format == PackExportFormat.Backup) {
        if (!options.includeSaves) {
            selected -=
                all.filter { saveExportPath(it.path) }.map { it.path }
        }
        if (!options.includeScreenshots) {
            selected -= all.filter { screenshotExportPath(it.path) }.map { it.path }
        }
        if (!options.includeOptionsTxt) {
            selected -= all.filter { preferenceExportPath(it.path) }.map { it.path }
        }
        if (!options.includeLogs) {
            selected -= all.filter { backupOnlyExportPath(it.path) }.map { it.path }
        }
    } else {
        if (options.includeSaves) selected += all.filter { saveExportPath(it.path) }.map { it.path }
        if (options.includeScreenshots) {
            selected += all.filter { screenshotExportPath(it.path) }.map { it.path }
        }
        if (options.includeOptionsTxt) {
            selected += all.filter { preferenceExportPath(it.path) }.map { it.path }
        }
        selected -= all.filter { backupOnlyExportPath(it.path) }.map { it.path }
        if (options.includeLocalResources) {
            selected += exportLocalResourcePaths(plan, format)
        } else {
            selected -= exportLocalResourcePaths(plan, format)
        }
    }
    if (options.enabledModsOnly) {
        selected -= all.filter { disabledModExportPath(it.path) }.map { it.path }
    }
    return selected
}

/**
 * 导出对话框里的一个顶层条目：实例目录的直接子项（文件或文件夹）。
 * [fileCount]/[totalBytes] 只统计真正会写进归档的文件；扫描未完成时 [scanning] 为真。
 */
data class PackExportEntry(
    val name: String,
    val directory: Boolean,
    val fileCount: Long,
    val totalBytes: Long,
    val scanning: Boolean,
    /** logs/、crash-reports/ 与根目录日志：只有完整备份能导出。 */
    val backupOnly: Boolean,
    /** 共用游戏目录中不属于标准内容的条目：不会写入导出包。 */
    val unavailable: Boolean = false,
) {
    /** 勾选状态使用的稳定键：Windows 上大小写不敏感。 */
    val key: String
        get() = name.lowercase(Locale.ROOT)

    val selectable: Boolean
        get() = !unavailable
}

/** 顶层条目扫描结果：可勾选条目 + 明确不导出的启动器数据/实例元数据。 */
data class PackExportScan(
    val entries: List<PackExportEntry>,
    val omitted: List<String>,
)

private class ExportEntryState(
    val name: String,
    val directory: Boolean,
    val backupOnly: Boolean,
    val unavailable: Boolean,
) {
    var fileCount = 0L
    var totalBytes = 0L
    var scanning = true
}

/**
 * 默认勾选：只勾 mods/、config/、resourcepacks/、shaderpacks/、options.txt（存在才勾）。
 * 所有格式共用同一套默认值，因此切换格式不改变用户已勾选的内容。
 */
private val defaultExportEntryKeys =
    setOf("mods", "config", "resourcepacks", "shaderpacks", "options.txt")

fun defaultExportEntries(entries: List<PackExportEntry>): Set<String> =
    entries
        .filter { it.selectable && it.backupOnly.not() && it.key in defaultExportEntryKeys }
        .map { it.key }
        .toSet()

/**
 * 切换格式时保留用户勾选：默认值所有格式一致，不重新套用；只丢弃已不存在的条目。
 * 这样用户手动勾选/取消的行不会因为换格式被重置。
 */
fun retainExportSelection(
    entries: List<PackExportEntry>,
    checked: Set<String>,
): Set<String> {
    val keys = entries.mapTo(mutableSetOf()) { it.key }
    return checked.filterTo(mutableSetOf()) { it in keys }
}

/**
 * 把顶层勾选落到具体文件：文件夹未勾选时其下所有文件都不写入。
 * 与旧开关共用规则：日志只进完整备份、禁用模组按选项排除、本地资源未经许可不分发。
 */
fun exportSelectedPaths(
    plan: MrpackExportPlan,
    format: PackExportFormat,
    options: PackExportOptions,
    entries: Set<String>,
): Set<String> {
    val selected =
        plan.files
            .filter { it.path.substringBefore('/').lowercase(Locale.ROOT) in entries }
            .filter { format == PackExportFormat.Backup || !backupOnlyExportPath(it.path) }
            .map { it.path }
            .toMutableSet()
    if (format != PackExportFormat.Backup && !options.includeLocalResources) {
        selected -= exportLocalResourcePaths(plan, format)
    }
    if (options.enabledModsOnly) {
        selected -= plan.files.filter { disabledModExportPath(it.path) }.map { it.path }
    }
    return selected
}

/** 实例自身的版本文件与升级备份副本：属于实例元数据，不当作整合包内容。 */
internal fun instanceOwnExportFile(
    name: String,
    versionId: String,
): Boolean {
    val lower = name.lowercase(Locale.ROOT)
    if (lower.contains(".json.molifox-backup-")) return true
    val id = versionId.lowercase(Locale.ROOT)
    return id.isNotBlank() && (lower == "$id.json" || lower == "$id.jar")
}

/** 启动器/其他启动器的私有数据与包元数据：永不出现在可选列表里。 */
internal fun launcherPrivateExportEntry(name: String): Boolean {
    val key = name.lowercase(Locale.ROOT)
    if (key.endsWith(".part")) return true
    if (key.startsWith(".molifox")) return true
    if (key.startsWith("credentials")) return true
    // 日志与缓存是可选的导出内容，不是私有数据。
    if (key in backupOnlyExportRoots || key == "cache" || key.startsWith(".cache")) return false
    if (key == "backups") return false
    return key in launcherPrivateExportNames
}

private val launcherPrivateExportNames =
    setOf(
        "molifox",
        "accounts",
        "tokens",
        "launcher.json",
        "launcher.json.bak",
        "accounts.json",
        "hmcl.json",
        "hmclversion.cfg",
        "instance.cfg",
        "launcher_profiles.json",
        "launcher_accounts.json",
        "launcher_msa_credentials.bin",
        "usernamecache.json",
        "usercache.json",
        "realms_persistence.json",
        "modrinth.index.json",
        "mcbbs.packmeta",
        "molifox-instance.json",
        "molifox-backup.json",
        "manifest.json",
        "modpack.json",
        "mmc-pack.json",
        "libraries",
        "assets",
        "versions",
        "natives",
        ".git",
        ".hmcl",
        ".fabric",
        ".quilt",
    )

internal class MrpackExporter(
    private val store: LauncherStore,
    private val network: NetworkClient,
    private val api: String,
    private val curseForge: CurseForgeBackend? = null,
) {
    /**
     * 只读扫描实例目录的直接子项，供导出对话框逐项勾选。
     * 边扫描边发快照：大实例（上万文件）也不会阻塞界面，界面可先显示 `…` 再逐步更新。
     */
    fun entries(id: String): Flow<PackExportScan> =
        flow {
            val instance = exportableInstance(id)
            val root = store.instancePath(id).toAbsolutePath().normalize()
            require(root.isDirectory() && !Files.isSymbolicLink(root)) {
                "实例目录不存在或是符号链接"
            }
            val isolated = store.isolated(instance)
            val tracks = mutableListOf<ExportEntryState>()
            val omitted = mutableListOf<String>()
            Files.newDirectoryStream(root).use { children ->
                for (child in children) {
                    coroutineContext.ensureActive()
                    if (Files.isSymbolicLink(child)) continue
                    val name = child.fileName.toString()
                    if (
                        instanceOwnExportFile(name, instance.versionId) ||
                        launcherPrivateExportEntry(name)
                    ) {
                        omitted += name
                        continue
                    }
                    val directory = Files.isDirectory(child, NOFOLLOW_LINKS)
                    val key = name.lowercase(Locale.ROOT)
                    val backupOnly =
                        key in backupOnlyExportRoots ||
                            (!directory && (key.endsWith(".log") || key.endsWith(".log.gz")))
                    tracks +=
                        ExportEntryState(
                            name,
                            directory,
                            backupOnly,
                            unavailable = !isolated && key !in exportContentRoots,
                        )
                }
            }
            tracks.sortWith(
                compareByDescending<ExportEntryState> { it.directory }
                    .thenBy { it.name.lowercase(Locale.ROOT) },
            )
            omitted.sort()
            var lastEmit = 0L
            var filesSinceEmit = 0

            suspend fun emitSnapshot(force: Boolean) {
                val now = System.nanoTime()
                if (!force && now - lastEmit < 60_000_000L) return
                lastEmit = now
                filesSinceEmit = 0
                emit(
                    PackExportScan(
                        tracks.map {
                            PackExportEntry(
                                it.name,
                                it.directory,
                                it.fileCount,
                                it.totalBytes,
                                it.scanning,
                                it.backupOnly,
                                it.unavailable,
                            )
                        },
                        omitted.toList(),
                    ),
                )
            }
            emitSnapshot(true)
            for (track in tracks) {
                if (track.unavailable) {
                    track.scanning = false
                    continue
                }
                val child = root.resolve(track.name)
                if (!track.directory) {
                    track.fileCount = 1
                    track.totalBytes = runCatching { Files.size(child) }.getOrDefault(0L)
                    track.scanning = false
                    emitSnapshot(true)
                    continue
                }
                val pending = ArrayDeque<Path>().apply { add(child) }
                while (pending.isNotEmpty()) {
                    coroutineContext.ensureActive()
                    val directory = pending.removeFirst()
                    try {
                        Files.newDirectoryStream(directory).use { paths ->
                            for (file in paths) {
                                coroutineContext.ensureActive()
                                if (Files.isSymbolicLink(file)) continue
                                val relative = root.relativize(file).joinToString("/")
                                // 日志目录按“显式勾选才写入”统计；普通目录不计嵌套日志。
                                if (excludedExportPath(
                                        relative,
                                        allowLogs = track.backupOnly,
                                    )
                                ) {
                                    continue
                                }
                                if (Files.isDirectory(file, NOFOLLOW_LINKS)) {
                                    pending.add(file)
                                    continue
                                }
                                if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) continue
                                track.fileCount++
                                track.totalBytes +=
                                    runCatching { Files.size(file) }.getOrDefault(0L)
                                filesSinceEmit++
                                if (filesSinceEmit >= 64) emitSnapshot(false)
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // 单个目录不可读时跳过，其余条目照常统计。
                    }
                }
                track.scanning = false
                emitSnapshot(true)
            }
            // 全部条目扫描结束（包括全部不可用的情形）后一定发一次终态。
            emitSnapshot(true)
        }.flowOn(Dispatchers.IO)

    suspend fun preview(
        id: String,
        metadata: MrpackExportMetadata,
        resolveDownloads: Boolean,
        resolveCurseForge: Boolean = false,
        includeLogs: Boolean = false,
    ): MrpackExportPlan =
        withContext(Dispatchers.IO) {
            require(metadata.name.isNotBlank() && metadata.name.length <= 200) {
                "请输入整合包名称（最多 200 字）"
            }
            require(metadata.version.isNotBlank() && metadata.version.length <= 100) {
                "请输入整合包版本（最多 100 字）"
            }
            require(metadata.summary.length <= 2000) { "整合包简介最多 2000 字" }
            require(
                listOf(metadata.name, metadata.version, metadata.summary).none { '\u0000' in it },
            ) {
                "整合包信息包含无效字符"
            }
            val instance = exportableInstance(id)
            val root = store.instancePath(id).toAbsolutePath().normalize()
            require(root.isDirectory() && !Files.isSymbolicLink(root)) { "实例目录不存在或是符号链接" }
            val realRoot = root.toRealPath()
            val warnings = mutableListOf<String>()
            val files = mutableListOf<MrpackExportFile>()
            // Standard component metadata cannot preserve another launcher's custom patches.
            val names = mutableSetOf<String>()
            val contentRoots =
                if (includeLogs) exportContentRoots + backupOnlyExportRoots else exportContentRoots
            val directories = ArrayDeque<Path>().apply { add(root) }
            while (directories.isNotEmpty()) {
                Files.newDirectoryStream(directories.removeFirst()).use { paths ->
                    for (file in paths) {
                        coroutineContext.ensureActive()
                        if (Files.isSymbolicLink(file)) continue
                        val path = root.relativize(file).joinToString("/")
                        if (excludedExportPath(path, includeLogs)) continue
                        if (instanceOwnExportFile(path, instance.versionId)) continue
                        val top = path.substringBefore('/').lowercase(Locale.ROOT)
                        if (!store.isolated(instance) && top !in contentRoots) continue
                        val checked = safePath(root, path)
                        require(checked.toRealPath().startsWith(realRoot)) { "导出文件指向实例目录外：$path" }
                        if (Files.isDirectory(checked, NOFOLLOW_LINKS)) {
                            directories.add(checked)
                            continue
                        }
                        if (!Files.isRegularFile(checked, NOFOLLOW_LINKS)) continue
                        // 日志可能带登录凭据：即使已选择包含日志，命中凭据特征的单个文件也跳过。
                        if (includeLogs && backupOnlyExportPath(path) &&
                            exportLogContainsSecret(checked)
                        ) {
                            warnings += "已跳过疑似包含登录凭据的日志：$path"
                            continue
                        }
                        require(names.add(path.lowercase(Locale.ROOT))) { "文件名在 Windows 上冲突：$path" }
                        require(files.size < 20000) { "实例文件过多，请缩小导出范围" }
                        val hashes = hashExportFile(checked)
                        val resource =
                            top in setOf("mods", "resourcepacks", "shaderpacks") ||
                                path.substringAfterLast('.', "").lowercase(Locale.ROOT) in
                                setOf("jar", "zip", "mrpack", "exe", "dll", "so")
                        files +=
                            MrpackExportFile(
                                path,
                                hashes.size,
                                hashes.sha1,
                                hashes.sha512,
                                null,
                                top in exportDefaultRoots && !resource,
                                resource,
                            )
                    }
                }
            }
            val remotes = mutableMapOf<String, String>()
            if (resolveDownloads) {
                for (
                batch in
                files
                    .filter { it.requiresPermission }
                    .map { it.sha512 }
                    .distinct()
                    .chunked(100)
                ) {
                    coroutineContext.ensureActive()
                    try {
                        val body =
                            com.google.gson.JsonObject().apply {
                                addProperty("algorithm", "sha512")
                                add("hashes", gson.toJsonTree(batch))
                            }
                        val response = network.post("$api/version_files", body)
                        for (file in files.filter { it.sha512 in batch }) {
                            val version =
                                response.get(file.sha512)?.takeIf { it.isJsonObject }?.asJsonObject
                                    ?: continue
                            val remote =
                                version
                                    .array("files")
                                    .firstOrNull {
                                        it.isJsonObject &&
                                            it.asJsonObject.let { candidate ->
                                                candidate
                                                    .obj("hashes")
                                                    .string("sha512")
                                                    .equals(file.sha512, true) &&
                                                    candidate
                                                        .obj("hashes")
                                                        .string("sha1")
                                                        .equals(file.sha1, true) &&
                                                    candidate.get("size")?.asLong == file.size &&
                                                    allowedMrpackDownload(candidate.string("url"))
                                            }
                                    }?.asJsonObject
                            if (remote != null) remotes[file.sha512] = remote.string("url")
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        warnings += "部分文件的 Modrinth 来源查询失败；未识别资源保持未选中，不会自动打包。"
                    }
                }
            }
            val curseForgeIds = mutableMapOf<String, Pair<Long, Long>>()
            if (resolveCurseForge) {
                val candidates = files.filter { curseForgeCandidate(it.path) }
                if (candidates.isNotEmpty()) {
                    val backend = curseForge
                    if (backend == null) {
                        warnings += "CurseForge 匹配不可用；所有模组会放进 overrides/mods，不会写入受管清单。"
                    } else {
                        try {
                            val matched =
                                backend.fingerprints(candidates.map { safePath(root, it.path) })
                            for (file in candidates) {
                                val info = matched[safePath(root, file.path)] ?: continue
                                curseForgeIds[file.path] = curseForgeIds(info) ?: continue
                            }
                            val unmatched = candidates.size - curseForgeIds.size
                            if (unmatched > 0) {
                                warnings +=
                                    "$unmatched 个模组未匹配到 CurseForge 编号，会放进 overrides/mods（该包不会对它们自动更新）。"
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            warnings += "CurseForge 指纹匹配失败；所有模组会放进 overrides/mods，不会写入受管清单。"
                        }
                    }
                }
            }
            if (instance.externalProfile) {
                warnings += "外部实例仅导出标准游戏与加载器组件；原启动器的特殊补丁、启动命令和认证配置不会写入整合包。"
            }
            MrpackExportPlan(
                id,
                metadata,
                files
                    .sortedBy { it.path }
                    .map { file ->
                        val cf = curseForgeIds[file.path]
                        when {
                            cf != null ->
                                file.copy(
                                    curseForgeProjectId = cf.first,
                                    curseForgeFileId = cf.second,
                                    selectedByDefault = true,
                                    requiresPermission = false,
                                )
                            remotes[file.sha512] != null ->
                                file.copy(
                                    downloadUrl = remotes.getValue(file.sha512),
                                    selectedByDefault = true,
                                    requiresPermission = false,
                                )
                            else -> file
                        }
                    },
                warnings.distinct(),
                includeLogs,
                root,
                realRoot,
                InstallRequest(
                    instance.name,
                    instance.gameVersion,
                    instance.loader,
                    instance.loaderVersion,
                ),
            )
        }

    suspend fun export(
        plan: MrpackExportPlan,
        destination: Path,
        selected: Set<String>,
        permittedLocalFiles: Set<String>,
        context: JobContext,
        format: PackExportFormat = PackExportFormat.Modrinth,
        options: PackExportOptions = PackExportOptions(),
    ) = withContext(Dispatchers.IO) {
        context.checkCancelled()
        validateSnapshot(plan)
        require(destination.extension.equals(format.extension, true)) {
            "此整合包格式需要 .${format.extension} 扩展名"
        }
        val target = destination.toAbsolutePath().normalize()
        // Resolve existing ancestors before creating anything, including aliases to the game
        // root.
        val resolved = resolveExportDestination(target)
        val protectedRoots =
            listOf(
                plan.realRoot,
                store.dataDirectory.toRealPath(),
                store.minecraftRoot(store.instance(plan.instanceId)).toRealPath(),
            )
        require(protectedRoots.none { resolved.startsWith(it) }) {
            "请将整合包保存到游戏目录和 MoliFox 数据目录外"
        }
        require(selected.all { path -> plan.files.any { it.path == path } }) {
            "导出选择已失效，请重新预览"
        }
        require(selected.isNotEmpty()) { "请至少选择一项要写入的内容" }
        require(
            format == PackExportFormat.Backup || selected.none { backupOnlyExportPath(it) },
        ) {
            "日志与崩溃报告只随完整备份导出"
        }
        // 日志只属于完整备份；禁用模组按选项排除，不改名为启用。
        val files =
            plan.files
                .filter { it.path in selected }
                .filter {
                    format == PackExportFormat.Backup &&
                        (!backupOnlyExportPath(it.path) || options.includeLogs) ||
                        format != PackExportFormat.Backup && !backupOnlyExportPath(it.path)
                }.filter { !(options.enabledModsOnly && disabledModExportPath(it.path)) }
        require(files.isNotEmpty()) { "勾选的内容没有可写入的文件，请重新选择" }
        val embedded = files.filter { it.embeddedIn(format) }
        // 完整备份只留在本机，不属于分发，不需要逐个确认分发权限。
        val missing =
            if (format == PackExportFormat.Backup) {
                emptyList()
            } else {
                embedded.filter { it.requiresPermission && it.path !in permittedLocalFiles }
            }
        require(missing.isEmpty()) {
            "以下本地资源尚未确认分发权限：${missing.joinToString { it.path }}"
        }
        val icon =
            try {
                if (format == PackExportFormat.Modrinth || format == PackExportFormat.Mcbbs) {
                    InstanceIcons(store).exportImage(store.instance(plan.instanceId))
                } else {
                    null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        val layout = buildPackExportLayout(format, plan, options, files, icon)
        require(layout.documents.all { it.second.size <= 8 * 1024 * 1024 }) {
            "导出清单过大，请减少选择的文件"
        }
        target.parent.createDirectories()
        val temporary = target.resolveSibling(".${target.fileName}.${UUID.randomUUID()}.part")
        try {
            val total = layout.totalEntries
            var written = 0
            ZipOutputStream(
                Files.newOutputStream(
                    temporary,
                    java.nio.file.StandardOpenOption.CREATE_NEW,
                ),
            ).use { zip ->
                for (directory in layout.directories) {
                    context.checkCancelled()
                    zip.putNextEntry(ZipEntry(directory))
                    zip.closeEntry()
                    written++
                }
                for ((name, bytes) in layout.documents) {
                    context.checkCancelled()
                    context.progress(
                        written.toFloat() / maxOf(1, total),
                        "写入 $name（${written + 1}/$total）",
                    )
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                    written++
                }
                if (layout.icon != null) {
                    context.checkCancelled()
                    zip.putNextEntry(ZipEntry("icon.png"))
                    zip.write(layout.icon)
                    zip.closeEntry()
                    written++
                }
                for ((archivePath, file) in layout.payload) {
                    context.checkCancelled()
                    context.progress(
                        written.toFloat() / maxOf(1, total),
                        "导出 ${file.path}（${written + 1}/$total）",
                    )
                    val source = safePath(plan.root, file.path)
                    require(
                        Files.isRegularFile(source, NOFOLLOW_LINKS) &&
                            source.toRealPath().startsWith(plan.realRoot),
                    ) {
                        "文件已移动，请重新预览：${file.path}"
                    }
                    zip.putNextEntry(ZipEntry(archivePath))
                    val hashes =
                        hashExportFile(source) { bytes, count ->
                            zip.write(bytes, 0, count)
                        }
                    require(
                        hashes == ExportHashes(file.size, file.sha1, file.sha512),
                    ) {
                        "文件已改变，请重新预览：${file.path}"
                    }
                    zip.closeEntry()
                    written++
                }
            }
            context.checkCancelled()
            // 重新打开写好的归档，逐项确认该格式必需的条目和清单字段都在。
            validatePackExport(
                temporary,
                format,
                layout,
                plan,
                options,
                files.map { it.path }.toSet(),
            )
            validateSnapshot(plan)
            require(resolveExportDestination(target) == resolved) { "导出目录已改变" }
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
            context.progress(1f, "${format.title} 已导出")
        } finally {
            temporary.deleteIfExists()
        }
    }

    private fun exportableInstance(id: String): GameInstance =
        store.instance(id).also {
            require(it.installed) {
                "请先完成实例安装，再导出整合包"
            }
            require(
                it.gameVersion.isNotBlank() &&
                    (it.loader == LoaderType.Vanilla || it.loaderVersion.isNotBlank()),
            ) {
                "实例缺少明确的游戏或加载器版本"
            }
        }

    private fun validateSnapshot(plan: MrpackExportPlan) {
        val instance = exportableInstance(plan.instanceId)
        require(
            store.instancePath(instance.id).toAbsolutePath().normalize() == plan.root &&
                plan.root.toRealPath() == plan.realRoot &&
                instance.gameVersion == plan.components.gameVersion &&
                instance.loader == plan.components.loader &&
                instance.loaderVersion == plan.components.loaderVersion,
        ) {
            "实例配置已改变，请重新预览"
        }
    }
}

/** 只有完整备份才扫描这些目录；非备份导出始终排除。 */
internal val backupOnlyExportRoots = setOf("logs", "crash-reports")

private val exportDefaultRoots = setOf("config", "defaultconfigs", "kubejs", "scripts")
private val exportContentRoots =
    exportDefaultRoots +
        setOf(
            "mods",
            "resourcepacks",
            "shaderpacks",
            "saves",
            "screenshots",
            "schematics",
            "options.txt",
            "optionsof.txt",
            "optionsshaders.txt",
            "servers.dat",
        )

/** Launcher private/runtime files are never offered, even when a user selects all candidates. */
internal fun excludedExportPath(
    path: String,
    allowLogs: Boolean = false,
): Boolean {
    if (path.endsWith(".part", true)) return true
    val parts = path.lowercase(Locale.ROOT).split('/')
    val logLike =
        parts.any { it in backupOnlyExportRoots || it.endsWith(".log") || it.endsWith(".log.gz") }
    if (logLike) {
        // 勾选日志后才写入；锁定文件始终跳过。
        return !allowLogs || parts.any { it.endsWith(".lock") }
    }
    return parts.any { part ->
        part.startsWith(".molifox") ||
            part.startsWith("credentials") ||
            part in
            setOf(
                "molifox",
                ".hmcl",
                ".git",
                ".fabric",
                ".quilt",
                "natives",
                "libraries",
                "assets",
                "versions",
                "accounts",
                "tokens",
                "launcher.json",
                "launcher.json.bak",
                "accounts.json",
                "hmcl.json",
                "hmclversion.cfg",
                "instance.cfg",
                "launcher_profiles.json",
                "launcher_accounts.json",
                "launcher_msa_credentials.bin",
                "usernamecache.json",
                "usercache.json",
                "realms_persistence.json",
                "modrinth.index.json",
                "mcbbs.packmeta",
                "molifox-instance.json",
                "molifox-backup.json",
                "manifest.json",
                "modpack.json",
                "mmc-pack.json",
            )
    }
}

/** 备份里的存档、截图与日志分类；导出端与界面共用。 */
internal fun backupOnlyExportPath(path: String): Boolean =
    path.substringBefore('/').lowercase(Locale.ROOT) in backupOnlyExportRoots

internal fun saveExportPath(path: String): Boolean = path.substringBefore('/').equals("saves", true)

internal fun screenshotExportPath(path: String): Boolean =
    path.substringBefore('/').equals("screenshots", true)

internal fun preferenceExportPath(path: String): Boolean =
    '/' !in path &&
        path.lowercase(Locale.ROOT) in setOf("options.txt", "optionsof.txt", "optionsshaders.txt")

internal fun disabledModExportPath(path: String): Boolean =
    path.substringBefore('/').equals("mods", true) &&
        path.substringAfterLast('/').lowercase(Locale.ROOT).endsWith(".disabled")

/** CurseForge 指纹可识别的文件：mods/ 下的 jar（含 .disabled 副本）。 */
internal fun curseForgeCandidate(path: String): Boolean {
    if (!path.substringBefore('/').equals("mods", true)) return false
    val name = path.substringAfterLast('/').lowercase(Locale.ROOT)
    return name.endsWith(".jar") || name.endsWith(".jar.disabled")
}

private fun curseForgeIds(info: com.google.gson.JsonObject): Pair<Long, Long>? {
    val project =
        info.get("modId")?.let { runCatching { it.asLong }.getOrNull() } ?: return null
    val file = info.get("id")?.let { runCatching { it.asLong }.getOrNull() } ?: return null
    return if (project in 1..0xffffffffL && file in 1..0xffffffffL) project to file else null
}

private val exportSecretPatterns =
    listOf(
        Regex("(?i)access[_-]?token\"?\\s*[=:]\\s*\"?[A-Za-z0-9._-]{16,}"),
        Regex("(?i)refresh[_-]?token\"?\\s*[=:]\\s*\"?[A-Za-z0-9._-]{16,}"),
        Regex("(?i)session[_-]?token\"?\\s*[=:]\\s*\"?[A-Za-z0-9._-]{16,}"),
        Regex("(?i)bearer\\s+[A-Za-z0-9._-]{20,}"),
        Regex("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"),
    )

/** 日志内容筛查：即使勾选包含日志，疑似含凭据的单个文件也不会进入备份。 */
internal fun exportLogContainsSecret(path: Path): Boolean =
    try {
        Files.newInputStream(path).use { input ->
            val text = input.readNBytes(4 shl 20).toString(Charsets.UTF_8)
            exportSecretPatterns.any { it.containsMatchIn(text) }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        true
    }

private fun allowedMrpackDownload(value: String): Boolean =
    runCatching {
        val uri = URI(value)
        uri.scheme == "https" &&
            uri.host?.lowercase(Locale.ROOT) in
            setOf("cdn.modrinth.com", "github.com", "raw.githubusercontent.com", "gitlab.com") &&
            uri.userInfo == null &&
            uri.fragment == null &&
            uri.port in setOf(-1, 443)
    }.getOrDefault(false)

internal data class ExportHashes(
    val size: Long,
    val sha1: String,
    val sha512: String,
)

internal suspend fun hashExportFile(
    path: Path,
    consume: (ByteArray, Int) -> Unit = { _, _ -> },
): ExportHashes {
    val sha1 = MessageDigest.getInstance("SHA-1")
    val sha512 = MessageDigest.getInstance("SHA-512")
    var size = 0L
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(65536)
        while (true) {
            coroutineContext.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            sha1.update(buffer, 0, count)
            sha512.update(buffer, 0, count)
            size += count
            consume(buffer, count)
        }
    }
    return ExportHashes(
        size,
        sha1.digest().joinToString("") { "%02x".format(it) },
        sha512.digest().joinToString("") { "%02x".format(it) },
    )
}

private fun resolveExportDestination(path: Path): Path {
    var ancestor = path
    while (!Files.exists(ancestor, NOFOLLOW_LINKS)) ancestor = requireNotNull(ancestor.parent)
    require(!Files.isSymbolicLink(path)) { "导出目标不能是符号链接" }
    return ancestor.toRealPath().resolve(ancestor.relativize(path)).normalize()
}
