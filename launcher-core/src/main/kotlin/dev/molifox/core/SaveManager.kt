// 负责存档列表、备份、恢复、复制和回收。

package dev.molifox.core

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
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

/** 备份区里的一个存档压缩包。 */
data class SaveBackupInfo(
    /** 备份标识，等于压缩包去掉 `.zip` 的文件名；恢复与删除都以它为句柄。 */
    val id: String,
    /** 备份时的存档名称（已做文件名安全化处理）。 */
    val saveName: String,
    val archive: Path,
    val createdAt: Instant,
    val size: Long,
    val sha1: String,
)

/** 备份区目录名；`.molifox-` 前缀不会被整合包覆盖（[packPath] 拒绝），也不在世界目录里。 */
private const val SAVE_BACKUP_DIRECTORY = ".molifox-saves-backups"

/** 临时目录前缀：解压与复制都先落在 `saves/` 内的隐藏目录，成功后一次改名。 */
private const val SAVE_STAGING_PREFIX = ".molifox-saves-"

/** 恢复时 `.molifox-saves-backups/<时间戳>-<名称>.replaced.zip` 的后缀标记。 */
private const val REPLACED_SUFFIX = ".replaced"

private const val MAX_SAVE_ENTRIES = 100_000
private const val MAX_SAVE_BYTES = 64L * 1024 * 1024 * 1024
private const val MAX_SAVE_ENTRY_BYTES = 32L * 1024 * 1024 * 1024

/** 备份文件名的时间戳：`yyyyMMdd-HHmmss-SSS`，可读且精确到毫秒，便于按名称解析创建时间。 */
private val saveBackupStamp =
    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneId.systemDefault())

/** 备份标识 = `<时间戳>-<安全化名称>[.replaced]`；第 1 组是名称部分。 */
private val saveBackupIdPattern = Regex("^\\d{8}-\\d{6}-\\d{3}-(.+)$")

/** 反向解析备份创建时间用的同一套格式。 */
private val parseBackupStamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
private val saveBackupParsePattern = Regex("^\\d{8}-\\d{6}-\\d{3}$")

/**
 * 存档管理：列出、备份、恢复与跨实例复制 `saves/` 下的世界。
 *
 * 约定：
 * - 只操作实例 `saves/` 的直接子项，目录与 `.zip` 都算存档；不跟随符号链接/目录联接， 损坏或不可读的子项只跳过，不抛异常。
 * - 备份写到实例目录下的 `.molifox-saves-backups/<时间戳>-<安全化名称>.zip`，先写 `.part` 再原子改名；备份**保留原存档**，不移动、不删除用户内容。
 * - 恢复先解压到 `saves/` 内的隐藏临时目录，校验通过后才改名到最终位置；`overwrite=false` 时目标已存在直接拒绝，`overwrite=true` 时先把现有存档打成
 *   `.replaced.zip` 再替换， 任一步失败都回滚（删除临时目录、把旧存档搬回原位、删除本次生成的 replaced 备份）。
 * - 跨实例复制只接受 MoliFox 自建的版本隔离实例；目标已有同名存档时**自动加序号** （`World` → `World 2`），从不覆盖目标实例的现有存档。
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
     * 列出备份区的全部压缩包，按创建时间从新到旧。
     *
     * [SaveBackupInfo.sha1] 在列出时现算，超大存档的首次列举会有明显耗时；只读，不修改磁盘。
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
     * 把 `saves/<saveName>` 打包到实例的备份区，返回备份信息。
     *
     * 世界目录按内容打包（跳过符号链接与越界路径），`saves/` 下现成的 `.zip` 存档直接复制； 成功与否都保留原存档。取消或失败时删除 `.part`，不留下半个压缩包。
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
            val directory = backupDirectory(instanceId)
            directory.createDirectories()
            val isZip = !source.isDirectory(LinkOption.NOFOLLOW_LINKS)
            val label = safeSaveLabel(source.name.removeSuffixIgnoreCase(".zip"))
            val (archive, createdAt) = allocateArchive(directory, label)
            context?.progress(0f, "备份存档 · $saveName")
            // 目录按内容打包；`saves/` 里现成的 `.zip` 直接整包复制，不嵌套压缩。
            if (isZip) copyIntoArchive(source, archive)
            else packDirectory(source, archive, context, "备份存档")
            val info =
                SaveBackupInfo(
                    id = archive.name.removeSuffix(".zip"),
                    saveName = label,
                    archive = archive,
                    createdAt = createdAt,
                    size = archive.fileSize(),
                    sha1 = digest(archive),
                )
            context?.progress(1f, "备份存档完成 · ${archive.name}")
            store.log("已备份存档：${instance.name} / $saveName → ${archive.name}")
            info
        }

    /**
     * 把备份解压回 `saves/<备份时的名称>`，返回恢复后的路径。
     *
     * [overwrite] 为 false 且目标已存在时拒绝（中文错误，不做任何修改）；为 true 时先把现有 存档打包成
     * `.molifox-saves-backups/<时间戳>-<名称>.replaced.zip` 再替换。解压只允许落在 `saves/` 之内，拒绝
     * `..`、绝对路径、盘符与符号链接条目。
     */
    suspend fun restoreSave(
        instanceId: String,
        backupId: String,
        overwrite: Boolean = false,
        context: JobContext? = null,
    ): Path =
        withContext(Dispatchers.IO) {
            val instance = store.instance(instanceId)
            requireGameStopped(instanceId)
            val root = store.instancePath(instanceId)
            require(root.isDirectory(LinkOption.NOFOLLOW_LINKS)) { "实例目录不存在，无法恢复存档" }
            val saves = savesDirectory(instanceId)
            val archive = resolveBackup(instanceId, backupId)
            val name = backupSaveName(backupId) ?: error("备份文件名无法识别，不能自动恢复：$backupId")
            val target = saveTarget(saves, name)
            val existed = target.exists(LinkOption.NOFOLLOW_LINKS)
            require(!existed || overwrite) {
                "存档「$name」已存在；如需用备份覆盖，请明确确认覆盖"
            }
            require(!isLink(target)) { "目标存档是符号链接或目录联接，未覆盖：$name" }
            saves.createDirectories()
            val staging = safePath(saves, "$SAVE_STAGING_PREFIX${UUID.randomUUID()}")
            var replaced: Path? = null
            var hold: Path? = null
            try {
                extractArchive(archive, staging, context)
                if (existed) {
                    val directory = backupDirectory(instanceId)
                    directory.createDirectories()
                    val (replacedArchive, _) = allocateArchive(directory, "$name$REPLACED_SUFFIX")
                    replaced = replacedArchive
                    if (target.isDirectory(LinkOption.NOFOLLOW_LINKS))
                        packDirectory(target, replacedArchive, context, "备份被替换的存档")
                    else copyIntoArchive(target, replacedArchive)
                    // 旧存档先同样本地改名搬到备份目录，成功后连同 replaced.zip 一起收尾。
                    val holding = safePath(directory, "$SAVE_STAGING_PREFIX${UUID.randomUUID()}")
                    Files.move(target, holding)
                    hold = holding
                }
                moveIntoPlace(staging, target)
                hold?.let { holding ->
                    runCatching { deleteTree(holding) }
                        .onFailure {
                            store.log("旧存档已备份到 ${replaced?.name}，临时副本保留在 $holding", true)
                        }
                }
                hold = null
                context?.progress(1f, "恢复存档完成 · $name")
                store.log("已恢复存档：${instance.name} / $name ← ${archive.name}")
                target
            } catch (error: Throwable) {
                val failures = mutableListOf<Throwable>()
                var holdingRestored = true
                runCatching { deleteTree(staging) }.onFailure { failures += it }
                if (hold != null) {
                    runCatching {
                        if (target.exists(LinkOption.NOFOLLOW_LINKS)) deleteTree(target)
                        Files.move(checkNotNull(hold), target)
                    }
                        .onFailure {
                            failures += it
                            holdingRestored = false
                        }
                }
                // 旧存档已经回到原位时 replaced 备份是多余副本，删掉；没回原位时保留它作为退路。
                if (holdingRestored)
                    replaced?.let { path ->
                        runCatching { path.deleteIfExists() }.onFailure { failures += it }
                    }
                if (failures.isNotEmpty() || !holdingRestored) {
                    val failure =
                        IOException(
                            "恢复存档未完成，且回滚失败：请检查 $target，旧存档副本在 $hold，备份在 $replaced",
                            error,
                        )
                    failures.forEach { failure.addSuppressed(it) }
                    throw failure
                }
                throw error
            }
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

    private fun describeBackup(archive: Path): SaveBackupInfo? {
        val id = archive.name.removeSuffixIgnoreCase(".zip")
        val name = backupSaveName(id) ?: return null
        return SaveBackupInfo(
            id = id,
            saveName = name,
            archive = archive,
            createdAt =
                parseBackupInstant(id)
                    ?: runCatching { archive.getLastModifiedTime().toInstant() }.getOrNull()
                    ?: Instant.EPOCH,
            size = runCatching { archive.fileSize() }.getOrDefault(0L),
            sha1 = runCatching { digest(archive) }.getOrDefault(""),
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

    /** 备份标识里的存档名称；`.replaced` 后缀表示它是覆盖前自动生成的旧存档备份。 */
    private fun backupSaveName(backupId: String): String? {
        val match = saveBackupIdPattern.find(backupId) ?: return null
        return match.groupValues[1].removeSuffix(REPLACED_SUFFIX).ifBlank { null }
    }

    /** 从备份标识的时间戳还原创建时间；名称被手工改坏时返回 null。 */
    private fun parseBackupInstant(backupId: String): Instant? {
        // 标识形如 20261027-173000-123-World，前 19 个字符是时间戳。
        val prefix = backupId.take(19)
        if (!saveBackupParsePattern.matches(prefix)) return null
        return runCatching {
            java.time.LocalDateTime.parse(prefix, parseBackupStamp)
                .atZone(ZoneId.systemDefault())
                .toInstant()
        }
            .getOrNull()
    }

    /** 分配一个不冲突的备份文件名与对应时间：同一毫秒内连续备份时把时间戳顺延 1 毫秒。 */
    private fun allocateArchive(directory: Path, label: String): Pair<Path, Instant> {
        var instant = Instant.now()
        var archive = directory.resolve("${saveBackupStamp.format(instant)}-$label.zip")
        var attempt = 0
        while (archive.exists(LinkOption.NOFOLLOW_LINKS)) {
            attempt++
            require(attempt < 1000) { "备份文件名冲突，请稍后重试" }
            instant = instant.plusMillis(1)
            archive = directory.resolve("${saveBackupStamp.format(instant)}-$label.zip")
        }
        return archive to instant
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

    /** 解压到 [destination]：拒绝 `..`、绝对路径、盘符与符号链接条目，体积与条目数受限。 */
    private suspend fun extractArchive(archive: Path, destination: Path, context: JobContext?) {
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
                val target = safePath(realRoot, name)
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
                    written.toFloat() / entries.size,
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
        return runCatching { safePath(Path.of(System.getProperty("java.io.tmpdir")), name) }
            .isSuccess
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
