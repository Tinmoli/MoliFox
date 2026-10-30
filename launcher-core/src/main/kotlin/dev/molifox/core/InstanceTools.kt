// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责实例复制、整理和文件账本校验。

package dev.molifox.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.walk

private const val MAX_INSTANCE_NAME_CHARS = 80
private const val MAX_COPY_NAME_SUFFIX = 999

/** 复制扫描每处理这么多文件检查一次取消，兼顾响应速度与开销。 */
private const val COPY_CANCELLATION_INTERVAL = 256

/** 整理实例目录的统计结果；重复模组、残留文件只报告，不删除用户内容。 */
data class TidyReport(
    /** 实际删除的空目录数量。 */
    val removedEmptyDirs: Int,
    /** `mods/` 中 `.jar.disabled` 的归类数量；只统计，不移动（启动器按文件名后缀识别停用）。 */
    val movedDisabledMods: Int,
    /** `mods/` 里既不是 `.jar` 也不是 `.jar.disabled` 的残留文件（相对实例根），只报告。 */
    val orphanFiles: List<String>,
    /** 内容完全相同（同 SHA-1）的模组分组，每组保留一份，其余只报告。 */
    val duplicateMods: List<List<String>>,
    /** 重复模组的多余副本占用的字节数，供用户判断可回收空间。 */
    val totalBytes: Long,
)

/** 按整合包文件归属账本做一致性校验的结果；所有列表都只报告。 */
data class VerifyReport(
    /** 账本登记但磁盘缺失（或不是普通文件）的路径。 */
    val missingCoreFiles: List<String>,
    /** 账本登记但内容哈希不符的路径。 */
    val ledgerMismatches: List<String>,
    /** 磁盘存在、账本未登记的实例文件；日志、缓存、存档等运行数据不计入。 */
    val unknownFiles: List<String>,
    /** 实例是否有 文件归属账本；没有账本时返回空报告。 */
    val hasLedger: Boolean = false,
)

/** 复制时单个文件的来源与目标相对路径（统一正斜杠，与账本一致）。 */
private data class CopyEntry(
    val source: Path,
    val relative: String,
)

/** 复制扫描的固定条件与收集结果，避免单文件过滤函数参数过多。 */
private data class CopyScan(
    val root: Path,
    val realRoot: Path,
    val archive: Path?,
    val copySaves: Boolean,
    val copyMods: Boolean,
    val entries: MutableList<CopyEntry>,
)

private val tidySkipRoots = setOf("logs", "crash-reports", "caches", ".cache")

private val internalInstanceFiles = setOf(".molifox-content.json", ".molifox-classpath.jar")

private val modFilePattern = Regex("(?i).*\\.jar(?:\\.disabled)?")

/** 没有文件归属账本时的空校验报告；内容不可变，可安全复用。 */
private val emptyVerifyReport =
    VerifyReport(
        missingCoreFiles = emptyList(),
        ledgerMismatches = emptyList(),
        unknownFiles = emptyList(),
        hasLedger = false,
    )

/**
 * 实例复制与整理。
 *
 * 约定：
 * - 只接受 MoliFox 自建的隔离实例：共用游戏目录或外部游戏目录版本由原启动器维护， 复制或整理会改变目录语义，因此直接拒绝。
 * - 复制跳过日志、崩溃报告、缓存、升级备份目录、`.fabric/remappedJars` 以及符号链接/目录联接；文件归属账本与整合包归档一并复制，副本独立持有。
 * - 整理默认只删除实例目录内的空目录；重复模组、停用模组、残留文件只统计和报告。
 * - 校验按 账本比对，未知文件只列入报告。
 */
class InstanceTools(
    private val store: LauncherStore,
) {
    /**
     * 把 [sourceId] 复制到独立的新实例目录。
     *
     * 新实例使用新 uuid 与新名称（[name] 为空时取「原名称 - 副本」，重名自动加序号）， 复制安装状态与配置字段，但运行态字段（最后启动时间、游戏时长）从零开始。
     * 取消或失败时删除半成品目录与实例记录；整合包归档缺失时只记录日志，副本作为普通实例保留。
     */
    suspend fun copyInstance(
        sourceId: String,
        name: String?,
        copySaves: Boolean = true,
        copyMods: Boolean = true,
        context: JobContext,
    ): GameInstance =
        withContext(Dispatchers.IO) {
            val source = store.instance(sourceId)
            require(!source.externalProfile) { "外部游戏目录的实例由原启动器维护，无法复制" }
            require(store.isolated(source)) { "共用游戏目录的实例无法复制，请先启用版本隔离" }
            val sourceRoot = store.instancePath(sourceId)
            require(sourceRoot.isDirectory()) { "实例目录不存在，无法复制" }
            val requestedName = name?.trim().orEmpty().ifBlank { "${source.name} - 副本" }
            require(requestedName.length <= MAX_INSTANCE_NAME_CHARS) { "实例名称需要 1–80 个字符" }
            val targetName = uniqueName(requestedName)
            val targetId = UUID.randomUUID().toString()
            val directoryName =
                VersionGameDirectories(store).availableName(store.minecraftRoot(source), targetName)
            var targetRoot: Path? = null
            runCatching {
                registerCopiedInstance(source, targetId, directoryName, targetName)
                val destination = store.instancePath(targetId)
                require(!destination.exists(LinkOption.NOFOLLOW_LINKS)) { "目标实例目录已存在" }
                targetRoot = destination
                val entries = collectCopyEntries(sourceRoot, source, copySaves, copyMods, context)
                context.progress(0f, "复制实例 · 文件 0/${entries.size}")
                copyEntriesTo(destination, entries, context)
                finishCopiedInstance(source, targetId, targetName, context)
                store.instance(targetId)
            }.getOrElse { error ->
                // 复制链路总出口：取消、校验失败与 IO 异常都要先清理半成品再原样抛出，不能按类型收窄。
                targetRoot?.let { runCatching { deleteInstanceFiles(it) } }
                runCatching {
                    store.mutate { data ->
                        data.copy(instances = data.instances.filter { it.id != targetId })
                    }
                }
                throw error
            }
        }

    /** 先在账本里登记副本记录（安装状态与整合包字段由后续步骤补齐），失败时由调用方回滚。 */
    private fun registerCopiedInstance(
        source: GameInstance,
        targetId: String,
        directoryName: String,
        targetName: String,
    ) {
        store.mutate {
            it.copy(
                instances =
                    it.instances +
                        source.copy(
                            id = targetId,
                            gameDirectoryName = directoryName,
                            name = targetName,
                            createdAt = Instant.now().toString(),
                            lastPlayed = null,
                            playTimeSeconds = 0,
                            installed = false,
                            packFile = "",
                        ),
            )
        }
    }

    /** 复制已统计的文件条目；每个文件前检查取消，进度文案与「文件 i/n」保持一致。 */
    private suspend fun copyEntriesTo(
        destination: Path,
        entries: List<CopyEntry>,
        context: JobContext,
    ) {
        entries.forEachIndexed { index, entry ->
            context.checkCancelled()
            val file = safePath(destination, entry.relative)
            file.parent?.createDirectories()
            Files.copy(entry.source, file, StandardCopyOption.REPLACE_EXISTING)
            context.progress(
                (index + 1).toFloat() / entries.size,
                "复制实例 · 文件 ${index + 1}/${entries.size}",
            )
        }
    }

    /** 复制整合包归档，回填安装状态与版本隔离配置，最后上报完成进度并记录日志。 */
    private suspend fun finishCopiedInstance(
        source: GameInstance,
        targetId: String,
        targetName: String,
        context: JobContext,
    ) {
        val archive = copyPackArchive(source, targetId, context)
        store.updateInstance(targetId) {
            it.copy(installed = source.installed, packFile = archive.orEmpty())
        }
        VersionGameDirectories(store).writeProfile(store.instance(targetId))
        context.progress(1f, "复制实例完成")
        store.log("已复制实例：${source.name} → $targetName")
    }

    /**
     * 整理实例目录：删除空目录，统计重复模组、停用模组与残留文件。
     *
     * 只删除实例根之内的空目录，不跟随符号链接或目录联接，也不会删除实例根本身； 重复模组等危险动作不默认执行，仅在报告中列出。
     */
    fun tidyInstance(
        instanceId: String,
        removeEmptyDirs: Boolean = true,
    ): TidyReport {
        val instance = store.instance(instanceId)
        require(store.isolated(instance)) { "共用游戏目录的实例无法整理，请先启用版本隔离" }
        val root = store.instancePath(instanceId)
        require(root.isDirectory()) { "实例目录不存在，无法整理" }
        val mods = root.resolve("mods")
        val modFiles = listModFiles(mods)
        val disabledMods = modFiles.count { it.name.endsWith(".disabled", true) }
        val orphans = modFiles.filterNot { modFilePattern.matches(it.name) }
        val duplicates = duplicateModNames(modFiles)
        val totalBytes = duplicateBytes(mods, duplicates)
        val removedEmptyDirs = if (removeEmptyDirs) removeEmptyDirectories(root) else 0
        return TidyReport(
            removedEmptyDirs = removedEmptyDirs,
            movedDisabledMods = disabledMods,
            orphanFiles = orphans.map { "mods/${it.name}" }.sorted(),
            duplicateMods = duplicates,
            totalBytes = totalBytes,
        )
    }

    /**
     * 用 文件归属账本校验实例内容。
     *
     * 没有账本的实例（老实例或非整合包实例）返回空报告并置 [VerifyReport.hasLedger] 为 false；未知文件只报告，不做任何删除。
     */
    fun verifyInstance(instanceId: String): VerifyReport {
        val instance = store.instance(instanceId)
        require(store.isolated(instance)) { "共用游戏目录的实例无法校验，请先启用版本隔离" }
        val root = store.instancePath(instanceId)
        require(root.isDirectory()) { "实例目录不存在，无法校验" }
        val ownership = PackOwnershipStore.read(root, store) ?: return emptyVerifyReport
        val known = ownership.files.map { packRelative(it.path) }.toSet()
        val (missing, mismatched) = verifyOwnedFiles(root, ownership.files)
        val unknown = collectUnknownFiles(root, known)
        return VerifyReport(
            missingCoreFiles = missing.sorted(),
            ledgerMismatches = mismatched.sorted(),
            unknownFiles = unknown.sorted(),
            hasLedger = true,
        )
    }

    /** 同名实例自动加序号，避免两个实例在列表中无法区分。 */
    private fun uniqueName(base: String): String {
        val taken =
            store.data.instances
                .map { it.name }
                .toSet()
        if (base !in taken) return base
        for (index in 2..MAX_COPY_NAME_SUFFIX) {
            val candidate = "$base $index"
            if (candidate.length <= MAX_INSTANCE_NAME_CHARS && candidate !in taken) return candidate
        }
        error("同名实例过多，请改用其他名称")
    }

    /** 复制整合包归档到数据目录的 packs/ 下，返回新路径；原归档缺失时只记录日志。 */
    private suspend fun copyPackArchive(
        source: GameInstance,
        targetId: String,
        context: JobContext,
    ): String? {
        if (source.packFile.isBlank()) return null
        val archive = runCatching { Path.of(source.packFile) }.getOrNull()
        if (archive == null || !archive.isRegularFile() || Files.isSymbolicLink(archive)) {
            store.log("原实例的整合包归档缺失，副本未记录整合包：${source.packFile}", true)
            return null
        }
        context.checkCancelled()
        val extension = if (archive.name.endsWith(".mrpack", true)) "mrpack" else "zip"
        val destination = store.directories.packArchives.resolve("$targetId.$extension")
        destination.parent.createDirectories()
        val part = destination.resolveSibling("${destination.fileName}.part")
        try {
            Files.copy(archive, part, StandardCopyOption.REPLACE_EXISTING)
            Files.move(part, destination, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            part.deleteIfExists()
        }
        return destination.toString()
    }

    /** 先统计要复制的文件，进度才能给出「文件 i/n」；跳过运行数据、缓存和链接。 */
    private suspend fun collectCopyEntries(
        sourceRoot: Path,
        source: GameInstance,
        copySaves: Boolean,
        copyMods: Boolean,
        context: JobContext,
    ): List<CopyEntry> {
        val root = sourceRoot.toAbsolutePath().normalize()
        val realRoot = root.toRealPath()
        val archive =
            source.packFile
                .takeIf { it.isNotBlank() }
                ?.let { runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull() }
        val entries = mutableListOf<CopyEntry>()
        val scan = CopyScan(root, realRoot, archive, copySaves, copyMods, entries)
        var visited = 0
        Files.walk(root).use { paths ->
            for (path in paths) {
                if (++visited % COPY_CANCELLATION_INTERVAL == 0) context.checkCancelled()
                collectCopyEntry(path, scan)
            }
        }
        return entries.sortedBy { it.relative }
    }

    /** 由深到浅删除实例根内的空目录；符号链接、目录联接和实例根本身都不删。 */
    private fun removeEmptyDirectories(root: Path): Int {
        val realRoot = root.toRealPath()
        val candidates = mutableListOf<Path>()
        Files.walk(root).use { paths ->
            paths
                .filter { path ->
                    path != root &&
                        !Files.isSymbolicLink(path) &&
                        Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                }.filter { path ->
                    runCatching { path.toRealPath() }.getOrNull()?.startsWith(realRoot) == true
                }.forEach { path -> candidates.add(path) }
        }
        var removed = 0
        for (path in candidates.sortedByDescending { it.nameCount }) {
            if (path == root || !path.startsWith(root)) continue
            val empty = runCatching { path.listDirectoryEntries().isEmpty() }.getOrDefault(false)
            if (empty && runCatching { Files.deleteIfExists(path) }.getOrDefault(false)) removed++
        }
        return removed
    }

    /** 清理复制失败的半成品目录；不跟随符号链接或目录联接。 */
    private fun deleteInstanceFiles(directory: Path) {
        if (!directory.exists(LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isSymbolicLink(directory)) {
            Files.deleteIfExists(directory)
            return
        }
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}

/** `mods/` 下的普通文件；目录是链接/联接或条目是链接时不参与统计。 */
private fun listModFiles(mods: Path): List<Path> {
    val eligible = mods.isDirectory(LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(mods)
    if (!eligible) return emptyList()
    return mods.listDirectoryEntries().filter {
        it.isRegularFile(LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it)
    }
}

/** 按 SHA-1 分组，返回内容完全相同且每组多于一份的模组名（组内与组间均已排序）。 */
private fun duplicateModNames(modFiles: List<Path>): List<List<String>> {
    val hashes =
        modFiles.mapNotNull { file ->
            runCatching { digest(file) }.getOrNull()?.lowercase()?.let { it to file }
        }
    return hashes
        .groupBy({ it.first }, { it.second })
        .values
        .filter { it.size > 1 }
        .map { group -> group.map { it.name }.sorted() }
        .sortedBy { it.first() }
}

/** 重复模组多余副本占用的字节数；单个文件读取失败按 0 计算。 */
private fun duplicateBytes(
    mods: Path,
    duplicates: List<List<String>>,
): Long =
    duplicates.sumOf { group ->
        group.drop(1).sumOf { name ->
            runCatching { mods.resolve(name).fileSize() }.getOrDefault(0L)
        }
    }

/** 逐条比对账本文件：缺失（含非普通文件与链接）和哈希不符分别收集，顺序与账本一致。 */
private fun verifyOwnedFiles(
    root: Path,
    files: List<PackOwnedFile>,
): Pair<List<String>, List<String>> {
    val missing = mutableListOf<String>()
    val mismatched = mutableListOf<String>()
    for (file in files) {
        val target = runCatching { safePath(root, file.path) }.getOrNull()
        val notRegularFile =
            target == null ||
                !target.isRegularFile(LinkOption.NOFOLLOW_LINKS) ||
                Files.isSymbolicLink(target)
        if (notRegularFile) {
            missing.add(file.path)
            continue
        }
        val actual = runCatching { digest(target) }.getOrNull()
        if (actual == null || !actual.equals(file.sha1, true)) mismatched.add(file.path)
    }
    return missing to mismatched
}

/** 实例内账本未登记、且不属于运行数据的普通文件（相对实例根，未排序）。 */
private fun collectUnknownFiles(
    root: Path,
    known: Set<String>,
): List<String> {
    val realRoot = root.toRealPath()
    val unknown = mutableListOf<String>()
    Files.walk(root).use { paths ->
        paths
            .filter { path ->
                path != root &&
                    !Files.isSymbolicLink(path) &&
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            }.map { path -> path to packRelative(root.relativize(path).toString()) }
            .filter { (path, relative) ->
                relative !in setOf("${root.fileName}.json", "${root.fileName}.jar") &&
                    relative !in known &&
                    !skipForVerify(relative) &&
                    runCatching { path.toRealPath() }.getOrNull()?.startsWith(realRoot) == true
            }.forEach { (_, relative) -> unknown.add(relative) }
    }
    return unknown
}

/** 单个候选路径的过滤规则；不满足条件时直接跳过，不参与复制。 */
private fun collectCopyEntry(
    path: Path,
    scan: CopyScan,
) {
    if (path == scan.root) return
    if (Files.isSymbolicLink(path)) return
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return
    val relative = packRelative(scan.root.relativize(path).toString())
    if (skipForCopy(relative, scan.copySaves, scan.copyMods)) return
    if (scan.archive != null && path.toAbsolutePath().normalize() == scan.archive) return
    if (runCatching { path.toRealPath() }.getOrNull()?.startsWith(scan.realRoot) != true) return
    scan.entries.add(CopyEntry(path, relative))
}

/** 复制排除规则：只看相对路径，避免不同磁盘上绝对路径差异影响判断。 */
private fun skipForCopy(
    relative: String,
    copySaves: Boolean,
    copyMods: Boolean,
): Boolean {
    val first = relative.substringBefore('/')
    if (first == ".molifox-saves-backups") return true
    if (first in tidySkipRoots) return true
    if (first.startsWith(".molifox-pack-upgrade-")) return true
    if (!copyMods && first == "mods") return true
    if (!copySaves && first == "saves") return true
    return relative.startsWith(".fabric/remappedJars") || relative.contains("/remappedJars/")
}

/** 账本之外的实例文件里，日志、缓存、存档、原版备份和启动器自身文件不算「未知文件」。 */
private fun skipForVerify(relative: String): Boolean {
    val first = relative.substringBefore('/')
    if (first == ".molifox-saves-backups") return true
    if (first in tidySkipRoots || first == "saves") return true
    // 备份与原版游戏共用 `backups/`，不属于整合包账本。
    if (first == "backups") return true
    if (first.startsWith(".molifox-pack-upgrade-")) return true
    if (relative == PACK_OWNERSHIP_FILE || relative in internalInstanceFiles) return true
    return relative.startsWith(".fabric/remappedJars") || relative.contains("/remappedJars/")
}
