// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责实例隔离切换与跨游戏目录迁移。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** 实例的目录语义：共用游戏目录（多个实例共用 mods/存档），或版本隔离（实例独占目录）。 */
enum class IsolationMode(
    private val titleKey: String,
) {
    Shared("core.isolationMode.shared"),
    Isolated("core.isolationMode.isolated"),
    ;

    /** 显示名在读取时翻译，切换语言后重新取值。 */
    val title: String
        get() = I18n.t(titleKey)
}

/** 迁移计划中单个相对路径的处理方式。 */
enum class MigrationAction {
    /** 复制到目标，源文件保留。 */
    Copy,

    /** 移动到目标并删除源文件；只在显式 [IsolationMigrationTransaction.apply] 的 moveSource 下执行。 */
    Move,

    /** 目标已有相同内容，无需搬运。 */
    Skip,

    /** 目标已有不同内容或不可覆盖的路径，需要用户处理。 */
    Conflict,
}

/** 迁移计划的一条：相对路径统一使用正斜杠，与 文件归属账本一致。 */
data class MigrationEntry(
    val relativePath: String,
    val action: MigrationAction,
    val size: Long,
    val reason: String,
)

/**
 * 隔离方式切换或跨游戏目录迁移的完整计划。
 *
 * [entries] 只覆盖白名单内容（或整体移动时的全部实例文件），[blockers] 非空时执行器拒绝执行： 冲突、符号链接、目录互相包含等都必须先由用户处理，不能静默覆盖或丢弃。
 */
data class IsolationPlan(
    val instanceId: String,
    val from: IsolationMode,
    val to: IsolationMode,
    val sourceRoot: Path,
    val targetRoot: Path,
    val entries: List<MigrationEntry>,
    val blockers: List<String> = emptyList(),
) {
    /** 需要实际搬运的字节数（[MigrationAction.Copy] 与 [MigrationAction.Move] 之和）。 */
    val copyBytes: Long
        get() =
            entries
                .filter {
                    it.action == MigrationAction.Copy || it.action == MigrationAction.Move
                }.sumOf { it.size }

    val moveCount: Int
        get() = entries.count { it.action == MigrationAction.Move }

    val conflictCount: Int
        get() = entries.count { it.action == MigrationAction.Conflict }

    /** 实际需要执行的文件数，不含 Skip 与 Conflict。 */
    val transferCount: Int
        get() =
            entries.count {
                it.action == MigrationAction.Copy || it.action == MigrationAction.Move
            }
}

/** 隔离切换时唯一会搬运的目录；其余目录（日志、崩溃报告、缓存、截图、启动器文件）一律留在原地。 */
private val migrationDirectories =
    listOf("mods", "config", "defaultconfigs", "resourcepacks", "shaderpacks", "saves")

/** 隔离切换时唯一会搬运的根目录文件；`saves/` 由 copySaves 单独控制。 */
private val migrationFiles =
    listOf("options.txt", "optionsof.txt", "servers.dat", "servers.dat_old")

internal fun IsolationMode.versionIsolation(): VersionIsolation =
    if (this == IsolationMode.Isolated) VersionIsolation.Enabled else VersionIsolation.Disabled

/**
 * 计划生成。
 *
 * - 白名单之外的文件（日志、崩溃报告、缓存、截图、`.molifox-content.json` 等启动器文件、 `.molifox-pack-upgrade-*` 升级备份）不进入计划；
 * - 目标已存在且内容相同 → Skip，内容不同或不可覆盖 → Conflict 并写入 blockers；
 * - 源不存在的路径不生成条目；
 * - `copySaves=false` 时 `saves/` 只生成一条 Skip，源存档保持原样；
 * - 共用游戏目录（Shared）里的文件可能被其他实例使用，即使请求 moveSource 也只复制。
 */
object IsolationMigrationPlanner {
    fun plan(
        instance: GameInstance,
        from: IsolationMode,
        to: IsolationMode,
        sourceRoot: Path,
        targetRoot: Path,
        copySaves: Boolean,
        moveSource: Boolean = false,
    ): IsolationPlan {
        require(from != to) { I18n.t("core.isolationMigration.3") }
        require(!instance.externalProfile) { I18n.t("core.isolationMigration.4") }
        val source = sourceRoot.toAbsolutePath().normalize()
        val target = targetRoot.toAbsolutePath().normalize()
        require(source != target) { I18n.t("core.isolationMigration.5") }
        require(source.isDirectory(LinkOption.NOFOLLOW_LINKS)) { I18n.t("core.isolationMigration.6") }
        // 共用游戏目录的文件可能被其他实例使用，只有实例独占目录才允许搬走。
        val moving = moveSource && from == IsolationMode.Isolated
        val copyOnlyNote =
            if (moveSource && from == IsolationMode.Shared) I18n.t("core.isolationMigration.7") else ""
        val entries = mutableListOf<MigrationEntry>()
        val blockers = mutableListOf<String>()
        // 版本隔离目录可能位于共用游戏目录之内（instances/<id>），只跳过目标子树，不能跳过整棵源树。
        val nested = if (target.startsWith(source)) target else null
        for ((relative, file) in candidates(source, nested, copySaves, blockers)) {
            planEntry(relative, file, target, moving, to, copyOnlyNote, entries, blockers)
        }
        if (!copySaves) savesSkip(source)?.let { entries += it }
        return IsolationPlan(
            instanceId = instance.id,
            from = from,
            to = to,
            sourceRoot = source,
            targetRoot = target,
            entries = entries.sortedBy { it.relativePath },
            blockers = blockers.toList(),
        )
    }

    /** 单条源文件的计划：目标不存在才搬运，已存在时按内容与类型判定 Skip 或 Conflict。 */
    private fun planEntry(
        relative: String,
        file: Path,
        target: Path,
        moving: Boolean,
        to: IsolationMode,
        copyOnlyNote: String,
        entries: MutableList<MigrationEntry>,
        blockers: MutableList<String>,
    ) {
        val size = runCatching { file.fileSize() }.getOrDefault(0L)
        val destination = runCatching { safePath(target, relative) }.getOrNull()
        if (destination == null) {
            entries += MigrationEntry(relative, MigrationAction.Conflict, size, I18n.t("core.isolationMigration.8"))
            blockers += I18n.t("core.isolationMigration.9", relative)
            return
        }
        if (!destination.exists(LinkOption.NOFOLLOW_LINKS)) {
            entries +=
                MigrationEntry(
                    relative,
                    if (moving) MigrationAction.Move else MigrationAction.Copy,
                    size,
                    transferReason(moving, to) + copyOnlyNote,
                )
            return
        }
        planExistingDestination(relative, file, destination, size, entries, blockers)
    }

    /** 目标已存在时的判定：符号链接、同名目录、相同内容、不同内容，顺序与判定结果保持不变。 */
    private fun planExistingDestination(
        relative: String,
        file: Path,
        destination: Path,
        size: Long,
        entries: MutableList<MigrationEntry>,
        blockers: MutableList<String>,
    ) {
        when {
            Files.isSymbolicLink(destination) -> {
                entries +=
                    MigrationEntry(relative, MigrationAction.Conflict, size, I18n.t("core.isolationMigration.10"))
                blockers += I18n.t("core.isolationMigration.11", relative)
            }
            destination.isDirectory(LinkOption.NOFOLLOW_LINKS) -> {
                entries +=
                    MigrationEntry(relative, MigrationAction.Conflict, size, I18n.t("core.isolationMigration.12"))
                blockers += I18n.t("core.isolationMigration.13", relative)
            }
            sameContent(file, destination, size) -> {
                entries +=
                    MigrationEntry(relative, MigrationAction.Skip, size, I18n.t("core.isolationMigration.14"))
            }
            else -> {
                entries +=
                    MigrationEntry(
                        relative,
                        MigrationAction.Conflict,
                        size,
                        I18n.t("core.isolationMigration.15"),
                    )
                blockers += I18n.t("core.isolationMigration.16", relative)
            }
        }
    }

    /** 目标与源同尺寸且摘要一致时视为已迁移；读不到时按不同内容处理。 */
    private fun sameContent(
        file: Path,
        destination: Path,
        size: Long,
    ): Boolean =
        destination.fileSize() == size &&
            runCatching { digest(file) == digest(destination) }.getOrDefault(false)

    /** 生成整体移动实例目录的计划：源目录下所有普通文件都要搬走，符号链接无法搬运时只记 blocker。 移动不改变隔离方式，因此 `from` 与 `to` 相同。 */
    fun planMove(
        instance: GameInstance,
        mode: IsolationMode,
        sourceRoot: Path,
        targetRoot: Path,
        blockers: List<String> = emptyList(),
    ): IsolationPlan {
        require(!instance.externalProfile) { I18n.t("core.isolationMigration.17") }
        val source = sourceRoot.toAbsolutePath().normalize()
        val target = targetRoot.toAbsolutePath().normalize()
        require(source != target) { I18n.t("core.isolationMigration.18") }
        require(!target.startsWith(source) && !source.startsWith(target)) {
            I18n.t("core.isolationMigration.19")
        }
        require(source.isDirectory(LinkOption.NOFOLLOW_LINKS)) { I18n.t("core.isolationMigration.20") }
        val problems = blockers.toMutableList()
        val (files, links) = instanceFiles(source)
        if (links > 0) problems += I18n.t("core.isolationMigration.21", links)
        val entries = mutableListOf<MigrationEntry>()
        for ((relative, file) in files.sortedBy { it.first }) {
            planMoveEntry(relative, file, target, entries, problems)
        }
        return IsolationPlan(
            instanceId = instance.id,
            from = mode,
            to = mode,
            sourceRoot = source,
            targetRoot = target,
            entries = entries,
            blockers = problems.toList(),
        )
    }

    /** 收集实例目录下的普通文件与符号链接数量；符号链接无法整体搬运，只计数供 blocker 使用。 */
    private fun instanceFiles(source: Path): Pair<List<Pair<String, Path>>, Int> {
        val files = mutableListOf<Pair<String, Path>>()
        var links = 0
        Files.walk(source).use { paths ->
            val iterator = paths.iterator()
            while (iterator.hasNext()) {
                val path = iterator.next()
                if (path == source) continue
                if (Files.isSymbolicLink(path)) {
                    links++
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    files += source.relativize(path).toString().replace('\\', '/') to path
                }
            }
        }
        return files to links
    }

    /** 整体移动的单条计划：目标已有同名文件时记为 Conflict，绝不覆盖。 */
    private fun planMoveEntry(
        relative: String,
        file: Path,
        target: Path,
        entries: MutableList<MigrationEntry>,
        problems: MutableList<String>,
    ) {
        val size = runCatching { file.fileSize() }.getOrDefault(0L)
        val destination = runCatching { safePath(target, relative) }.getOrNull()
        if (destination == null) {
            problems += I18n.t("core.isolationMigration.9", relative)
            return
        }
        if (destination.exists(LinkOption.NOFOLLOW_LINKS)) {
            entries += MigrationEntry(relative, MigrationAction.Conflict, size, I18n.t("core.isolationMigration.22"))
            problems += I18n.t("core.isolationMigration.23", relative)
        } else {
            entries += MigrationEntry(relative, MigrationAction.Copy, size, I18n.t("core.isolationMigration.24"))
        }
    }

    private fun transferReason(
        moving: Boolean,
        to: IsolationMode,
    ): String =
        if (moving) I18n.t("core.isolationMigration.25", to.title) else I18n.t("core.isolationMigration.26", to.title)

    /** 按白名单收集源文件；只读，跳过符号链接与目标目录自身。 */
    private fun candidates(
        source: Path,
        excluded: Path?,
        copySaves: Boolean,
        blockers: MutableList<String>,
    ): List<Pair<String, Path>> {
        val result = mutableListOf<Pair<String, Path>>()
        for (name in migrationDirectories) {
            if (name == "saves" && !copySaves) continue
            val root = source.resolve(name)
            if (Files.isSymbolicLink(root)) {
                blockers += I18n.t("core.isolationMigration.27", name)
            } else {
                when {
                    root.isDirectory(LinkOption.NOFOLLOW_LINKS) ->
                        walk(root, excluded, source, blockers, result)
                    root.isRegularFile(LinkOption.NOFOLLOW_LINKS) -> result += name to root
                }
            }
        }
        for (name in migrationFiles) {
            val file = source.resolve(name)
            if (Files.isSymbolicLink(file)) {
                blockers += I18n.t("core.isolationMigration.27", name)
                continue
            }
            if (file.isRegularFile(LinkOption.NOFOLLOW_LINKS)) result += name to file
        }
        return result.sortedBy { it.first }
    }

    private fun walk(
        root: Path,
        excluded: Path?,
        source: Path,
        blockers: MutableList<String>,
        result: MutableList<Pair<String, Path>>,
    ) {
        var links = 0
        Files.walk(root).use { paths ->
            val iterator = paths.iterator()
            while (iterator.hasNext()) {
                val path = iterator.next()
                if (path == root || (excluded != null && path.startsWith(excluded))) continue
                if (Files.isSymbolicLink(path)) {
                    links++
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    result += source.relativize(path).toString().replace('\\', '/') to path
                }
            }
        }
        if (links > 0) {
            blockers +=
                I18n.t("core.isolationMigration.28", source.relativize(root).toString().replace('\\', '/'), links)
        }
    }

    /** `copySaves=false` 时给出一条整目录 Skip，并统计存档体积供界面提示。 */
    private fun savesSkip(source: Path): MigrationEntry? {
        val saves = source.resolve("saves")
        if (!saves.isDirectory(LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(saves)) {
            return null
        }
        var bytes = 0L
        Files.walk(saves).use { paths ->
            paths.forEach {
                if (Files.isSymbolicLink(it)) return@forEach
                if (Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS)) bytes += it.fileSize()
            }
        }
        return MigrationEntry("saves", MigrationAction.Skip, bytes, I18n.t("core.isolationMigration.29"))
    }
}

/**
 * 隔离迁移执行器（事务）。
 *
 * 逐条执行 Copy/Move 并在每条前后检查取消；中途失败或取消时删除已复制的目标文件、把已移动的 源文件搬回原处、恢复实例记录。回滚本身失败时保留迁移清单目录并抛出带绝对路径的
 * [IOException]，绝不假装迁移成功。默认不删除任何源文件：只有计划里明确是 Move 且调用方传入 `moveSource=true` 时才搬走源文件。
 */
internal class IsolationMigrationTransaction(
    private val store: LauncherStore,
    private val instanceRoot: Path,
    private val backupRoot: Path,
) {
    /** 返回实际执行的条目数（Skip 与 Conflict 不计）。 */
    suspend fun apply(
        plan: IsolationPlan,
        context: JobContext,
        copySaves: Boolean,
        moveSource: Boolean = false,
        updateInstance: (GameInstance) -> Unit,
    ): Int {
        validatePlan(plan)
        val original = store.instance(plan.instanceId)
        val sourceRoot = plan.sourceRoot.toAbsolutePath().normalize()
        val targetRoot = plan.targetRoot.toAbsolutePath().normalize()
        val root = instanceRoot.toAbsolutePath().normalize()
        validateRoots(root, sourceRoot, targetRoot)
        val transfer = transferEntries(plan)
        validateTransfer(transfer, copySaves)
        val state = TransferState(original, targetRoot, backupRoot.toAbsolutePath().normalize())
        val label =
            if (plan.from == plan.to) I18n.t("core.isolationMigration.30") else I18n.t("core.isolationMigration.31")
        state.backup.createDirectories()
        MigrationManifest.write(state.backup, plan)
        guardedTransfer(state) {
            copyEntries(transfer, sourceRoot, state, moveSource, label, context)
            context.checkCancelled()
            updateInstance(recordFor(plan, original))
        }
        MigrationManifest.deleteTree(state.backup)
        return transfer.size
    }

    /** 迁移现场：逐条记录已复制、已移动与已创建的目录，失败时按相反顺序回滚。 */
    private class TransferState(
        val original: GameInstance,
        val targetRoot: Path,
        val backup: Path,
    ) {
        val copied = mutableListOf<Path>()
        val moved = mutableListOf<Pair<Path, Path>>()
        val createdDirectories = mutableListOf<Path>()
    }

    /**
     * 执行搬运并在失败时回滚。
     *
     * 这里只能兜底：调用方传入的 `updateInstance` 可以抛出任意运行时异常，回滚必须覆盖所有这些情况；
     * 具体类型按实际会抛出的 IO、记录缺失与路径校验失败收窄。
     */
    private suspend fun guardedTransfer(
        state: TransferState,
        body: suspend () -> Unit,
    ) {
        val failure =
            try {
                body()
                return
            } catch (error: CancellationException) {
                error
            } catch (error: IOException) {
                error
            } catch (error: IllegalStateException) {
                error
            } catch (error: IllegalArgumentException) {
                error
            }
        throw rollback(failure, state)
    }

    private fun transferEntries(plan: IsolationPlan): List<MigrationEntry> =
        plan.entries.filter {
            it.action == MigrationAction.Copy ||
                it.action == MigrationAction.Move
        }

    private fun recordFor(
        plan: IsolationPlan,
        original: GameInstance,
    ): GameInstance =
        if (plan.from == plan.to) {
            original
        } else {
            original.copy(isolation = plan.to.versionIsolation())
        }

    private suspend fun copyEntries(
        transfer: List<MigrationEntry>,
        sourceRoot: Path,
        state: TransferState,
        moveSource: Boolean,
        label: String,
        context: JobContext,
    ) {
        transfer.forEachIndexed { index, entry ->
            context.checkCancelled()
            val source = transferSource(sourceRoot, entry.relativePath)
            val target = safePath(state.targetRoot, entry.relativePath)
            ensureParent(target, state.createdDirectories)
            if (moveSource && entry.action == MigrationAction.Move) {
                Files.move(source, target)
                state.moved.add(target to source)
            } else {
                Files.copy(source, target)
                state.copied.add(target)
            }
            context.progress(
                (index + 1).toFloat() / transfer.size,
                I18n.t("core.isolationMigration.32", label, index + 1, transfer.size),
            )
        }
    }

    private fun validatePlan(plan: IsolationPlan) {
        require(plan.blockers.isEmpty()) { plan.blockers.first() }
    }

    private fun validateRoots(
        root: Path,
        sourceRoot: Path,
        targetRoot: Path,
    ) {
        require(root == sourceRoot || root == targetRoot) { I18n.t("core.isolationMigration.33") }
    }

    private fun validateTransfer(
        transfer: List<MigrationEntry>,
        copySaves: Boolean,
    ) {
        require(
            copySaves ||
                transfer.none {
                    it.relativePath == "saves" ||
                        it.relativePath.startsWith(
                            "saves/",
                        )
                },
        ) {
            I18n.t("core.isolationMigration.34")
        }
    }

    private fun transferSource(
        sourceRoot: Path,
        relativePath: String,
    ): Path {
        val source = safePath(sourceRoot, relativePath)
        require(
            source.isRegularFile(
                LinkOption.NOFOLLOW_LINKS,
            ) && !Files.isSymbolicLink(source),
        ) {
            I18n.t("core.isolationMigration.35", relativePath)
        }
        return source
    }

    /** 回滚已移动/复制的文件与本次创建的目录；有未恢复项时保留迁移清单并返回带说明的异常。 */
    private fun rollback(
        error: Throwable,
        state: TransferState,
    ): Throwable {
        val failures = mutableListOf<Throwable>()
        state.moved.asReversed().forEach { (target, source) ->
            runCatching {
                source.parent?.createDirectories()
                Files.move(target, source, StandardCopyOption.REPLACE_EXISTING)
            }.onFailure { failures += it }
        }
        state.copied.asReversed().forEach { target ->
            runCatching { Files.deleteIfExists(target) }.onFailure { failures += it }
        }
        state.createdDirectories
            .sortedByDescending { it.nameCount }
            .forEach { directory ->
                runCatching { Files.deleteIfExists(directory) }
            }
        if (failures.isNotEmpty()) {
            return IOException(
                I18n.t("core.isolationMigration.36", state.backup, state.targetRoot),
                error,
            ).also { result -> failures.forEach { result.addSuppressed(it) } }
        }
        MigrationManifest.deleteTree(state.backup)
        return error
    }

    /** 目标目录逐级创建，并记录本次新建的目录，回滚时按由深到浅删除空目录。 */
    private fun ensureParent(
        target: Path,
        created: MutableList<Path>,
    ) {
        val parent = target.parent ?: return
        val missing =
            generateSequence(parent) { it.parent }
                .takeWhile { !it.exists(LinkOption.NOFOLLOW_LINKS) }
                .toList()
        parent.createDirectories()
        created.addAll(missing)
    }
}

/**
 * 隔离方式切换与跨游戏目录迁移。
 *
 * 目录语义以 [LauncherStore.gamePath] 为准：共用游戏目录的实例使用游戏根目录，版本隔离的实例
 * 使用自己的目录。切换隔离方式时先搬运文件、再写记录；整体移动时先复制、校验文件数与字节数， 再切记录、最后删源，任何一步失败都不删除源目录。
 */
class IsolationMigration(
    private val store: LauncherStore,
) {
    /** 预览切换隔离方式要搬运的内容；只读，不修改磁盘。 */
    fun planIsolation(
        instanceId: String,
        isolate: Boolean,
        copySaves: Boolean = true,
        moveSource: Boolean = false,
    ): IsolationPlan {
        val instance = store.instance(instanceId)
        require(!instance.externalProfile) { I18n.t("core.isolationMigration.37") }
        val from = currentMode(instance)
        val to = if (isolate) IsolationMode.Isolated else IsolationMode.Shared
        require(from != to) {
            if (isolate) I18n.t("core.isolationMigration.38") else I18n.t("core.isolationMigration.39")
        }
        val sourceRoot = store.gamePath(instance)
        val targetRoot =
            if (isolate) store.versionGamePath(instance) else store.minecraftRoot(instance)
        require(
            sourceRoot.toAbsolutePath().normalize() != targetRoot.toAbsolutePath().normalize(),
        ) {
            I18n.t("core.isolationMigration.40")
        }
        require(sourceRoot.isDirectory(LinkOption.NOFOLLOW_LINKS)) { I18n.t("core.isolationMigration.41") }
        return IsolationMigrationPlanner.plan(
            instance = instance,
            from = from,
            to = to,
            sourceRoot = sourceRoot,
            targetRoot = targetRoot,
            copySaves = copySaves,
            moveSource = moveSource,
        )
    }

    /** 在任务队列里执行隔离切换；失败或取消时回滚文件与实例记录。 */
    suspend fun changeIsolation(
        instanceId: String,
        isolate: Boolean,
        copySaves: Boolean = true,
        moveSource: Boolean = false,
        context: JobContext,
    ): Int =
        withContext(Dispatchers.IO) {
            val plan = planIsolation(instanceId, isolate, copySaves, moveSource)
            require(plan.blockers.isEmpty()) { plan.blockers.first() }
            context.progress(0f, I18n.t("core.isolationMigration.42", plan.to.title, plan.transferCount))
            val transaction =
                IsolationMigrationTransaction(
                    store,
                    plan.sourceRoot,
                    backupDirectory("isolation", instanceId),
                )
            val executed =
                transaction.apply(plan, context, copySaves, moveSource) { record ->
                    store.mutate { data ->
                        data.copy(
                            instances =
                                data.instances.map {
                                    if (it.id == record.id) {
                                        it.copy(isolation = record.isolation)
                                    } else {
                                        it
                                    }
                                },
                        )
                    }
                }
            store.log(
                I18n.t("core.isolationMigration.43", store.instance(instanceId).name, plan.to.title, executed),
            )
            executed
        }

    /** 预览把整个实例目录移动到另一个游戏目录的内容；只读。 */
    fun planInstanceMove(
        instanceId: String,
        destinationGameDirectory: Path,
    ): IsolationPlan {
        val instance = store.instance(instanceId)
        require(!instance.externalProfile) { I18n.t("core.isolationMigration.17") }
        require(store.isolated(instance)) { I18n.t("core.isolationMigration.44") }
        val sourceRoot = store.gamePath(instance)
        require(sourceRoot.isDirectory(LinkOption.NOFOLLOW_LINKS)) { I18n.t("core.isolationMigration.20") }
        val destination = destinationGameDirectory.toAbsolutePath().normalize()
        require(destination.parent != null) { I18n.t("diagA1.isolationMigration.rootDirectory") }
        val targetRoot =
            runCatching {
                safePath(
                    destination.resolve("versions"),
                    instance.gameDirectoryName.ifBlank { instance.versionId },
                )
            }.getOrElse { error(I18n.t("core.isolationMigration.45", it.message)) }
        val blockers = mutableListOf<String>()
        if (targetRoot.exists(LinkOption.NOFOLLOW_LINKS)) {
            blockers += I18n.t("core.isolationMigration.46")
        }
        gameDirectoryAt(destination)?.let { directory ->
            if (
                store.data.instances.any {
                    it.id != instanceId &&
                        it.name == instance.name &&
                        it.gameDirectoryId == directory.id
                }
            ) {
                blockers += I18n.t("core.isolationMigration.47", instance.name)
            }
        }
        return IsolationMigrationPlanner.planMove(
            instance = instance,
            mode = currentMode(instance),
            sourceRoot = sourceRoot,
            targetRoot = targetRoot,
            blockers = blockers,
        )
    }

    /** 先复制到新游戏目录并校验，再切换记录，最后把源目录移入回收站；校验失败不删源。 */
    suspend fun moveInstance(
        instanceId: String,
        destinationGameDirectory: Path,
        context: JobContext,
    ): Path =
        withContext(Dispatchers.IO) {
            val plan = planInstanceMove(instanceId, destinationGameDirectory)
            require(plan.blockers.isEmpty()) { plan.blockers.first() }
            val instance = store.instance(instanceId)
            val sourceRoot = plan.sourceRoot
            val targetRoot = plan.targetRoot
            val destination = destinationGameDirectory.toAbsolutePath().normalize()
            val registered = gameDirectoryAt(destination)
            val directory = registered ?: newGameDirectory(destination)
            destination.createDirectories()
            context.progress(0f, I18n.t("core.isolationMigration.48", plan.transferCount))
            val transaction =
                IsolationMigrationTransaction(
                    store,
                    sourceRoot,
                    backupDirectory("move", instanceId),
                )
            val executed =
                transaction.apply(plan, context, copySaves = true, moveSource = false) {
                    verifyCopy(plan)
                    store.mutate { data ->
                        registerMovedInstance(data, instanceId, registered, directory)
                    }
                }
            val removed = runCatching { store.platform.moveToTrash(sourceRoot) }
            if (removed.isFailure) {
                store.log(I18n.t("diagA1.isolationMigration.sourceLeftover", targetRoot, sourceRoot), true)
            }
            context.progress(1f, I18n.t("core.isolationMigration.49"))
            store.log(I18n.t("core.isolationMigration.50", instance.name, targetRoot, executed))
            targetRoot
        }

    /** 未登记的目标目录按文件夹名生成一条待登记记录；名称取不到时用「游戏目录」。 */
    private fun newGameDirectory(destination: Path): GameDirectory =
        GameDirectory(
            name =
                destination.fileName
                    ?.toString()
                    .orEmpty()
                    .ifBlank { I18n.t("core.isolationMigration.51") },
            path = destination.toString(),
        )

    /** 把实例记录指向新游戏目录，并在目标目录尚未登记时补一条记录。 */
    private fun registerMovedInstance(
        data: LauncherData,
        instanceId: String,
        registered: GameDirectory?,
        directory: GameDirectory,
    ): LauncherData =
        data.copy(
            gameDirectories =
                if (registered == null) {
                    data.gameDirectories + directory
                } else {
                    data.gameDirectories
                },
            instances =
                data.instances.map { current ->
                    if (current.id == instanceId) {
                        current.copy(
                            gameDirectoryId = directory.id,
                            managedGameDirectory = true,
                        )
                    } else {
                        current
                    }
                },
        )

    private fun currentMode(instance: GameInstance): IsolationMode =
        if (store.isolated(instance)) IsolationMode.Isolated else IsolationMode.Shared

    /** 目标游戏目录已登记时返回其记录，未登记返回 null（调用方负责登记）。 */
    private fun gameDirectoryAt(destination: Path): GameDirectory? =
        store.data.gameDirectories.find { registered ->
            val path = runCatching { Path.of(registered.path) }.getOrNull() ?: return@find false
            runCatching { path.toRealPath() == destination.toRealPath() }
                .getOrDefault(path.toAbsolutePath().normalize() == destination)
        }

    private fun backupDirectory(
        kind: String,
        instanceId: String,
    ): Path = store.directories.instanceBackups.resolve("$kind-$instanceId-${System.currentTimeMillis()}")

    /** 复制完成后核对文件数与字节数，避免记录先切过去、文件却没搬全。 */
    private fun verifyCopy(plan: IsolationPlan) {
        val (files, bytes) = measure(plan.targetRoot)
        require(files == plan.transferCount && bytes == plan.copyBytes) {
            I18n.t("core.isolationMigration.52", files, bytes, plan.transferCount, plan.copyBytes)
        }
    }

    private fun measure(root: Path): Pair<Int, Long> {
        if (!root.isDirectory(LinkOption.NOFOLLOW_LINKS)) return 0 to 0L
        var files = 0
        var bytes = 0L
        Files.walk(root).use { paths ->
            val iterator = paths.iterator()
            while (iterator.hasNext()) {
                val path = iterator.next()
                if (!Files.isSymbolicLink(path) &&
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                ) {
                    files++
                    bytes += runCatching { path.fileSize() }.getOrDefault(0L)
                }
            }
        }
        return files to bytes
    }
}
