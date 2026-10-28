// 负责整合包升级计划、受保护文件和回滚。

package dev.molifox.core

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.*

/** 升级计划里单个文件的动作；desktop-app 需要据此展示预览列表。 */
enum class PackFileAction {
    Add,
    Replace,
    Remove,
    Keep,
}

/** 文件相对整合包账本的用户状态；desktop-app 需要据此解释受保护文件。 */
enum class PackUserState {
    Unchanged,
    UserModified,
    UserAdded,
    UserDeleted,
}

/** 升级预览中的单条文件变更；对 desktop-app 可见以展示「新增/替换/移除/保留」。 */
data class PackFileChange(
    val path: String,
    val action: PackFileAction,
    val userState: PackUserState,
    val reason: String,
    val optional: Boolean = false,
)

/** 升级策略；桌面端预览与执行使用同一套策略，避免两边判断不一致。 */
data class PackUpgradePolicy(
    val protectUserModified: Boolean = true,
    val restoreUserDeleted: Boolean = false,
    val removeObsoleteFiles: Boolean = true,
)

internal data class PackFileState(val present: Boolean, val sha1: String?)

/** 升级预览结果；对 desktop-app 可见，用于展示版本变化与文件明细。 */
data class PackUpgradePlan(
    val fromVersionId: String,
    val toVersionId: String,
    val requiresComponentChange: Boolean,
    val changes: List<PackFileChange>,
    val protectedPaths: List<String> = emptyList(),
    val archiveSha256: String = "",
) {
    val adds: Int
        get() = changes.count { it.action == PackFileAction.Add }

    val replaces: Int
        get() = changes.count { it.action == PackFileAction.Replace }

    val removes: Int
        get() = changes.count { it.action == PackFileAction.Remove }

    val keeps: Int
        get() = changes.count { it.action == PackFileAction.Keep }
}

/** 纯函数规划器：只比较旧账本、新账本和磁盘现状，不读写文件。 */
internal object PackUpgradePlanner {
    fun plan(
        instanceId: String,
        old: PackOwnership,
        new: PackOwnership,
        current: Map<String, PackFileState>,
        policy: PackUpgradePolicy = PackUpgradePolicy(),
    ): PackUpgradePlan {
        require(instanceId.isNotBlank()) { "实例标识无效" }
        val oldFiles = old.files.associateBy { packRelative(it.path) }
        val newFiles = new.files.associateBy { packRelative(it.path) }
        val states = current.mapKeys { packRelative(it.key) }
        val changes = mutableListOf<PackFileChange>()
        val protectedPaths = mutableListOf<String>()
        for (path in (oldFiles.keys + newFiles.keys + states.keys).sorted()) {
            val state = states[path] ?: PackFileState(false, null)
            val oldFile = oldFiles[path]
            val newFile = newFiles[path]
            changes +=
                when {
                    newFile != null ->
                        incoming(path, newFile, oldFile, state, policy, protectedPaths)
                    oldFile != null -> obsolete(path, oldFile, state, policy, protectedPaths)
                    else ->
                        PackFileChange(
                            path,
                            PackFileAction.Keep,
                            PackUserState.UserAdded,
                            "保留用户自加文件",
                        )
                }
        }
        return PackUpgradePlan(
            fromVersionId = old.versionId,
            toVersionId = new.versionId,
            requiresComponentChange =
                old.gameVersion != new.gameVersion || old.loader != new.loader,
            changes = changes,
            protectedPaths = protectedPaths,
            archiveSha256 = new.archiveSha256,
        )
    }

    private fun incoming(
        path: String,
        newFile: PackOwnedFile,
        oldFile: PackOwnedFile?,
        state: PackFileState,
        policy: PackUpgradePolicy,
        protectedPaths: MutableList<String>,
    ): PackFileChange {
        val optional = newFile.optional
        if (!state.present || state.sha1 == null) {
            val deleted = oldFile != null
            if (deleted && !policy.restoreUserDeleted)
                return PackFileChange(
                    path,
                    PackFileAction.Keep,
                    PackUserState.UserDeleted,
                    "用户删除的整合包文件保持缺失",
                    optional,
                )
            return PackFileChange(
                path,
                PackFileAction.Add,
                if (deleted) PackUserState.UserDeleted else PackUserState.Unchanged,
                if (deleted) "恢复用户删除的整合包文件" else "新增整合包文件",
                optional,
            )
        }
        if (state.sha1.equals(newFile.sha1, true))
            return PackFileChange(
                path,
                PackFileAction.Keep,
                PackUserState.Unchanged,
                "文件已是新整合包版本",
                optional,
            )
        if (oldFile != null && state.sha1.equals(oldFile.sha1, true))
            return PackFileChange(
                path,
                PackFileAction.Replace,
                PackUserState.Unchanged,
                "整合包更新文件",
                optional,
            )
        if (policy.protectUserModified) {
            protectedPaths += path
            return PackFileChange(
                path,
                PackFileAction.Keep,
                PackUserState.UserModified,
                "保留用户修改的文件",
                optional,
            )
        }
        return PackFileChange(
            path,
            PackFileAction.Replace,
            PackUserState.UserModified,
            "按策略覆盖用户修改的文件",
            optional,
        )
    }

    private fun obsolete(
        path: String,
        oldFile: PackOwnedFile,
        state: PackFileState,
        policy: PackUpgradePolicy,
        protectedPaths: MutableList<String>,
    ): PackFileChange {
        if (!state.present || state.sha1 == null)
            return PackFileChange(
                path,
                PackFileAction.Keep,
                PackUserState.UserDeleted,
                "用户已删除的旧整合包文件",
            )
        if (state.sha1.equals(oldFile.sha1, true))
            return if (policy.removeObsoleteFiles)
                PackFileChange(
                    path,
                    PackFileAction.Remove,
                    PackUserState.Unchanged,
                    "新整合包已移除该文件",
                )
            else
                PackFileChange(
                    path,
                    PackFileAction.Keep,
                    PackUserState.Unchanged,
                    "按策略保留新整合包已移除的文件",
                )
        if (policy.protectUserModified) {
            protectedPaths += path
            return PackFileChange(
                path,
                PackFileAction.Keep,
                PackUserState.UserModified,
                "保留用户修改的旧文件",
            )
        }
        return PackFileChange(
            path,
            PackFileAction.Remove,
            PackUserState.UserModified,
            "按策略移除用户修改的旧文件",
        )
    }
}

/**
 * 升级事务：备份 → 落新文件 → 写新账本/新归档/新实例记录。
 *
 * 任何异常（含 CancellationException）都会把文件、账本、归档、实例记录一起回滚； 回滚失败时保留备份目录并抛出带备份路径的 IOException。
 */
internal class PackUpgradeTransaction(
    private val store: LauncherStore,
    instanceRoot: Path,
    backup: Path,
) {
    private val root = instanceRoot.toAbsolutePath().normalize()
    val directory: Path = backup.toAbsolutePath().normalize()
    private var retain = false

    init {
        require(directory.parent == root) { "升级备份目录必须直接位于实例目录下" }
        require(directory.name.startsWith(".molifox-pack-upgrade-")) { "升级备份目录名称无效" }
    }

    suspend fun apply(
        plan: PackUpgradePlan,
        staging: Path,
        archiveSource: Path,
        archiveTarget: Path,
        ownership: PackOwnership,
        instanceId: String,
        updateInstance: (GameInstance) -> GameInstance,
        context: JobContext,
    ) {
        context.checkCancelled()
        val content = staging.toAbsolutePath().normalize()
        require(content.startsWith(root) && content != root) { "升级暂存目录必须位于实例目录内" }
        require(archiveSource.isRegularFile() && !Files.isSymbolicLink(archiveSource)) {
            "新整合包归档不可用"
        }
        require(plan.changes.map { packRelative(it.path) }.distinct().size == plan.changes.size) {
            "升级计划包含重复路径"
        }
        val replacing =
            plan.changes.filter {
                it.action == PackFileAction.Replace || it.action == PackFileAction.Remove
            }
        val installing =
            plan.changes.filter {
                it.action == PackFileAction.Add || it.action == PackFileAction.Replace
            }
        for (change in installing) {
            val source = packPath(content, change.path)
            require(source.isRegularFile() && !Files.isSymbolicLink(source)) {
                "暂存文件缺失：${change.path}"
            }
        }
        val ledger = safePath(root, PACK_OWNERSHIP_FILE)
        val archive = archiveTarget.toAbsolutePath().normalize()
        val oldInstance = store.instance(instanceId)
        val backup = directory.createDirectories()
        val backups = linkedMapOf<Path, Path>()
        val installed = mutableListOf<Path>()
        var ledgerBackup: Path? = null
        var archiveBackup: Path? = null
        var ledgerWritten = false
        var archiveWritten = false
        var instanceUpdated = false
        try {
            for ((position, change) in replacing.withIndex()) {
                context.checkCancelled()
                val target = packPath(root, change.path)
                if (!target.exists()) continue
                require(target.isRegularFile() && !Files.isSymbolicLink(target)) {
                    "升级目标不是普通文件：${change.path}"
                }
                val saved = safePath(backup, "files/$position/${target.fileName}")
                saved.parent.createDirectories()
                Files.move(target, saved)
                backups[target] = saved
            }
            if (ledger.exists()) {
                require(ledger.isRegularFile() && !Files.isSymbolicLink(ledger)) {
                    "文件归属账本不是普通文件"
                }
                ledgerBackup = safePath(backup, "ledger.json").also { Files.copy(ledger, it) }
            }
            if (archive.exists()) {
                require(archive.isRegularFile() && !Files.isSymbolicLink(archive)) {
                    "旧整合包归档不是普通文件"
                }
                archiveBackup =
                    safePath(backup, "archive").createDirectories().resolve(archive.fileName).also {
                        Files.copy(archive, it)
                    }
            }
            for (change in installing) {
                context.checkCancelled()
                val source = packPath(content, change.path)
                val target = packPath(root, change.path)
                target.parent.createDirectories()
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
                installed.add(target)
            }
            context.checkCancelled()
            PackOwnershipStore.write(root, ownership)
            ledgerWritten = true
            val parent = archive.parent ?: throw IOException("整合包归档路径无效")
            parent.createDirectories()
            val part = Files.createTempFile(parent, "${archive.fileName}.", ".part")
            try {
                Files.copy(archiveSource, part, StandardCopyOption.REPLACE_EXISTING)
                try {
                    Files.move(
                        part,
                        archive,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(part, archive, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                part.deleteIfExists()
            }
            archiveWritten = true
            context.checkCancelled()
            store.updateInstance(instanceId, updateInstance)
            instanceUpdated = true
        } catch (error: Throwable) {
            val failures = mutableListOf<Throwable>()
            runCatching {
                installed.asReversed().forEach { Files.deleteIfExists(it) }
                for ((target, saved) in backups.entries.toList().asReversed()) Files.move(
                    saved,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                )
                if (ledgerWritten) restoreOrDelete(ledger, ledgerBackup)
                if (archiveWritten) restoreOrDelete(archive, archiveBackup)
            }
                .onFailure { failures += it }
            if (instanceUpdated)
                runCatching { store.updateInstance(instanceId) { oldInstance } }
                    .onFailure { failures += it }
            if (failures.isNotEmpty()) {
                retain = true
                throw IOException("整合包升级未完成，原文件备份保留在 $directory", error).also {
                    failures.forEach(it::addSuppressed)
                }
            }
            cleanup()
            throw error
        }
        cleanup()
    }

    fun cleanup() {
        if (retain) return
        if (!directory.exists()) return
        runCatching {
            check(directory.parent == root && directory.name.startsWith(".molifox-pack-upgrade-"))
            check(!Files.isSymbolicLink(directory)) { "升级备份目录是符号链接" }
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
            .onFailure { store.log("整合包升级备份清理失败：$directory", true) }
    }
}

private fun restoreOrDelete(target: Path, backup: Path?) {
    if (backup == null) Files.deleteIfExists(target)
    else Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING)
}
