// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责存档列表、备份、恢复、复制和回收。

package dev.molifox.core

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipFile as ArchiveZipFile

/** `saves/` 下的一个直接子项：世界目录，或用户自己放的 `.zip` 存档包。 */
data class SaveEntry(
    /** 目录名 / 文件名（含 `.zip` 后缀）。 */
    val name: String,
    val path: Path,
    /** 目录按其中普通文件的总字节数计算，`.zip` 按文件大小计算。 */
    val size: Long,
    val lastModified: Instant?,
    /** 目录内或 `.zip` 内是否有 `level.dat`，据此判断「像不像一个世界」。 */
    val hasLevelDat: Boolean,
    /** 是否位于备份区；[SaveManager.listSaves] 只列 `saves/`，因此恒为 false。 */
    val backup: Boolean,
)

/**
 * `backups/` 里的一个存档压缩包：可能是 MoliFox 写的，也可能是原版游戏写的。
 *
 * 原版命名是 `yyyy-MM-dd_HH-mm-ss_<世界名>.zip`，世界名与时间都从文件名解析； 不满足该命名的压缩包按文件名显示，时间退回文件修改时间。
 */
data class SaveBackupInfo(
    /** 备份标识，等于压缩包去掉 `.zip` 的文件名；恢复与删除都以它为句柄。 */
    val id: String,
    /** 世界名：原版命名里最后一段，其他命名直接取去掉后缀的文件名。 */
    val saveName: String,
    val archive: Path,
    val createdAt: Instant,
    val size: Long,
    /** 压缩包根目录是否有 `level.dat`；false 时不在界面提供恢复。 */
    val restorable: Boolean,
)

/** 原版备份目录名：`<游戏目录>/backups/`，与原版游戏共用同一个目录。 */
private const val SAVE_BACKUP_DIRECTORY = "backups"

/** 临时目录前缀：解压与复制都先落在 `saves/` 内的隐藏目录，成功后一次改名。 */
private const val SAVE_STAGING_PREFIX = ".molifox-saves-"

private const val MAX_SAVE_ENTRIES = 100_000
private const val MAX_SAVE_BYTES = 64L * 1024 * 1024 * 1024
private const val MAX_SAVE_ENTRY_BYTES = 32L * 1024 * 1024 * 1024

/** 原版备份文件名：`yyyy-MM-dd_HH-mm-ss_<世界名>.zip`。 */
private val saveBackupStamp =
    DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.systemDefault())

/** 备份标识 = `<时间戳>_<世界名>`；第 1 组是时间戳、第 2 组是世界名。 */
private val saveBackupIdPattern = Regex("^(\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2})_(.+)$")

/** 反向解析备份创建时间用的同一套格式（本地时区）。 */
private val parseBackupStamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

/**
 * 恢复出来的同级世界目录后缀：`<世界名>（备份 yyyy-MM-dd HH-mm）`。
 *
 * Windows 文件名不允许冒号，所以时间用 `HH-mm` 而不是 `HH:mm`；界面展示与实际目录名保持一致。
 */
private val restoreSuffixFormat =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm").withZone(ZoneId.systemDefault())

/** 替换时保留的旧世界后缀：`<世界名>（替换前 HH-mm）`，同样避开冒号。 */
private val replaceSuffixFormat =
    DateTimeFormatter.ofPattern("HH-mm").withZone(ZoneId.systemDefault())

/**
 * 存档管理：列出、备份、恢复与跨实例复制 `saves/` 下的世界。
 *
 * 约定：
 * - 只操作实例 `saves/` 的直接子项，目录与 `.zip` 都算存档；不跟随符号链接/目录联接， 损坏或不可读的子项只跳过，不抛异常。
 * - 备份写到 `<游戏目录>/backups/<yyyy-MM-dd_HH-mm-ss>_<世界名>.zip`（与原版游戏同名同目录）， 先写 `.part` 再原子改名；备份**保留原存档**，不移动、不删除用户内容。
 * - 恢复默认是**非破坏性复制**：解压到 `saves/<世界名>（备份 时间）/`，跳过 `session.lock`， 已存在同名目录时加数字后缀，从不改动现有世界。
 * - 开启实例设置「恢复时替换原世界」后走替换流程：先备份当前世界 → 解压到 `saves/` 内的临时目录 → 校验 level.dat 与非空区域文件 →
 *   把当前世界改名为 `<世界名>（替换前 HH-mm）` 保留 → 临时目录改名就位；任一步失败都删除临时目录并把旧世界放回原位。
 * - 世界目录里 `session.lock` 被占用（游戏正在打开该世界）时拒绝恢复与替换。
 * - 跨实例复制只接受 MoliFox 自建的版本隔离实例；目标已有同名存档时**自动加序号** （`World` → `World 2`），从不覆盖目标实例的现有存档。
 * - 导入外部存档（[importSave]）只接受带 `level.dat` 的 `.zip` 或世界文件夹；ZIP 允许唯一一层包装目录，
 *   重名加 ` (2)` 序号，先校验后解压，失败不留残留。
 * - 删除备份与删除存档走 [PlatformServices.moveToTrash]，不做不可恢复的硬删除。
 */
class SaveManager(private val store: LauncherStore) {

    /**
     * 列出实例 `saves/` 下的直接子项。
     *
     * `saves/` 不存在时返回空列表；程序目录、非 `.zip` 文件、符号链接与目录联接都不列出； 单个子项读不出属性时只跳过它。结果按名称排序（忽略大小写）。
     */
    fun listSaves(instanceId: String): List<SaveEntry> {
        store.instance(instanceId)
        val saves = savesDirectory(instanceId)
        if (!saves.isDirectory(LinkOption.NOFOLLOW_LINKS) || isLink(saves)) return emptyList()
        val children = runCatching {
            saves.listDirectoryEntries()
        }
            .getOrElse {
                store.log("读取存档目录失败：${it.message ?: saves}", true)
                return emptyList()
            }
        return children
            .mapNotNull { child -> runCatching { describeSave(child) }.getOrNull() }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    /**
     * 列出 `backups/` 里的全部压缩包，按创建时间从新到旧。
     *
     * 与原版游戏共用同一个目录，因此原版游戏自己写的备份也会出现在这里；世界名与时间从 原版文件名
     * `yyyy-MM-dd_HH-mm-ss_<世界名>.zip` 解析，不符合命名的压缩包按文件名显示、时间退回 修改时间。只读，不修改磁盘。
     */
    fun listBackups(instanceId: String): List<SaveBackupInfo> {
        store.instance(instanceId)
        val directory = backupDirectory(instanceId)
        if (!directory.isDirectory(LinkOption.NOFOLLOW_LINKS) || isLink(directory))
            return emptyList()
        val children = runCatching {
            directory.listDirectoryEntries()
        }
            .getOrElse {
                return emptyList()
            }
        return children
            .filter {
                it.isRegularFile(LinkOption.NOFOLLOW_LINKS) &&
                    !isLink(it) &&
                    it.name.endsWith(".zip", true)
            }
            .mapNotNull { archive -> runCatching { describeBackup(archive) }.getOrNull() }
            .sortedByDescending { it.createdAt }
    }

    /**
     * 把 `saves/<saveName>` 打包到 `<游戏目录>/backups/`，返回备份信息。
     *
     * 文件名用原版命名 `yyyy-MM-dd_HH-mm-ss_<世界名>.zip`；世界目录按内容打包（跳过符号链接与越界路径）， `saves/`
     * 下现成的 `.zip` 存档直接复制；成功与否都保留原存档。取消或失败时删除 `.part`，不留下半个压缩包。
     */
    suspend fun backupSave(
        instanceId: String,
        saveName: String,
        context: JobContext? = null,
    ): SaveBackupInfo =
        withContext(Dispatchers.IO) {
            val instance = store.instance(instanceId)
            requireGameStopped(instanceId)
            val saves = savesDirectory(instanceId)
            require(saves.isDirectory(LinkOption.NOFOLLOW_LINKS) && !isLink(saves)) {
                "存档目录不存在，无法备份"
            }
            val source = saveSource(saves, saveName)
            val label = safeSaveLabel(source.name.removeSuffixIgnoreCase(".zip"))
            context?.progress(0f, "备份存档 · $saveName")
            val info = packSave(instanceId, source, label, context, "备份存档")
            context?.progress(1f, "备份存档完成 · ${info.archive.name}")
            store.log("已备份存档：${instance.name} / $saveName → ${info.archive.name}")
            info
        }

    /**
     * 把备份解压成 `saves/` 下的**新世界目录**，返回新目录路径；从不改动现有世界。
     *
     * 目录名是 `<世界名>（备份 yyyy-MM-dd HH-mm）`，已存在时加数字后缀；解压跳过 `session.lock`，
     * 解压后校验根目录的 `level.dat` 存在且非空，失败则删除临时目录。压缩包没有 `level.dat`、 或该世界正被游戏打开时拒绝。
     */
    suspend fun restoreSave(
        instanceId: String,
        backupId: String,
        context: JobContext? = null,
    ): Path =
        withContext(Dispatchers.IO) {
            val instance = store.instance(instanceId)
            requireGameStopped(instanceId)
            val saves = savesDirectory(instanceId)
            require(saves.isDirectory(LinkOption.NOFOLLOW_LINKS) && !isLink(saves)) {
                "存档目录不存在，无法恢复"
            }
            requireSavesNotLocked(instanceId)
            val archive = resolveBackup(instanceId, backupId)
            val info = describeBackup(archive) ?: error("备份文件名无法识别，不能自动恢复：$backupId")
            require(info.restorable) { "备份包里没有 level.dat，无法恢复：${archive.name}" }
            val base =
                safeSaveLabel(info.saveName).take(40) +
                    "（备份 " +
                    restoreSuffixFormat.format(info.createdAt) +
                    "）"
            val name = uniqueSaveName(saves, base, isZip = false)
            val target = saveTarget(saves, name)
            require(!target.exists(LinkOption.NOFOLLOW_LINKS)) { "目标存档已存在：$name" }
            val staging = safePath(saves, "$SAVE_STAGING_PREFIX${UUID.randomUUID()}")
            try {
                extractArchive(archive, staging, context, skipSessionLock = true)
                requireRestorableWorld(staging, "备份包")
                moveIntoPlace(staging, target)
            } catch (error: Throwable) {
                runCatching { deleteTree(staging) }
                throw error
            }
            context?.progress(1f, "恢复存档完成 · $name")
            store.log("已恢复存档：${instance.name} / $name ← ${archive.name}")
            target
        }

    /**
     * 用备份**替换**现有世界（实例设置「恢复时替换原世界」开启时使用），返回新世界路径。
     *
     * 步骤：先把当前世界备份到 `backups/` → 解压到 `saves/` 内的临时目录 → 校验 level.dat 与非空区域文件 →
     * 当前世界改名为 `<世界名>（替换前 HH-mm）` 保留 → 临时目录改名就位。任一步失败都删除临时目录， 并把已改名的旧世界放回原位；原世界数据不会丢。
     */
    suspend fun replaceWorldWithBackup(
        instanceId: String,
        backupId: String,
        context: JobContext? = null,
    ): Path =
        withContext(Dispatchers.IO) {
            val instance = store.instance(instanceId)
            requireGameStopped(instanceId)
            val saves = savesDirectory(instanceId)
            require(saves.isDirectory(LinkOption.NOFOLLOW_LINKS) && !isLink(saves)) {
                "存档目录不存在，无法恢复"
            }
            val archive = resolveBackup(instanceId, backupId)
            val info = describeBackup(archive) ?: error("备份文件名无法识别，不能自动恢复：$backupId")
            require(info.restorable) { "备份包里没有 level.dat，无法恢复：${archive.name}" }
            val name = safeSaveLabel(info.saveName)
            val target = saveTarget(saves, name)
            require(!isLink(target)) { "目标存档是符号链接或目录联接，未处理：$name" }
            // 原世界不存在时「替换」没有意义，直接按非破坏性复制走一遍。
            if (!target.exists(LinkOption.NOFOLLOW_LINKS)) {
                return@withContext restoreSave(instanceId, backupId, context)
            }
            require(target.isDirectory(LinkOption.NOFOLLOW_LINKS)) { "存档「$name」不是目录，未替换" }
            requireWorldClosed(target)
            // 1) 先备份当前世界；备份失败时后面一步都还没做，原世界保持原样。
            context?.progress(0f, "替换前备份存档 · $name")
            val previous =
                packSave(instanceId, target, safeSaveLabel(name), context, "替换前备份存档")
            store.log("替换前已备份当前世界：${instance.name} / $name → ${previous.archive.name}")
            // 2) 解压到 saves/ 内的临时目录。
            val staging = safePath(saves, "$SAVE_STAGING_PREFIX${UUID.randomUUID()}")
            var kept: Path? = null
            try {
                extractArchive(archive, staging, context, skipSessionLock = true)
                requireReplaceableWorld(staging)
                // 3) 当前世界改名保留，名字带「替换前 HH:mm」，不删除。
                val keptName =
                    uniqueSaveName(
                        saves,
                        "$name（替换前 ${replaceSuffixFormat.format(Instant.now())}）",
                        isZip = false,
                    )
                val keptPath = saveTarget(saves, keptName)
                moveIntoPlace(target, keptPath)
                kept = keptPath
                // 4) 临时目录改名就位。
                moveIntoPlace(staging, target)
            } catch (error: Throwable) {
                val failures = mutableListOf<Throwable>()
                runCatching { deleteTree(staging) }.onFailure { failures += it }
                val previousWorld = kept
                if (previousWorld != null) {
                    runCatching {
                        if (target.exists(LinkOption.NOFOLLOW_LINKS)) deleteTree(target)
                        moveIntoPlace(previousWorld, target)
                    }
                        .onFailure {
                            failures += it
                            val failure =
                                IOException(
                                    "替换存档失败，且旧世界没有放回原位：请手动把 ${previousWorld.fileName} 改回 $name，备份在 ${previous.archive.name}",
                                    error,
                                )
                            failures.forEach { failure.addSuppressed(it) }
                            throw failure
                        }
                }
                if (failures.isNotEmpty()) {
                    val failure = IOException("替换存档失败，清理未完成：请检查 $saves", error)
                    failures.forEach { failure.addSuppressed(it) }
                    throw failure
                }
                throw error
            }
            context?.progress(1f, "恢复存档完成 · $name")
            store.log(
                "已用备份替换存档：${instance.name} / $name ← ${archive.name}；旧世界保留为 ${kept.fileName}"
            )
            target
        }

    /**
     * 把源实例的存档复制到目标实例，返回目标路径。
     *
     * 目标已有同名存档时自动加序号（`World` → `World 2`），从不覆盖；复制先写目标 `saves/` 内的隐藏临时目录再原子改名，失败不污染目标。两个实例都必须是
     * MoliFox 自建的版本隔离 实例，外部游戏目录的实例由原启动器维护，直接拒绝。
     */
    suspend fun copySaveToInstance(
        sourceInstanceId: String,
        saveName: String,
        targetInstanceId: String,
        rename: String? = null,
        context: JobContext? = null,
    ): Path =
        withContext(Dispatchers.IO) {
            require(sourceInstanceId != targetInstanceId) { "源实例与目标实例相同，请选择另一个实例" }
            val source = store.instance(sourceInstanceId)
            val destination = store.instance(targetInstanceId)
            requireGameStopped(sourceInstanceId, targetInstanceId)
            require(
                taskPathKey(store.gamePath(source)) != taskPathKey(store.gamePath(destination))
            ) {
                "源和目标使用同一个存档目录，无需复制"
            }
            val sourceSaves = savesDirectory(sourceInstanceId)
            require(sourceSaves.isDirectory(LinkOption.NOFOLLOW_LINKS) && !isLink(sourceSaves)) {
                "源实例的存档目录不存在，无法复制"
            }
            val sourcePath = saveSource(sourceSaves, saveName)
            val targetSaves = savesDirectory(targetInstanceId)
            require(!isLink(targetSaves)) { "目标实例的存档目录是符号链接或目录联接，无法复制" }
            targetSaves.createDirectories()
            val isZip = !sourcePath.isDirectory(LinkOption.NOFOLLOW_LINKS)
            // 未指定新名称时沿用存档名：目录名，或去掉 `.zip` 的文件名（`uniqueSaveName` 会补回后缀）。
            val defaultName = sourcePath.name.removeSuffixIgnoreCase(".zip")
            val requested = rename?.trim().orEmpty().ifBlank { defaultName }
            require(requested.length <= 80) { "存档名称需要 1–80 个字符" }
            val base = if (isZip) requested.removeSuffixIgnoreCase(".zip") else requested
            val finalName = uniqueSaveName(targetSaves, base, isZip)
            val target =
                safePath(targetSaves, finalName).also {
                    require(it.parent.toAbsolutePath().normalize() == absolute(targetSaves)) {
                        "存档名称无效：$finalName"
                    }
                }
            require(!target.exists(LinkOption.NOFOLLOW_LINKS)) { "目标存档已存在：$finalName" }
            val staging = safePath(targetSaves, "$SAVE_STAGING_PREFIX${UUID.randomUUID()}")
            try {
                if (isZip) Files.copy(sourcePath, staging)
                else copySaveTree(sourcePath, staging, context)
                moveIntoPlace(staging, target)
            } catch (error: Throwable) {
                runCatching { deleteTree(staging) }
                throw error
            }
            context?.progress(1f, "复制存档完成 · $finalName")
            store.log("已复制存档：${source.name} / $saveName → ${destination.name} / $finalName")
            target
        }

    /**
     * 导入一个外部存档：`.zip` 或世界文件夹，返回 `saves/` 下的新世界路径。
     *
     * ZIP 必须在根目录或唯一一层包装目录里有 `level.dat`（[SaveArchive] 先只读校验，无效输入不写盘），
     * 解压时跳过 `session.lock` 并剥掉包装目录；文件夹直接复制。目标重名时加序号（`World (2)`）， 从不覆盖现有世界；解压先落 `saves/` 内的隐藏临时目录，失败删除，不留残留。
     */
    suspend fun importSave(
        instanceId: String,
        source: Path,
        context: JobContext? = null,
    ): Path =
        withContext(Dispatchers.IO) {
            val instance = store.instance(instanceId)
            requireGameStopped(instanceId)
            val saves = savesDirectory(instanceId)
            require(!isLink(saves)) { "存档目录是符号链接或目录联接，无法导入" }
            saves.createDirectories()
            val incoming = source.toAbsolutePath().normalize()
            require(incoming.exists(LinkOption.NOFOLLOW_LINKS) && !isLink(incoming)) {
                "找不到要导入的存档：${source.fileName}"
            }
            val isDirectory = incoming.isDirectory(LinkOption.NOFOLLOW_LINKS)
            val prefix: String
            val base: String
            if (isDirectory) {
                val level = incoming.resolve("level.dat")
                require(level.isRegularFile(LinkOption.NOFOLLOW_LINKS) && !isLink(level)) {
                    "文件夹里没有 level.dat，不是有效的世界：${incoming.fileName}"
                }
                prefix = ""
                base = safeSaveLabel(incoming.name)
            } else {
                require(incoming.isRegularFile(LinkOption.NOFOLLOW_LINKS) && incoming.name.endsWith(".zip", true)) {
                    "存档只能导入 .zip 或世界文件夹：${incoming.fileName}"
                }
                prefix = SaveArchive.inspect(incoming).prefix
                base = safeSaveLabel(incoming.name.removeSuffixIgnoreCase(".zip"))
            }
            context?.progress(0f, "导入存档 · ${incoming.fileName}")
            val name = uniqueChildName(saves, base)
            val target = saveTarget(saves, name)
            require(!target.exists(LinkOption.NOFOLLOW_LINKS)) { "目标存档已存在：$name" }
            val staging = safePath(saves, "$SAVE_STAGING_PREFIX${UUID.randomUUID()}")
            try {
                if (isDirectory) copySaveTree(incoming, staging, context)
                else
                    extractArchive(
                        incoming,
                        staging,
                        context,
                        skipSessionLock = true,
                        stripPrefix = prefix,
                    )
                requireRestorableWorld(staging, "导入的存档")
                moveIntoPlace(staging, target)
            } catch (error: Throwable) {
                runCatching { deleteTree(staging) }
                throw error
            }
            context?.progress(1f, "导入存档完成 · $name")
            store.log("已导入存档：${instance.name} / $name ← ${incoming.fileName}")
            target
        }

    /** 删除一个备份：移入回收站，不硬删；找不到或回收站失败返回 false。 */
    fun deleteBackup(instanceId: String, backupId: String): Boolean {
        store.instance(instanceId)
        val archive = runCatching { resolveBackup(instanceId, backupId) }.getOrNull()
        if (archive == null) return false
        return runCatching {
            store.platform.moveToTrash(archive)
            store.log("存档备份已移入回收站：${archive.name}")
            true
        }
            .getOrElse {
                store.log("删除存档备份失败：${it.message ?: backupId}", true)
                false
            }
    }

    /** 删除一个存档：移入回收站，不硬删；找不到或回收站失败返回 false。 */
    fun deleteSave(instanceId: String, saveName: String): Boolean {
        val instance = store.instance(instanceId)
        requireGameStopped(instanceId)
        val saves = savesDirectory(instanceId)
        val path = runCatching { saveSource(saves, saveName) }.getOrNull() ?: return false
        return runCatching {
            store.platform.moveToTrash(path)
            store.log("存档已移入回收站：${instance.name} / $saveName")
            true
        }
            .getOrElse {
                store.log("删除存档失败：${it.message ?: saveName}", true)
                false
            }
    }

    private fun requireGameStopped(vararg ids: String) {
        val directories = ids.map { taskPathKey(store.instancePath(it)) }.toSet()
        require(
            store.state.value.runningGames.none {
                taskPathKey(store.instancePath(it.instanceId)) in directories
            }
        ) {
            "游戏正在使用此存档目录，请先退出游戏"
        }
    }

    private fun savesDirectory(instanceId: String): Path =
        store.instancePath(instanceId).resolve("saves")

    private fun backupDirectory(instanceId: String): Path =
        store.instancePath(instanceId).resolve(SAVE_BACKUP_DIRECTORY)

    /** 把一个直接子项描述成 [SaveEntry]；不认识的类型、链接与读不出属性的子项返回 null。 */
    private fun describeSave(path: Path): SaveEntry? {
        val attributes =
            runCatching {
                Files.readAttributes(
                    path,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
            }
                .getOrNull() ?: return null
        // 符号链接与目录联接（Windows junction）都不列出，避免把实例外的世界当成存档。
        if (attributes.isSymbolicLink || attributes.isOther) return null
        val name = path.fileName?.toString() ?: return null
        val modified = runCatching { path.getLastModifiedTime().toInstant() }.getOrNull()
        return when {
            attributes.isDirectory ->
                SaveEntry(
                    name = name,
                    path = path,
                    size = directorySize(path),
                    lastModified = modified,
                    hasLevelDat =
                        path.resolve("level.dat").isRegularFile(LinkOption.NOFOLLOW_LINKS) &&
                            !Files.isSymbolicLink(path.resolve("level.dat")),
                    backup = false,
                )
            attributes.isRegularFile && name.endsWith(".zip", true) ->
                SaveEntry(
                    name = name,
                    path = path,
                    size = runCatching { path.fileSize() }.getOrDefault(0L),
                    lastModified = modified,
                    hasLevelDat = zipHasLevelDat(path),
                    backup = false,
                )
            else -> null
        }
    }

    /** 备份压缩包 -> 展示信息：优先按原版文件名解析世界名与时间，否则退回文件名与修改时间。 */
    private fun describeBackup(archive: Path): SaveBackupInfo? {
        val id = archive.name.removeSuffixIgnoreCase(".zip")
        require(id.isNotBlank())
        val parsed = parseBackupId(id)
        return SaveBackupInfo(
            id = id,
            saveName = parsed?.second ?: id,
            archive = archive,
            createdAt =
                parsed?.first
                    ?: runCatching { archive.getLastModifiedTime().toInstant() }.getOrNull()
                    ?: Instant.EPOCH,
            size = runCatching { archive.fileSize() }.getOrDefault(0L),
            restorable = zipHasRootLevelDat(archive),
        )
    }

    /** 目录体积：只统计普通文件，跳过符号链接与目录联接，读不出的大小时按 0 计。 */
    private fun directorySize(directory: Path): Long {
        var total = 0L
        runCatching {
            Files.walkFileTree(
                directory,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        dir: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult =
                        if (dir != directory && (attrs.isOther || attrs.isSymbolicLink))
                            FileVisitResult.SKIP_SUBTREE
                        else FileVisitResult.CONTINUE

                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        if (attrs.isRegularFile && !attrs.isOther)
                            total += runCatching { file.fileSize() }.getOrDefault(0L)
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        file: Path,
                        error: IOException,
                    ): FileVisitResult = FileVisitResult.CONTINUE
                },
            )
        }
            .onFailure { store.log("读取存档体积失败：${it.message ?: directory}") }
        return total
    }

    /** `.zip` 存档里是否有 `level.dat`；损坏的压缩包按「不像世界」处理，不抛异常。 */
    private fun zipHasLevelDat(archive: Path): Boolean = runCatching {
        ZipFile(archive.toFile()).use { zip ->
            Collections.list(zip.entries()).any { entry ->
                !entry.isDirectory &&
                    (entry.name.equals("level.dat", true) ||
                        entry.name.endsWith("/level.dat", true))
            }
        }
    }
        .getOrDefault(false)

    /** 解析 `saves/` 下的直接子项；只接受目录或 `.zip`，并拒绝越界与符号链接。 */
    private fun saveSource(saves: Path, saveName: String): Path {
        require(saveName.isNotBlank() && saveName.length < 200) { "存档名称无效" }
        require('/' !in saveName && '\\' !in saveName && ':' !in saveName) { "存档名称无效：$saveName" }
        val path = runCatching {
            safePath(saves, saveName)
        }
            .getOrElse { error("存档名称不安全：$saveName") }
        require(path.parent?.toAbsolutePath()?.normalize() == absolute(saves)) {
            "只能操作 saves 目录下的存档：$saveName"
        }
        require(!isLink(path)) { "存档是符号链接或目录联接，未处理：$saveName" }
        require(path.exists(LinkOption.NOFOLLOW_LINKS)) { "找不到存档：$saveName" }
        require(
            path.isDirectory(LinkOption.NOFOLLOW_LINKS) ||
                (path.isRegularFile(LinkOption.NOFOLLOW_LINKS) && path.name.endsWith(".zip", true))
        ) {
            "不是可识别的存档：$saveName"
        }
        return path
    }

    /** 恢复目标路径：`saves/<名称>`，并保证它就是 `saves/` 的直接子项。 */
    private fun saveTarget(saves: Path, name: String): Path {
        require(isPlainName(name)) { "备份里的存档名称无效：$name" }
        val target = runCatching { safePath(saves, name) }.getOrElse { error("备份里的存档名称不安全：$name") }
        require(target.parent.toAbsolutePath().normalize() == absolute(saves)) {
            "备份里的存档名称超出 saves 目录：$name"
        }
        return target
    }

    /** 由备份标识解析备份压缩包；标识就是去掉 `.zip` 的文件名，越界与符号链接一律拒绝。 */
    private fun resolveBackup(instanceId: String, backupId: String): Path {
        require(backupId.isNotBlank() && backupId.length < 200) { "备份标识无效" }
        require('/' !in backupId && '\\' !in backupId && ':' !in backupId) { "备份标识无效" }
        val directory = backupDirectory(instanceId)
        val archive = runCatching {
            safePath(directory, "$backupId.zip")
        }
            .getOrElse { error("备份标识无效：$backupId") }
        require(archive.parent.toAbsolutePath().normalize() == absolute(directory)) {
            "备份标识无效：$backupId"
        }
        require(archive.isRegularFile(LinkOption.NOFOLLOW_LINKS) && !isLink(archive)) {
            "找不到备份文件：$backupId"
        }
        return archive
    }

    /** 原版备份文件名里的 `(创建时间, 世界名)`；不符合命名时返回 null，不因此隐藏备份。 */
    private fun parseBackupId(id: String): Pair<Instant, String>? {
        val match = saveBackupIdPattern.matchEntire(id) ?: return null
        val name = match.groupValues[2].trim()
        if (name.isBlank()) return null
        val instant =
            runCatching {
                java.time.LocalDateTime.parse(match.groupValues[1], parseBackupStamp)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
            }
                .getOrNull() ?: return null
        return instant to name
    }

    /** 分配一个不冲突的原版备份文件名：同一秒内连续备份时把秒数顺延 1 秒。 */
    private fun allocateArchive(directory: Path, label: String): Pair<Path, Instant> {
        var instant = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        var archive = directory.resolve("${saveBackupStamp.format(instant)}_$label.zip")
        var attempt = 0
        while (archive.exists(LinkOption.NOFOLLOW_LINKS)) {
            attempt++
            require(attempt < 1000) { "备份文件名冲突，请稍后重试" }
            instant = instant.plusSeconds(1)
            archive = directory.resolve("${saveBackupStamp.format(instant)}_$label.zip")
        }
        return archive to instant
    }

    /** 把存档打包进 `backups/`：目录按内容打包，`saves/` 里的 `.zip` 直接整包复制。 */
    private suspend fun packSave(
        instanceId: String,
        source: Path,
        label: String,
        context: JobContext?,
        progressLabel: String,
    ): SaveBackupInfo {
        val directory = backupDirectory(instanceId)
        if (directory.exists(LinkOption.NOFOLLOW_LINKS)) requireOrdinaryDirectory(directory)
        directory.createDirectories()
        val (archive, createdAt) = allocateArchive(directory, label)
        if (!source.isDirectory(LinkOption.NOFOLLOW_LINKS)) copyIntoArchive(source, archive)
        else packDirectory(source, archive, context, progressLabel)
        return SaveBackupInfo(
            id = archive.name.removeSuffix(".zip"),
            saveName = label,
            archive = archive,
            createdAt = createdAt,
            size = archive.fileSize(),
            restorable = zipHasRootLevelDat(archive),
        )
    }

    /** 备份目录必须是普通目录：不能是文件、符号链接或目录联接。 */
    private fun requireOrdinaryDirectory(path: Path) {
        val attributes =
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(attributes.isDirectory && !attributes.isSymbolicLink && !attributes.isOther) {
            "备份路径不是普通目录：$path"
        }
    }

    /** 压缩包根目录是否有 `level.dat`：决定这个备份能否被还原成一个世界。 */
    private fun zipHasRootLevelDat(archive: Path): Boolean = runCatching {
        ZipFile(archive.toFile()).use { zip ->
            Collections.list(zip.entries()).any { entry ->
                !entry.isDirectory && entry.name.trimEnd('/').equals("level.dat", true)
            }
        }
    }
        .getOrDefault(false)

    /** 解压后的世界必须带非空的 `level.dat`，否则不是可恢复的存档。 */
    private fun requireRestorableWorld(directory: Path, source: String) {
        val level = directory.resolve("level.dat")
        require(level.isRegularFile(LinkOption.NOFOLLOW_LINKS) && !isLink(level)) {
            "$source 缺少 level.dat，未恢复"
        }
        require(level.fileSize() > 0) { "$source 的 level.dat 是空文件，未恢复" }
        runCatching { Files.newInputStream(level).use { it.read() } }
            .getOrElse { error("$source 的 level.dat 无法读取，未恢复") }
    }

    /** 替换前的额外校验：除 level.dat 外还要有非空的区域文件，避免把空壳目录换进世界位置。 */
    private fun requireReplaceableWorld(directory: Path) {
        requireRestorableWorld(directory, "备份包")
        var regions = 0
        Files.walkFileTree(
            directory,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult =
                    if (dir != directory && (attrs.isOther || attrs.isSymbolicLink))
                        FileVisitResult.SKIP_SUBTREE
                    else FileVisitResult.CONTINUE

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (
                        attrs.isRegularFile &&
                            !attrs.isOther &&
                            file.name.endsWith(".mca", true) &&
                            attrs.size() > 0
                    )
                        regions++
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, error: IOException): FileVisitResult =
                    FileVisitResult.CONTINUE
            },
        )
        require(regions > 0) { "备份包没有非空的区域文件，未替换" }
    }

    /** `saves/` 下有没有世界正被游戏打开：`session.lock` 被占用即视为打开。 */
    private fun requireSavesNotLocked(instanceId: String) {
        val saves = savesDirectory(instanceId)
        if (!saves.isDirectory(LinkOption.NOFOLLOW_LINKS) || isLink(saves)) return
        val open =
            runCatching {
                    saves.listDirectoryEntries()
                        .filter { it.isDirectory(LinkOption.NOFOLLOW_LINKS) && !isLink(it) }
                        .firstOrNull { sessionLockHeld(it) }
                }
                .getOrNull()
        require(open == null) {
            "世界「${open?.fileName}」正在游戏中打开，请先退出游戏再恢复存档"
        }
    }

    private fun requireWorldClosed(world: Path) {
        require(!sessionLockHeld(world)) {
            "世界「${world.fileName}」正在游戏中打开，请先退出游戏"
        }
    }

    /** 把世界目录打包成 zip：按相对路径排序，跳过符号链接与越出世界目录的路径，先写 `.part`。 */
    private suspend fun packDirectory(
        source: Path,
        archive: Path,
        context: JobContext?,
        label: String,
    ) {
        val part = siblingPart(archive)
        try {
            val files = collectSaveFiles(source)
            ZipOutputStream(part.outputStream().buffered()).use { zip ->
                if (files.isEmpty()) context?.progress(0.5f, "$label · 空目录")
                files.forEachIndexed { index, (relative, file) ->
                    context?.checkCancelled()
                    zip.putNextEntry(ZipEntry(relative))
                    file.inputStream().use { input -> input.copyTo(zip, 64 * 1024) }
                    zip.closeEntry()
                    context?.progress(
                        (index + 1).toFloat() / files.size,
                        "$label · 文件 ${index + 1}/${files.size}",
                    )
                }
            }
            moveIntoPlace(part, archive)
        } finally {
            part.deleteIfExists()
        }
    }

    /** `.zip` 存档当成整包备份：直接复制到 `.part` 再改名，不嵌套压缩。 */
    private fun copyIntoArchive(source: Path, archive: Path) {
        val part = siblingPart(archive)
        try {
            Files.copy(source, part)
            moveIntoPlace(part, archive)
        } finally {
            part.deleteIfExists()
        }
    }

    private fun siblingPart(archive: Path): Path =
        archive.resolveSibling("${archive.fileName}.part")

    /**
     * 收集世界目录内的普通文件（相对路径统一正斜杠）。
     *
     * 用 [Files.walkFileTree] 而不是 `Files.walk`：Windows 目录联接（junction）的 `isSymbolicLink` 为
     * false，`Files.walk` 会递归进去，必须在 `preVisitDirectory` 里跳过整棵子树。 链接、越出世界目录的路径与读不出的文件只跳过；取消时抛
     * [CancellationException]。
     */
    private suspend fun collectSaveFiles(source: Path): List<Pair<String, Path>> {
        val root = source.toAbsolutePath().normalize()
        val realRoot = root.toRealPath()
        val job = currentCoroutineContext()[Job]
        val files = mutableListOf<Pair<String, Path>>()
        var visited = 0
        fun checkCancelled() {
            if (job != null && !job.isActive) throw CancellationException("存档操作已取消")
        }
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    checkCancelled()
                    if (dir != root && (attrs.isOther || attrs.isSymbolicLink))
                        return FileVisitResult.SKIP_SUBTREE
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (++visited % 256 == 0) checkCancelled()
                    if (!attrs.isRegularFile || attrs.isOther) return FileVisitResult.CONTINUE
                    val real =
                        runCatching { file.toRealPath() }.getOrNull()
                            ?: return FileVisitResult.CONTINUE
                    if (!real.startsWith(realRoot)) return FileVisitResult.CONTINUE
                    files.add(root.relativize(file).joinToString("/") to file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, error: IOException): FileVisitResult =
                    FileVisitResult.CONTINUE
            },
        )
        return files.sortedBy { it.first }
    }

    /** 解压到 [destination]：拒绝 `..`、绝对路径、盘符与符号链接条目，体积与条目数受限；可跳过 `session.lock`。 */
    private suspend fun extractArchive(
        archive: Path,
        destination: Path,
        context: JobContext?,
        skipSessionLock: Boolean = false,
        stripPrefix: String = "",
    ) {
        destination.createDirectories()
        ArchiveZipFile.builder().setPath(archive).get().use { zip ->
            val entries =
                Collections.list(zip.entries).filter { !it.isDirectory && !it.name.endsWith("/") }
            require(entries.isNotEmpty()) { "备份包是空的，无法恢复" }
            require(entries.size <= MAX_SAVE_ENTRIES) { "备份包文件过多，无法恢复" }
            val realRoot = destination.toAbsolutePath().normalize()
            var declared = 0L
            var written = 0
            for (entry in entries) {
                context?.checkCancelled()
                val name = entry.name.replace('\\', '/')
                require(name.split('/').none { it == ".." }) {
                    "备份包包含向上跳转的路径：${entry.name}"
                }
                require(!name.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(name)) {
                    "备份包包含绝对路径：${entry.name}"
                }
                require(!entry.isUnixSymlink) { "备份包包含符号链接条目：${entry.name}" }
                if (entry.size > 0) declared += entry.size
                require(declared <= MAX_SAVE_BYTES) { "备份包解压后体积超出限制" }
                // 导入的压缩包可能多出一层世界目录：只解压这一层里面的内容。
                val relative =
                    if (stripPrefix.isBlank()) name
                    else {
                        require(name.startsWith(stripPrefix, ignoreCase = true)) {
                            "压缩包条目不在世界目录内：${entry.name}"
                        }
                        name.substring(stripPrefix.length)
                    }
                if (relative.isBlank()) continue
                // session.lock 是游戏打开世界时持有的锁文件；复制出来只会让新世界一启动就报「世界正在使用」。
                if (skipSessionLock && relative.substringAfterLast('/').equals("session.lock", true))
                    continue
                val target = safePath(realRoot, relative)
                target.parent.createDirectories()
                var actual = 0L
                target.outputStream().use { output ->
                    zip.getInputStream(entry).use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            actual += read
                            require(actual <= MAX_SAVE_ENTRY_BYTES) {
                                "备份包条目超出大小限制：${entry.name}"
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                written++
                context?.progress(
                    if (entries.isEmpty()) 1f else written.toFloat() / entries.size,
                    "恢复存档 · 文件 $written/${entries.size}",
                )
            }
        }
    }

    /** 跨实例复制世界目录：先写临时目录，逐文件复制并检查取消，符号链接与越界路径跳过。 */
    private suspend fun copySaveTree(source: Path, staging: Path, context: JobContext?) {
        val files = collectSaveFiles(source)
        staging.createDirectories()
        files.forEachIndexed { index, (relative, file) ->
            context?.checkCancelled()
            val target = safePath(staging, relative)
            target.parent.createDirectories()
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING)
            context?.progress(
                (index + 1).toFloat() / files.size,
                "复制存档 · 文件 ${index + 1}/${files.size}",
            )
        }
    }

    /** 目标已有同名存档时按序号避让（`World` → `World 2`），从不覆盖。 */
    private fun uniqueSaveName(directory: Path, base: String, isZip: Boolean): String {
        fun withExtension(name: String) = if (isZip) "$name.zip" else name
        fun taken(name: String) =
            !isPlainName(withExtension(name)) ||
                directory.resolve(withExtension(name)).exists(LinkOption.NOFOLLOW_LINKS)
        if (!taken(base)) return withExtension(base)
        for (index in 2..999) {
            val candidate = "$base $index"
            if (candidate.length <= 80 && !taken(candidate)) return withExtension(candidate)
        }
        error("同名存档过多，请改用其他名称")
    }

    /** 存档名必须是单层名称：不含分隔符、盘符与 Windows 保留名。 */
    private fun isPlainName(name: String): Boolean {
        if (name.isBlank() || name.length > 80) return false
        if ('/' in name || '\\' in name || ':' in name) return false
        if (name.any { it < ' ' }) return false
        if (name.endsWith('.') || name.endsWith(' ')) return false
        // 用“当前目录 + 相对名称”做合法路径校验，不依赖 java.io.tmpdir（构建环境可能把它设成非法值）。
        return runCatching { safePath(Path.of("").toAbsolutePath(), name) }.isSuccess
    }

    /** 文件名安全化：去掉各平台的非法字符与结尾的点/空格，限制长度，空名称回退为 `save`。 */
    private fun safeSaveLabel(name: String): String {
        val cleaned =
            name
                .trim()
                .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
                .trimEnd('.', ' ')
                .trim()
        val limited = cleaned.take(60).trimEnd('.', ' ')
        return limited.ifBlank { "save" }
    }

    private fun absolute(path: Path): Path = path.toAbsolutePath().normalize()

    /**
     * 符号链接与 Windows 目录联接（junction）都算链接。
     *
     * Java 把 junction 的 `isSymbolicLink` 报成 false、`isOther` 报成 true，因此只看 `isSymbolicLink`
     * 会漏掉联接，`Files.walk` 甚至会递归进联接指向的目录。
     */
    private fun isLink(path: Path): Boolean = runCatching {
        Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
    }
        .map { it.isSymbolicLink || it.isOther }
        .getOrDefault(false)

    private fun moveIntoPlace(part: Path, target: Path) {
        commitSavePath(part, target)
    }

    /** 删除本次操作产生的目录/文件；链接与目录联接只删链接本身，不进入目标目录。 */
    private fun deleteTree(path: Path) {
        if (!path.exists(LinkOption.NOFOLLOW_LINKS)) return
        if (isLink(path) || path.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(path)
            return
        }
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult =
                    if (dir != path && (attrs.isOther || attrs.isSymbolicLink))
                        FileVisitResult.SKIP_SUBTREE
                    else FileVisitResult.CONTINUE

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun String.removeSuffixIgnoreCase(suffix: String): String =
        if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this
}

/**
 * 世界是否正被游戏打开：对 `session.lock` 尝试独占锁，拿不到就认为被占用。
 *
 * 原版游戏打开世界时会独占 `session.lock`；文件存在但不被锁是上次退出的残留，不算占用。 读不到/打不开锁文件时保守地按「占用」处理，宁可不写
 * `level.dat` 也不冒损坏世界的风险。
 */
internal fun sessionLockHeld(world: Path): Boolean {
    val lock = world.resolve("session.lock")
    if (!lock.exists(LinkOption.NOFOLLOW_LINKS)) return false
    if (!lock.isRegularFile(LinkOption.NOFOLLOW_LINKS)) return false
    return runCatching {
        FileChannel.open(lock, StandardOpenOption.WRITE).use { channel ->
            val held = channel.tryLock()
            if (held == null) true
            else {
                runCatching { held.release() }
                false
            }
        }
    }
        .getOrElse { true }
}
