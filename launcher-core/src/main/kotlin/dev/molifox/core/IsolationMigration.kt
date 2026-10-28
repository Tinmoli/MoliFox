// 负责实例隔离切换与跨游戏目录迁移。

package dev.molifox.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 实例的目录语义：共用游戏目录（多个实例共用 mods/存档），或版本隔离（实例独占目录）。 */
enum class IsolationMode(val title: String) {
    Shared("共用游戏目录"),
    Isolated("版本隔离"),
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
                }
                .sumOf { it.size }

    val moveCount: Int
        get() = entries.count { it.action == MigrationAction.Move }

    val conflictCount: Int
        get() = entries.count { it.action == MigrationAction.Conflict }

    /** 实际需要执行的文件数，不含 Skip 与 Conflict。 */
    val transferCount: Int
        get() = entries.count {
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
        require(from != to) { "当前隔离方式与目标相同，无需迁移" }
        require(!instance.externalProfile) { "外部游戏目录的实例由原启动器维护，无法迁移隔离方式" }
        val source = sourceRoot.toAbsolutePath().normalize()
        val target = targetRoot.toAbsolutePath().normalize()
        require(source != target) { "迁移的源目录与目标目录相同" }
        require(source.isDirectory(LinkOption.NOFOLLOW_LINKS)) { "源目录不存在，无法迁移" }
        // 共用游戏目录的文件可能被其他实例使用，只有实例独占目录才允许搬走。
        val moving = moveSource && from == IsolationMode.Isolated
        val copyOnlyNote =
            if (moveSource && from == IsolationMode.Shared) "（共用游戏目录可能被其他实例使用，只复制）" else ""
        val entries = mutableListOf<MigrationEntry>()
        val blockers = mutableListOf<String>()
        // 版本隔离目录可能位于共用游戏目录之内（instances/<id>），只跳过目标子树，不能跳过整棵源树。
        val nested = if (target.startsWith(source)) target else null
        for ((relative, file) in candidates(source, nested, copySaves, blockers)) {
            val size = runCatching { file.fileSize() }.getOrDefault(0L)
            val destination = runCatching { safePath(target, relative) }.getOrNull()
            if (destination == null) {
                entries += MigrationEntry(relative, MigrationAction.Conflict, size, "目标路径不安全，未迁移")
                blockers += "目标路径不安全：$relative"
                continue
            }
            when {
                !destination.exists(LinkOption.NOFOLLOW_LINKS) ->
                    entries +=
                        MigrationEntry(
                            relative,
                            if (moving) MigrationAction.Move else MigrationAction.Copy,
                            size,
                            transferReason(moving, to) + copyOnlyNote,
                        )
                Files.isSymbolicLink(destination) -> {
                    entries +=
                        MigrationEntry(relative, MigrationAction.Conflict, size, "目标是符号链接，未覆盖")
                    blockers += "目标已是符号链接：$relative"
                }
                destination.isDirectory(LinkOption.NOFOLLOW_LINKS) -> {
                    entries +=
                        MigrationEntry(relative, MigrationAction.Conflict, size, "目标已有同名目录，未覆盖")
                    blockers += "目标已存在同名目录：$relative"
                }
                else -> {
                    val same =
                        destination.fileSize() == size &&
                            runCatching { digest(file) == digest(destination) }.getOrDefault(false)
                    if (same) {
                        entries +=
                            MigrationEntry(relative, MigrationAction.Skip, size, "目标已有相同内容，跳过")
                    } else {
                        entries +=
                            MigrationEntry(
                                relative,
                                MigrationAction.Conflict,
                                size,
                                "目标已有不同内容的文件，未覆盖",
                            )
                        blockers += "目标已存在不同内容的文件：$relative"
                    }
                }
            }
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

    /** 生成整体移动实例目录的计划：源目录下所有普通文件都要搬走，符号链接无法搬运时只记 blocker。 移动不改变隔离方式，因此 `from` 与 `to` 相同。 */
    fun planMove(
        instance: GameInstance,
        mode: IsolationMode,
        sourceRoot: Path,
        targetRoot: Path,
        blockers: List<String> = emptyList(),
    ): IsolationPlan {
        require(!instance.externalProfile) { "外部游戏目录的实例由原启动器维护，无法移动" }
        val source = sourceRoot.toAbsolutePath().normalize()
        val target = targetRoot.toAbsolutePath().normalize()
        require(source != target) { "实例已经在该游戏目录中" }
        require(!target.startsWith(source) && !source.startsWith(target)) {
            "目标目录不能与实例目录互相包含"
        }
        require(source.isDirectory(LinkOption.NOFOLLOW_LINKS)) { "实例目录不存在，无法移动" }
        val problems = blockers.toMutableList()
        val files = mutableListOf<Pair<String, Path>>()
        var links = 0
        Files.walk(source).use { paths ->
            val iterator = paths.iterator()
            while (iterator.hasNext()) {
                val path = iterator.next()
                if (path == source) continue
                if (Files.isSymbolicLink(path)) {
                    links++
                    continue
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue
                files += source.relativize(path).toString().replace('\\', '/') to path
            }
        }
        if (links > 0) problems += "实例目录含 $links 个符号链接，暂不支持整体移动"
        val entries = mutableListOf<MigrationEntry>()
        for ((relative, file) in files.sortedBy { it.first }) {
            val size = runCatching { file.fileSize() }.getOrDefault(0L)
            val destination = runCatching { safePath(target, relative) }.getOrNull()
            if (destination == null) {
                problems += "目标路径不安全：$relative"
                continue
            }
            if (destination.exists(LinkOption.NOFOLLOW_LINKS)) {
                entries += MigrationEntry(relative, MigrationAction.Conflict, size, "目标已有同名文件，未覆盖")
                problems += "目标已存在同名文件：$relative"
            } else {
                entries += MigrationEntry(relative, MigrationAction.Copy, size, "复制到新的游戏目录")
            }
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

    private fun transferReason(moving: Boolean, to: IsolationMode): String =
        if (moving) "移动到${to.title}并删除源文件" else "复制到${to.title}"

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
                blockers += "$name 是符号链接，暂不支持迁移"
                continue
            }
            when {
                root.isDirectory(LinkOption.NOFOLLOW_LINKS) ->
                    walk(root, excluded, source, blockers, result)
                root.isRegularFile(LinkOption.NOFOLLOW_LINKS) -> result += name to root
            }
        }
        for (name in migrationFiles) {
            val file = source.resolve(name)
            if (Files.isSymbolicLink(file)) {
                blockers += "$name 是符号链接，暂不支持迁移"
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
                    continue
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue
                result += source.relativize(path).toString().replace('\\', '/') to path
            }
        }
        if (links > 0) {
            blockers +=
                "${source.relativize(root).toString().replace('\\', '/')} 内含 $links 个符号链接，暂不支持迁移"
        }
    }

    /** `copySaves=false` 时给出一条整目录 Skip，并统计存档体积供界面提示。 */
    private fun savesSkip(source: Path): MigrationEntry? {
        val saves = source.resolve("saves")
        if (!saves.isDirectory(LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(saves))
            return null
        var bytes = 0L
        Files.walk(saves).use { paths ->
            paths.forEach {
                if (Files.isSymbolicLink(it)) return@forEach
                if (Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS)) bytes += it.fileSize()
            }
        }
        return MigrationEntry("saves", MigrationAction.Skip, bytes, "未勾选复制存档，存档保留在原目录")
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
        require(plan.blockers.isEmpty()) { plan.blockers.first() }
        val original = store.instance(plan.instanceId)
        val sourceRoot = plan.sourceRoot.toAbsolutePath().normalize()
        val targetRoot = plan.targetRoot.toAbsolutePath().normalize()
        val root = instanceRoot.toAbsolutePath().normalize()
        require(root == sourceRoot || root == targetRoot) { "迁移计划与实例目录不一致，请重新生成计划" }
        val transfer =
            plan.entries.filter {
                it.action == MigrationAction.Copy || it.action == MigrationAction.Move
            }
        require(
            copySaves ||
                transfer.none { it.relativePath == "saves" || it.relativePath.startsWith("saves/") }
        ) {
            "迁移计划包含存档，但已选择不迁移存档，请重新生成计划"
        }
        val backup = backupRoot.toAbsolutePath().normalize()
        val label = if (plan.from == plan.to) "移动实例" else "切换隔离"
        val copied = mutableListOf<Path>()
        val moved = mutableListOf<Pair<Path, Path>>()
        val createdDirectories = mutableListOf<Path>()
        var recordUpdated = false
        var committed = false
        backup.createDirectories()
        writeManifest(backup, plan)
        try {
            transfer.forEachIndexed { index, entry ->
                context.checkCancelled()
                val source = safePath(sourceRoot, entry.relativePath)
                require(
                    source.isRegularFile(LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(source)
                ) {
                    "源文件已不存在，请重新生成迁移计划：${entry.relativePath}"
                }
                val target = safePath(targetRoot, entry.relativePath)
                ensureParent(target, createdDirectories)
                if (moveSource && entry.action == MigrationAction.Move) {
                    Files.move(source, target)
                    moved.add(target to source)
                } else {
                    Files.copy(source, target)
                    copied.add(target)
                }
                context.progress(
                    (index + 1).toFloat() / transfer.size,
                    "$label · 文件 ${index + 1}/${transfer.size}",
                )
            }
            context.checkCancelled()
            val record =
                if (plan.from == plan.to) original
                else original.copy(isolation = plan.to.versionIsolation())
            updateInstance(record)
            recordUpdated = true
            committed = true
            deleteTree(backup)
            return transfer.size
        } catch (error: Throwable) {
            if (committed) throw error
            val failures = mutableListOf<Throwable>()
            moved.asReversed().forEach { (target, source) ->
                runCatching {
                    source.parent?.createDirectories()
                    Files.move(target, source, StandardCopyOption.REPLACE_EXISTING)
                }
                    .onFailure { failures += it }
            }
            copied.asReversed().forEach { target ->
                runCatching { Files.deleteIfExists(target) }.onFailure { failures += it }
            }
            createdDirectories
                .sortedByDescending { it.nameCount }
                .forEach { directory ->
                    runCatching { Files.deleteIfExists(directory) }
                }
            if (recordUpdated) {
                runCatching { store.updateInstance(original.id) { original } }
                    .onFailure { failures += it }
            }
            if (failures.isNotEmpty()) {
                val failure =
                    IOException(
                        "迁移未完成，迁移清单保留在 $backup，未恢复的文件仍在 $targetRoot，请先手动恢复后再操作",
                        error,
                    )
                failures.forEach { failure.addSuppressed(it) }
                throw failure
            }
            deleteTree(backup)
            throw error
        }
    }

    /** 目标目录逐级创建，并记录本次新建的目录，回滚时按由深到浅删除空目录。 */
    private fun ensureParent(target: Path, created: MutableList<Path>) {
        val parent = target.parent ?: return
        val missing =
            generateSequence(parent) { it.parent }
                .takeWhile { !it.exists(LinkOption.NOFOLLOW_LINKS) }
                .toList()
        parent.createDirectories()
        created.addAll(missing)
    }

    /** 迁移清单供回滚失败时人工核对；成功或完整回滚后整个目录删除。 */
    private fun writeManifest(directory: Path, plan: IsolationPlan) {
        val text = buildString {
            appendLine("MoliFox 迁移清单")
            appendLine("实例：${plan.instanceId}")
            appendLine("方向：${plan.from.title} → ${plan.to.title}")
            appendLine("源目录：${plan.sourceRoot.toAbsolutePath()}")
            appendLine("目标目录：${plan.targetRoot.toAbsolutePath()}")
            appendLine("条目：${plan.entries.size}")
            plan.entries.forEach {
                appendLine("${it.action} ${it.relativePath} ${it.size} ${it.reason}")
            }
        }
        directory.resolve("isolation-plan.txt").writeText(text)
    }

    private fun deleteTree(directory: Path) {
        if (!directory.exists(LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}

/**
 * 隔离方式切换与跨游戏目录迁移。
 *
 * 目录语义以 [LauncherStore.gamePath] 为准：共用游戏目录的实例使用游戏根目录，版本隔离的实例
 * 使用自己的目录。切换隔离方式时先搬运文件、再写记录；整体移动时先复制、校验文件数与字节数， 再切记录、最后删源，任何一步失败都不删除源目录。
 */
class IsolationMigration(private val store: LauncherStore) {

    /** 预览切换隔离方式要搬运的内容；只读，不修改磁盘。 */
    fun planIsolation(
        instanceId: String,
        isolate: Boolean,
        copySaves: Boolean = true,
        moveSource: Boolean = false,
    ): IsolationPlan {
        val instance = store.instance(instanceId)
        require(!instance.externalProfile) { "外部游戏目录的实例由原启动器维护，无法切换隔离方式" }
        val from = currentMode(instance)
        val to = if (isolate) IsolationMode.Isolated else IsolationMode.Shared
        require(from != to) { if (isolate) "此实例已经启用版本隔离" else "此实例已经共用游戏目录" }
        val sourceRoot = store.gamePath(instance)
        val targetRoot =
            if (isolate) store.versionGamePath(instance) else store.minecraftRoot(instance)
        require(
            sourceRoot.toAbsolutePath().normalize() != targetRoot.toAbsolutePath().normalize()
        ) {
            "切换隔离方式不会改变此实例的目录，无法迁移"
        }
        require(sourceRoot.isDirectory(LinkOption.NOFOLLOW_LINKS)) { "实例目录不存在，无法切换隔离方式" }
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
            context.progress(0f, "准备${plan.to.title} · ${plan.transferCount} 个文件")
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
                            settings = data.settings.copy(isolateVersions = isolate),
                            instances =
                                data.instances.map {
                                    if (it.id == record.id) it.copy(isolation = record.isolation)
                                    else it
                                },
                        )
                    }
                }
            store.log(
                "已切换隔离方式：${store.instance(instanceId).name} → ${plan.to.title}（$executed 个文件）"
            )
            executed
        }

    /** 预览把整个实例目录移动到另一个游戏目录的内容；只读。 */
    fun planInstanceMove(instanceId: String, destinationGameDirectory: Path): IsolationPlan {
        val instance = store.instance(instanceId)
        require(!instance.externalProfile) { "外部游戏目录的实例由原启动器维护，无法移动" }
        require(store.isolated(instance)) { "共用游戏目录的实例没有独立目录，无法移动，请先启用版本隔离" }
        val sourceRoot = store.gamePath(instance)
        require(sourceRoot.isDirectory(LinkOption.NOFOLLOW_LINKS)) { "实例目录不存在，无法移动" }
        val destination = destinationGameDirectory.toAbsolutePath().normalize()
        require(destination.parent != null) { "请选择专用的游戏文件夹，而非磁盘根目录" }
        val targetRoot = runCatching {
            safePath(
                destination.resolve("versions"),
                instance.gameDirectoryName.ifBlank { instance.versionId },
            )
        }
            .getOrElse { error("目标游戏目录不可用：${it.message}") }
        val blockers = mutableListOf<String>()
        if (targetRoot.exists(LinkOption.NOFOLLOW_LINKS)) {
            blockers += "目标游戏目录中已存在同名实例文件夹，请先移除或换一个目录"
        }
        gameDirectoryAt(destination)?.let { directory ->
            if (
                store.data.instances.any {
                    it.id != instanceId &&
                        it.name == instance.name &&
                        it.gameDirectoryId == directory.id
                }
            ) {
                blockers += "目标游戏目录中已存在同名实例：${instance.name}"
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
            val directory =
                registered
                    ?: GameDirectory(
                        name = destination.fileName?.toString().orEmpty().ifBlank { "游戏目录" },
                        path = destination.toString(),
                    )
            destination.createDirectories()
            context.progress(0f, "准备移动实例 · ${plan.transferCount} 个文件")
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
                        data.copy(
                            gameDirectories =
                                if (registered == null) data.gameDirectories + directory
                                else data.gameDirectories,
                            instances =
                                data.instances.map { current ->
                                    if (current.id == instanceId)
                                        current.copy(
                                            gameDirectoryId = directory.id,
                                            managedGameDirectory = true,
                                        )
                                    else current
                                },
                        )
                    }
                }
            val removed = runCatching { store.platform.moveToTrash(sourceRoot) }
            if (removed.isFailure) {
                store.log("实例已移动到 $targetRoot，但原目录未能删除，保留在 $sourceRoot", true)
            }
            context.progress(1f, "移动实例完成")
            store.log("已移动实例：${instance.name} → $targetRoot（$executed 个文件）")
            targetRoot
        }

    private fun currentMode(instance: GameInstance): IsolationMode =
        if (store.isolated(instance)) IsolationMode.Isolated else IsolationMode.Shared

    /** 目标游戏目录已登记时返回其记录，未登记返回 null（调用方负责登记）。 */
    private fun gameDirectoryAt(destination: Path): GameDirectory? =
        store.data.gameDirectories.find { registered ->
            val path = runCatching { Path.of(registered.path) }.getOrNull() ?: return@find false
            runCatching { path.toRealPath() == destination.toRealPath() }
                .getOrDefault(path.toAbsolutePath().normalize() == destination)
        }

    private fun backupDirectory(kind: String, instanceId: String): Path =
        store.directories.instanceBackups.resolve("$kind-$instanceId-${System.currentTimeMillis()}")

    /** 复制完成后核对文件数与字节数，避免记录先切过去、文件却没搬全。 */
    private fun verifyCopy(plan: IsolationPlan) {
        val (files, bytes) = measure(plan.targetRoot)
        require(files == plan.transferCount && bytes == plan.copyBytes) {
            "复制校验失败：目标 ${files} 个文件 / ${bytes} 字节，应为 ${plan.transferCount} 个 / ${plan.copyBytes} 字节"
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
                if (Files.isSymbolicLink(path)) continue
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue
                files++
                bytes += runCatching { path.fileSize() }.getOrDefault(0L)
            }
        }
        return files to bytes
    }
}
