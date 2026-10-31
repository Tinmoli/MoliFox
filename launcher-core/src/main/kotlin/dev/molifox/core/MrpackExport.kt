// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责整合包导出的格式、可选内容与写盘流程。

package dev.molifox.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isSymbolicLink

/** 整合包元数据的长度上限，与导出对话框的输入限制共用。 */
private const val MAX_PACK_NAME_LENGTH = 200
private const val MAX_PACK_VERSION_LENGTH = 100
private const val MAX_PACK_SUMMARY_LENGTH = 2000

/** Modrinth 版本查询每次提交的哈希数量。 */
private const val MODRINTH_HASH_BATCH_SIZE = 100

/** 归档内单个清单/文本文件的大小上限。 */
private const val MAX_EXPORT_DOCUMENT_BYTES = 8 * 1024 * 1024

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
        "Modrinth · .mrpack",
        "mrpack",
        true,
        "标准整合包；可从 Modrinth 下载资源，未命中的文件自动打包。",
    ),
    CurseForge(
        "ZIP · 通用整合包",
        "zip",
        true,
        "全量打包；PCL CE、HMCL、Prism、CurseForge App 均可导入。",
    ),
    MultiMc(
        "MultiMC / Prism · .zip（可导入）",
        "zip",
        true,
        "MultiMC 实例目录：instance.cfg + mmc-pack.json + .minecraft/；" +
            "Prism/MultiMC 可用「从 ZIP 导入实例」打开，实例内存/JVM/窗口设置一并写入。",
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
    ;

    companion object {
        /**
         * 导出界面只保留两种：Modrinth 与通用 ZIP。其余枚举保留用于旧包识别与回退。
         */
        val offeredForExport: List<PackExportFormat> =
            listOf(Modrinth, CurseForge)
    }
}

/**
 * 可选内容开关。每个开关都直接决定写入包里的条目：
 * 存档/截图/options.txt 默认不进整合包，备份则默认带上存档与截图。
 * 日志与崩溃报告只属于完整备份，且默认关闭。
 */
data class PackExportOptions(
    /** 优先写入远程来源（Modrinth/CurseForge 下载链接），默认开启。 */
    val remoteResources: Boolean = true,
    /** Modrinth 上传模式：只使用 Modrinth 来源，仅 CurseForge 有的文件改为本地打包。 */
    val modrinthUploadMode: Boolean = false,
    /** 未命中下载来源的本地模组/资源包/光影一并打包，默认开启。 */
    val includeLocalResources: Boolean = true,
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
        /** 实例级启动设置（内存/JVM/窗口），供 MultiMC/Prism 的 instance.cfg 使用。 */
        internal val instance: GameInstance,
    ) {
        val defaultSelection: Set<String>
            get() = files.filter { it.selectedByDefault }.map { it.path }.toSet()
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
    /** 文件夹内的实际子项树，供界面展开；文件条目为空。 */
    val children: List<PackExportNode> = emptyList(),
) {
    /** 勾选状态使用的稳定键：Windows 上大小写不敏感。 */
    val key: String
        get() = name.lowercase(Locale.ROOT)

    val selectable: Boolean
        get() = !unavailable
}

/** 导出内容树中的一个实际文件或文件夹；文件夹可继续展开。 */
data class PackExportNode(
    val path: String,
    val name: String,
    val directory: Boolean,
    val children: List<PackExportNode> = emptyList(),
)

/** 顶层条目扫描结果：可勾选条目 + 明确不导出的启动器数据/实例元数据。 */
data class PackExportScan(
    val entries: List<PackExportEntry>,
    val omitted: List<String>,
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
            emitAll(PackExportScanner(root, instance.versionId, store.isolated(instance)).scan())
        }.flowOn(Dispatchers.IO)

    suspend fun preview(
        id: String,
        metadata: MrpackExportMetadata,
        resolveDownloads: Boolean,
        resolveCurseForge: Boolean = false,
        includeLogs: Boolean = false,
    ): MrpackExportPlan =
        withContext(Dispatchers.IO) {
            validatePackMetadata(metadata)
            val instance = exportableInstance(id)
            val root = store.instancePath(id).toAbsolutePath().normalize()
            require(root.isDirectory() && !Files.isSymbolicLink(root)) { "实例目录不存在或是符号链接" }
            val realRoot = root.toRealPath()
            val scan = ExportScanState(store, instance, root, realRoot, includeLogs)
            scanInstanceFiles(scan)
            val remotes = resolveDownloadUrls(scan.files, scan.warnings, resolveDownloads)
            val curseForgeIds =
                resolveCurseForgeIds(root, scan.files, scan.warnings, resolveCurseForge)
            if (instance.externalProfile) {
                scan.warnings += "外部实例仅导出标准游戏与加载器组件；原启动器的特殊补丁、启动命令和认证配置不会写入整合包。"
            }
            MrpackExportPlan(
                id,
                metadata,
                scan.files
                    .sortedBy { it.path }
                    .map { file ->
                        withResolvedSource(file, remotes[file.sha512], curseForgeIds[file.path])
                    },
                scan.warnings.distinct(),
                includeLogs,
                root,
                realRoot,
                InstallRequest(
                    instance.name,
                    instance.gameVersion,
                    instance.loader,
                    instance.loaderVersion,
                ),
                instance,
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
        val target = exportTargetPath(format, destination)
        // Resolve existing ancestors before creating anything, including aliases to the game
        // root.
        val resolved = resolveExportDestination(target)
        requireExportDestinationOutsideGame(plan, resolved)
        val files = selectExportFiles(plan, selected, format, options)
        requireExportPermissions(files, permittedLocalFiles, format)
        val icon = exportIcon(plan, format)
        val layout = buildPackExportLayout(format, plan, options, files, icon)
        require(layout.documents.all { it.second.size <= MAX_EXPORT_DOCUMENT_BYTES }) {
            "导出清单过大，请减少选择的文件"
        }
        target.parent.createDirectories()
        val temporary = target.resolveSibling(".${target.fileName}.${UUID.randomUUID()}.part")
        try {
            val output = Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW)
            ZipOutputStream(output).use { zip -> writePackEntries(zip, layout, plan, context) }
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
            moveFromTemporary(temporary, target)
            context.progress(1f, "${format.title} 已导出")
        } finally {
            temporary.deleteIfExists()
        }
    }

    /** 拒绝把包写进游戏目录、MoliFox 数据目录或实例目录（包括符号链接别名）。 */
    private fun requireExportDestinationOutsideGame(
        plan: MrpackExportPlan,
        resolved: Path,
    ) {
        val protectedRoots =
            listOf(
                plan.realRoot,
                store.dataDirectory.toRealPath(),
                store.minecraftRoot(store.instance(plan.instanceId)).toRealPath(),
            )
        require(protectedRoots.none { resolved.startsWith(it) }) {
            "请将整合包保存到游戏目录和 MoliFox 数据目录外"
        }
    }

    /**
     * 整合包图标：Modrinth / MCBBS 写根目录 `icon.png`，MultiMC/Prism 写 `icons/<key>.png`；
     * 完整备份与 CurseForge 清单不需要图标。取图标失败不阻断打包。
     */
    private suspend fun exportIcon(
        plan: MrpackExportPlan,
        format: PackExportFormat,
    ): ByteArray? =
        try {
            when (format) {
                PackExportFormat.Modrinth, PackExportFormat.Mcbbs, PackExportFormat.MultiMc ->
                    InstanceIcons(store).exportImage(store.instance(plan.instanceId))
                PackExportFormat.CurseForge, PackExportFormat.Backup -> null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

    /** 按 SHA-512 分批向 Modrinth 查询下载来源；查询失败只记录，不阻断导出。 */
    private suspend fun resolveDownloadUrls(
        files: List<MrpackExportFile>,
        warnings: MutableList<String>,
        enabled: Boolean,
    ): Map<String, String> {
        val remotes = mutableMapOf<String, String>()
        if (!enabled) return remotes
        val batches =
            files
                .filter { it.requiresPermission }
                .map { it.sha512 }
                .distinct()
                .chunked(MODRINTH_HASH_BATCH_SIZE)
        for (batch in batches) {
            coroutineContext.ensureActive()
            try {
                resolveDownloadBatch(batch, files, remotes)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                warnings += "部分文件的 Modrinth 来源查询失败；未识别资源保持未选中，不会自动打包。"
            }
        }
        return remotes
    }

    /** 查询一个哈希批次并把可信下载链接写入 [remotes]；未匹配的文件保持原样。 */
    private suspend fun resolveDownloadBatch(
        batch: List<String>,
        files: List<MrpackExportFile>,
        remotes: MutableMap<String, String>,
    ) {
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
            val remote = matchingRemoteFile(version, file)
            if (remote != null) remotes[file.sha512] = remote.string("url")
        }
    }

    /** 用 CurseForge 指纹匹配候选模组；不可用或失败时只记录，不阻断导出。 */
    private suspend fun resolveCurseForgeIds(
        root: Path,
        files: List<MrpackExportFile>,
        warnings: MutableList<String>,
        enabled: Boolean,
    ): Map<String, CurseForgeSource> {
        val matchedIds = mutableMapOf<String, CurseForgeSource>()
        if (!enabled) return matchedIds
        val candidates = files.filter { curseForgeCandidate(it.path) }
        if (candidates.isEmpty()) return matchedIds
        val backend = curseForge
        if (backend == null) {
            warnings += "CurseForge 匹配不可用；所有模组会放进 overrides/mods，不会写入受管清单。"
            return matchedIds
        }
        try {
            val matched = backend.fingerprints(candidates.map { safePath(root, it.path) })
            for (file in candidates) {
                val info = matched[safePath(root, file.path)]
                val source = if (info == null) null else curseForgeSource(info)
                if (source != null) matchedIds[file.path] = source
            }
            val unmatched = candidates.size - matchedIds.size
            if (unmatched > 0) {
                warnings +=
                    "$unmatched 个模组未匹配到 CurseForge 编号，" +
                    "会放进 overrides/mods（该包不会对它们自动更新）。"
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            warnings += "CurseForge 指纹匹配失败；所有模组会放进 overrides/mods，不会写入受管清单。"
        }
        return matchedIds
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

/** 校验整合包元数据的长度与非法字符，与导出对话框的输入限制共用。 */
private fun validatePackMetadata(metadata: MrpackExportMetadata) {
    require(metadata.name.isNotBlank() && metadata.name.length <= MAX_PACK_NAME_LENGTH) {
        "请输入整合包名称（最多 200 字）"
    }
    require(metadata.version.isNotBlank() && metadata.version.length <= MAX_PACK_VERSION_LENGTH) {
        "请输入整合包版本（最多 100 字）"
    }
    require(metadata.summary.length <= MAX_PACK_SUMMARY_LENGTH) { "整合包简介最多 2000 字" }
    require(
        listOf(metadata.name, metadata.version, metadata.summary).none { '\u0000' in it },
    ) {
        "整合包信息包含无效字符"
    }
}

/** 应用远程来源：Modrinth 链接优先，CurseForge 匹配作为下载地址回退与受管清单。 */
private fun withResolvedSource(
    file: MrpackExportFile,
    remoteUrl: String?,
    curseForge: CurseForgeSource?,
): MrpackExportFile =
    when {
        remoteUrl != null ->
            file.copy(
                downloadUrl = remoteUrl,
                curseForgeProjectId = curseForge?.projectId,
                curseForgeFileId = curseForge?.fileId,
                selectedByDefault = true,
                requiresPermission = false,
            )
        curseForge?.downloadUrl != null ->
            file.copy(
                downloadUrl = curseForge.downloadUrl,
                curseForgeProjectId = curseForge.projectId,
                curseForgeFileId = curseForge.fileId,
                selectedByDefault = true,
                requiresPermission = false,
            )
        curseForge != null ->
            file.copy(
                curseForgeProjectId = curseForge.projectId,
                curseForgeFileId = curseForge.fileId,
                selectedByDefault = true,
                requiresPermission = false,
            )
        else -> file
    }

/** CurseForge 匹配结果：编号用于受管清单，下载地址用于 Modrinth 的 files[]。 */
private data class CurseForgeSource(
    val projectId: Long,
    val fileId: Long,
    val downloadUrl: String?,
)

private fun curseForgeSource(info: com.google.gson.JsonObject): CurseForgeSource? {
    val ids = curseForgeIds(info) ?: return null
    val url =
        info.string("downloadUrl").takeIf {
            it.isNotBlank() && allowedMrpackDownload(it)
        }
    return CurseForgeSource(ids.first, ids.second, url)
}

/** 在 Modrinth 返回的候选里找到哈希、大小与下载链接都可信的那一项。 */
private fun matchingRemoteFile(
    version: com.google.gson.JsonObject,
    file: MrpackExportFile,
): com.google.gson.JsonObject? =
    version
        .array("files")
        .firstOrNull {
            it.isJsonObject &&
                it.asJsonObject.let { candidate ->
                    candidate.obj("hashes").string("sha512").equals(file.sha512, true) &&
                        candidate.obj("hashes").string("sha1").equals(file.sha1, true) &&
                        candidate.get("size")?.asLong == file.size &&
                        allowedMrpackDownload(candidate.string("url"))
                }
        }?.asJsonObject

/** 校验扩展名并解析目标绝对路径；不创建任何文件。 */
private fun exportTargetPath(
    format: PackExportFormat,
    destination: Path,
): Path {
    require(destination.extension.equals(format.extension, true)) {
        "此整合包格式需要 .${format.extension} 扩展名"
    }
    return destination.toAbsolutePath().normalize()
}

/** 按勾选与选项过滤实际写入的文件；规则与预览时的默认勾选一致。 */
private fun selectExportFiles(
    plan: MrpackExportPlan,
    selected: Set<String>,
    format: PackExportFormat,
    options: PackExportOptions,
): List<MrpackExportFile> {
    require(selected.all { path -> plan.files.any { it.path == path } }) {
        "导出选择已失效，请重新预览"
    }
    require(selected.isNotEmpty()) { "请至少选择一项要写入的内容" }
    require(
        format == PackExportFormat.Backup || selected.none { backupOnlyExportPath(it) },
    ) {
        "日志与崩溃报告只随完整备份导出"
    }
    val filtered =
        plan.files
            .filter { it.path in selected }
            .filter {
                format == PackExportFormat.Backup &&
                    (!backupOnlyExportPath(it.path) || options.includeLogs) ||
                    format != PackExportFormat.Backup && !backupOnlyExportPath(it.path)
            }.filter { !(options.enabledModsOnly && disabledModExportPath(it.path)) }
    // 关闭远程时强制改为本地写入；即使调用方传入带下载地址的旧快照也不会写出远程清单。
    val files =
        if (!options.remoteResources &&
            (format == PackExportFormat.Modrinth || format == PackExportFormat.CurseForge)
        ) {
            filtered.map(::withoutRemoteSource)
        } else {
            filtered
        }
    require(files.isNotEmpty()) { "勾选的内容没有可写入的文件，请重新选择" }
    return files
}

/** 去掉远程来源并标记为需授权打包，避免关闭远程后仍写下载链接或受管清单。 */
private fun withoutRemoteSource(file: MrpackExportFile): MrpackExportFile =
    file.copy(
        downloadUrl = null,
        curseForgeProjectId = null,
        curseForgeFileId = null,
        selectedByDefault = true,
        requiresPermission = true,
    )

/** 完整备份只留在本机，不需要授权；其它格式要求先确认可分发。 */
private fun requireExportPermissions(
    files: List<MrpackExportFile>,
    permittedLocalFiles: Set<String>,
    format: PackExportFormat,
) {
    val embedded = files.filter { it.embeddedIn(format) }
    val missing =
        if (format == PackExportFormat.Backup) {
            emptyList()
        } else {
            embedded.filter { it.requiresPermission && it.path !in permittedLocalFiles }
        }
    require(missing.isEmpty()) {
        "以下本地资源尚未确认分发权限：${missing.joinToString { it.path }}"
    }
}

/** 原子替换目标文件；文件系统不支持原子移动时退回普通替换。 */
private fun moveFromTemporary(
    temporary: Path,
    target: Path,
) {
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
}

private fun resolveExportDestination(path: Path): Path {
    var ancestor = path
    while (!Files.exists(ancestor, NOFOLLOW_LINKS)) ancestor = requireNotNull(ancestor.parent)
    require(!Files.isSymbolicLink(path)) { "导出目标不能是符号链接" }
    return ancestor.toRealPath().resolve(ancestor.relativize(path)).normalize()
}
