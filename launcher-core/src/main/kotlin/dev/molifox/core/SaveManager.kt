// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责存档列表、备份、恢复、复制和回收。

package dev.molifox.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

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

/** 恢复出来的同级世界目录后缀：`<世界名>（备份 yyyy-MM-dd HH-mm）`。 */
private val restoreSuffixFormat =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm").withZone(ZoneId.systemDefault())

/** 恢复出来的世界名最多保留的字符数，给「（备份 时间）」留出文件名长度。 */
private const val SAVE_RESTORE_LABEL_LENGTH = 40

/**
 * 存档管理：列出、备份、恢复与跨实例复制 `saves/` 下的世界。
 *
 * 约定：
 * - 只操作实例 `saves/` 的直接子项，目录与 `.zip` 都算存档；不跟随符号链接/目录联接， 损坏或不可读的子项只跳过，不抛异常。
 * - 备份写到 `<游戏目录>/backups/<yyyy-MM-dd_HH-mm-ss>_<世界名>.zip`（与原版游戏同名同目录），
 *   先写 `.part` 再原子改名；备份**保留原存档**，不移动、不删除用户内容。
 * - 恢复默认是**非破坏性复制**：解压到 `saves/<世界名>（备份 时间）/`，跳过 `session.lock`， 已存在同名目录时加数字后缀，从不改动现有世界。
 * - 开启实例设置「恢复时替换原世界」后走替换流程：先备份当前世界 → 解压到 `saves/` 内的临时目录 → 校验 level.dat 与非空区域文件 →
 *   把当前世界改名为 `<世界名>（替换前 HH-mm）` 保留 → 临时目录改名就位；任一步失败都删除临时目录并把旧世界放回原位。
 * - 世界目录里 `session.lock` 被占用（游戏正在打开该世界）时拒绝恢复与替换。
 * - 跨实例复制只接受 MoliFox 自建的版本隔离实例；目标已有同名存档时**自动加序号** （`World` → `World 2`），从不覆盖目标实例的现有存档。
 * - 导入外部存档（[importSave]）只接受带 `level.dat` 的 `.zip` 或世界文件夹；ZIP 允许唯一一层包装目录，
 *   重名加 ` (2)` 序号，先校验后解压，失败不留残留。
 * - 删除备份与删除存档走 [PlatformServices.moveToTrash]，不做不可恢复的硬删除。
 */
class SaveManager(
    private val store: LauncherStore,
) {
    private val packer = SavePacker(store)

    /**
     * 列出实例 `saves/` 下的直接子项。
     *
     * `saves/` 不存在时返回空列表；程序目录、非 `.zip` 文件、符号链接与目录联接都不列出； 单个子项读不出属性时只跳过它。结果按名称排序（忽略大小写）。
     */
    fun listSaves(instanceId: String): List<SaveEntry> {
        store.instance(instanceId)
        val saves = savesDirectoryOf(store, instanceId)
        if (!saves.isDirectory(LinkOption.NOFOLLOW_LINKS) || isLinkPath(saves)) return emptyList()
        return runCatching {
            saves.listDirectoryEntries()
        }.onFailure {
            store.log("读取存档目录失败：${it.message ?: saves}", true)
        }.getOrDefault(emptyList())
            .mapNotNull { child -> runCatching { describeSaveEntry(child, store) }.getOrNull() }
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
        val directory = backupDirectoryOf(store, instanceId)
        if (!directory.isDirectory(LinkOption.NOFOLLOW_LINKS) || isLinkPath(directory)) {
            return emptyList()
        }
        return runCatching {
            directory.listDirectoryEntries()
        }.getOrDefault(emptyList())
            .filter {
                it.isRegularFile(LinkOption.NOFOLLOW_LINKS) &&
                    !isLinkPath(it) &&
                    it.name.endsWith(".zip", true)
            }.mapNotNull { archive -> runCatching { describeSaveBackup(archive) }.getOrNull() }
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
            requireSaveGameStopped(store, instanceId)
            val saves = savesDirectoryOf(store, instanceId)
            requireSaveDirectory(saves, "存档目录不存在，无法备份")
            val source = saveSourcePath(saves, saveName)
            val label = safeSaveLabel(source.name.removeSuffixIgnoreCase(".zip"))
            context?.progress(0f, "备份存档 · $saveName")
            val info = packer.packSave(instanceId, source, label, context, "备份存档")
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
            requireSaveGameStopped(store, instanceId)
            val saves = savesDirectoryOf(store, instanceId)
            requireSaveDirectory(saves, "存档目录不存在，无法恢复")
            requireSavesUnlocked(saves)
            val info = requireRestorableBackup(backupDirectoryOf(store, instanceId), backupId)
            val base =
                safeSaveLabel(info.saveName).take(SAVE_RESTORE_LABEL_LENGTH) +
                    "（备份 " +
                    restoreSuffixFormat.format(info.createdAt) +
                    "）"
            val name = uniqueSaveName(saves, base, isZip = false)
            val target = saveTargetPath(saves, name)
            require(!target.exists(LinkOption.NOFOLLOW_LINKS)) { "目标存档已存在：$name" }
            val staging = saveStagingPath(saves)
            withSaveCleanup({ runCatching { deleteSaveTree(staging) } }) {
                extractArchive(info.archive, staging, context, skipSessionLock = true)
                requireRestorableWorld(staging, "备份包")
                commitSavePath(staging, target)
            }
            context?.progress(1f, "恢复存档完成 · $name")
            store.log("已恢复存档：${instance.name} / $name ← ${info.archive.name}")
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
            requireSaveGameStopped(store, instanceId)
            val saves = savesDirectoryOf(store, instanceId)
            requireSaveDirectory(saves, "存档目录不存在，无法恢复")
            val info = requireRestorableBackup(backupDirectoryOf(store, instanceId), backupId)
            val name = safeSaveLabel(info.saveName)
            val target = saveTargetPath(saves, name)
            require(!isLinkPath(target)) { "目标存档是符号链接或目录联接，未处理：$name" }
            // 原世界不存在时「替换」没有意义，直接按非破坏性复制走一遍。
            if (!target.exists(LinkOption.NOFOLLOW_LINKS)) {
                return@withContext restoreSave(instanceId, backupId, context)
            }
            require(target.isDirectory(LinkOption.NOFOLLOW_LINKS)) { "存档「$name」不是目录，未替换" }
            require(!sessionLockHeld(target)) {
                "世界「${target.fileName}」正在游戏中打开，请先退出游戏"
            }
            // 1) 先备份当前世界；备份失败时后面一步都还没做，原世界保持原样。
            context?.progress(0f, "替换前备份存档 · $name")
            val previous =
                packer.packSave(instanceId, target, safeSaveLabel(name), context, "替换前备份存档")
            store.log("替换前已备份当前世界：${instance.name} / $name → ${previous.archive.name}")
            // 2) 解压到 saves/ 内的临时目录；3) 旧世界改名保留；4) 临时目录改名就位。
            val kept = replaceSaveWorld(saves, name, info.archive, context, previous.archive.name)
            context?.progress(1f, "恢复存档完成 · $name")
            store.log(
                "已用备份替换存档：${instance.name} / $name ← ${info.archive.name}；" +
                    "旧世界保留为 ${kept.fileName}",
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
            requireSaveGameStopped(store, sourceInstanceId, targetInstanceId)
            requireDistinctSaveDirectories(store, source, destination)
            val plan =
                prepareSaveCopy(
                    store,
                    sourceInstanceId,
                    targetInstanceId,
                    saveName,
                    rename,
                )
            val staging = saveStagingPath(plan.targetDirectory)
            withSaveCleanup({ runCatching { deleteSaveTree(staging) } }) {
                if (plan.isZip) {
                    Files.copy(plan.sourceTree, staging)
                } else {
                    packer.copySaveTree(plan.sourceTree, staging, context)
                }
                commitSavePath(staging, plan.target)
            }
            context?.progress(1f, "复制存档完成 · ${plan.finalName}")
            store.log(
                "已复制存档：${source.name} / $saveName → ${destination.name} / ${plan.finalName}",
            )
            plan.target
        }

    /**
     * 导入一个外部存档：`.zip` 或世界文件夹，返回 `saves/` 下的新世界路径。
     *
     * ZIP 必须在根目录或唯一一层包装目录里有 `level.dat`（[SaveArchive] 先只读校验，无效输入不写盘），
     * 解压时跳过 `session.lock` 并剥掉包装目录；文件夹直接复制。目标重名时加序号（`World (2)`），
     * 从不覆盖现有世界；解压先落 `saves/` 内的隐藏临时目录，失败删除，不留残留。
     */
    suspend fun importSave(
        instanceId: String,
        source: Path,
        context: JobContext? = null,
    ): Path =
        withContext(Dispatchers.IO) {
            val instance = store.instance(instanceId)
            requireSaveGameStopped(store, instanceId)
            val saves = savesDirectoryOf(store, instanceId)
            require(!isLinkPath(saves)) { "存档目录是符号链接或目录联接，无法导入" }
            saves.createDirectories()
            val plan = prepareSaveImport(source)
            context?.progress(0f, "导入存档 · ${plan.source.fileName}")
            val name = uniqueChildName(saves, plan.base)
            val target = saveTargetPath(saves, name)
            require(!target.exists(LinkOption.NOFOLLOW_LINKS)) { "目标存档已存在：$name" }
            val staging = saveStagingPath(saves)
            withSaveCleanup({ runCatching { deleteSaveTree(staging) } }) {
                if (plan.isDirectory) {
                    packer.copySaveTree(plan.source, staging, context)
                } else {
                    extractArchive(
                        plan.source,
                        staging,
                        context,
                        skipSessionLock = true,
                        stripPrefix = plan.prefix,
                    )
                }
                requireRestorableWorld(staging, "导入的存档")
                commitSavePath(staging, target)
            }
            context?.progress(1f, "导入存档完成 · $name")
            store.log("已导入存档：${instance.name} / $name ← ${plan.source.fileName}")
            target
        }

    /** 删除一个备份：移入回收站，不硬删；找不到或回收站失败返回 false。 */
    fun deleteBackup(
        instanceId: String,
        backupId: String,
    ): Boolean {
        store.instance(instanceId)
        val archive =
            runCatching { resolveBackupArchive(backupDirectoryOf(store, instanceId), backupId) }
                .getOrNull()
        if (archive == null) return false
        return runCatching {
            store.platform.moveToTrash(archive)
            store.log("存档备份已移入回收站：${archive.name}")
            true
        }.getOrElse {
            store.log("删除存档备份失败：${it.message ?: backupId}", true)
            false
        }
    }

    /** 删除一个存档：移入回收站，不硬删；找不到或回收站失败返回 false。 */
    fun deleteSave(
        instanceId: String,
        saveName: String,
    ): Boolean {
        val instance = store.instance(instanceId)
        requireSaveGameStopped(store, instanceId)
        val saves = savesDirectoryOf(store, instanceId)
        val path = runCatching { saveSourcePath(saves, saveName) }.getOrNull() ?: return false
        return runCatching {
            store.platform.moveToTrash(path)
            store.log("存档已移入回收站：${instance.name} / $saveName")
            true
        }.getOrElse {
            store.log("删除存档失败：${it.message ?: saveName}", true)
            false
        }
    }
}
