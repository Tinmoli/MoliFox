// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责整合包升级计划、受保护文件和回滚。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

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

internal data class PackFileState(
    val present: Boolean,
    val sha1: String?,
)

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
        require(instanceId.isNotBlank()) { I18n.t("packsB2.packUpgrade.invalidInstanceId") }
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
                            I18n.t("core.packUpgrade.1"),
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
        val current = state.sha1
        if (!state.present || current == null) {
            return missingIncoming(path, optional, oldFile != null, policy)
        }
        return when {
            current.equals(newFile.sha1, true) ->
                change(path, PackFileAction.Keep, PackUserState.Unchanged, I18n.t("core.packUpgrade.2"), optional)
            oldFile != null && current.equals(oldFile.sha1, true) ->
                change(path, PackFileAction.Replace, PackUserState.Unchanged, I18n.t("core.packUpgrade.3"), optional)
            policy.protectUserModified -> {
                protectedPaths += path
                change(path, PackFileAction.Keep, PackUserState.UserModified, I18n.t("core.packUpgrade.4"), optional)
            }
            else ->
                change(
                    path,
                    PackFileAction.Replace,
                    PackUserState.UserModified,
                    I18n.t("core.packUpgrade.5"),
                    optional,
                )
        }
    }

    /** 磁盘上缺少新整合包文件时的结论：按策略恢复用户删除的文件，或保持缺失。 */
    private fun missingIncoming(
        path: String,
        optional: Boolean,
        deleted: Boolean,
        policy: PackUpgradePolicy,
    ): PackFileChange {
        if (deleted && !policy.restoreUserDeleted) {
            return change(
                path,
                PackFileAction.Keep,
                PackUserState.UserDeleted,
                I18n.t("core.packUpgrade.6"),
                optional,
            )
        }
        return change(
            path,
            PackFileAction.Add,
            if (deleted) PackUserState.UserDeleted else PackUserState.Unchanged,
            if (deleted) I18n.t("core.packUpgrade.7") else I18n.t("core.packUpgrade.8"),
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
        if (!state.present || state.sha1 == null) {
            return change(path, PackFileAction.Keep, PackUserState.UserDeleted, I18n.t("core.packUpgrade.9"))
        }
        if (state.sha1.equals(oldFile.sha1, true)) {
            return if (policy.removeObsoleteFiles) {
                change(path, PackFileAction.Remove, PackUserState.Unchanged, I18n.t("core.packUpgrade.10"))
            } else {
                change(path, PackFileAction.Keep, PackUserState.Unchanged, I18n.t("core.packUpgrade.11"))
            }
        }
        if (policy.protectUserModified) {
            protectedPaths += path
            return change(path, PackFileAction.Keep, PackUserState.UserModified, I18n.t("core.packUpgrade.12"))
        }
        return change(path, PackFileAction.Remove, PackUserState.UserModified, I18n.t("core.packUpgrade.13"))
    }

    private fun change(
        path: String,
        action: PackFileAction,
        userState: PackUserState,
        reason: String,
        optional: Boolean = false,
    ): PackFileChange = PackFileChange(path, action, userState, reason, optional)
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
        require(directory.parent == root) { I18n.t("core.packUpgrade.14") }
        require(directory.name.startsWith(".molifox-pack-upgrade-")) {
            I18n.t("packsB2.packUpgrade.invalidBackupName")
        }
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
        validateUpgradeDirectories(content, archiveSource)
        validateUpgradePlan(plan)
        val installing =
            plan.changes.filter {
                it.action == PackFileAction.Add || it.action == PackFileAction.Replace
            }
        validateStagedFiles(content, installing)
        val ledger = safePath(root, PACK_OWNERSHIP_FILE)
        val archive = archiveTarget.toAbsolutePath().normalize()
        val oldInstance = store.instance(instanceId)
        val backup = directory.createDirectories()
        val state = UpgradeWriteState(ledger, archive)
        runCatching {
            backupCurrentFiles(state, plan.changes, backup, context)
            installStagedFiles(state, installing, content, context)
            writeLedgerAndArchive(state, archiveSource, ownership, context)
            context.checkCancelled()
            store.updateInstance(instanceId, updateInstance)
            state.instanceUpdated = true
        }.onFailure { error ->
            // 升级事务的总出口：任何 Throwable（含取消与 Error）都必须先回滚再抛出，不能收窄。
            throw rollbackUpgrade(error, instanceId, oldInstance, state)
        }
        cleanup()
    }

    /** 把将被替换/移除的旧文件移入备份目录，并保留旧账本与旧归档的副本。 */
    private suspend fun backupCurrentFiles(
        state: UpgradeWriteState,
        changes: List<PackFileChange>,
        backup: Path,
        context: JobContext,
    ) {
        val replacing =
            changes.filter {
                it.action == PackFileAction.Replace || it.action == PackFileAction.Remove
            }
        for ((position, change) in replacing.withIndex()) {
            context.checkCancelled()
            val target = packPath(root, change.path)
            if (!target.exists()) continue
            requireUpgradeFile(target, I18n.t("core.packUpgrade.15", change.path))
            val saved = safePath(backup, "files/$position/${target.fileName}")
            saved.parent.createDirectories()
            Files.move(target, saved)
            state.backups[target] = saved
        }
        if (state.ledger.exists()) {
            requireUpgradeFile(state.ledger, I18n.t("core.packUpgrade.16"))
            state.ledgerBackup =
                safePath(backup, "ledger.json").also { Files.copy(state.ledger, it) }
        }
        if (state.archive.exists()) {
            requireUpgradeFile(state.archive, I18n.t("core.packUpgrade.17"))
            state.archiveBackup =
                safePath(backup, "archive")
                    .createDirectories()
                    .resolve(state.archive.fileName)
                    .also { Files.copy(state.archive, it) }
        }
    }

    private suspend fun installStagedFiles(
        state: UpgradeWriteState,
        installing: List<PackFileChange>,
        content: Path,
        context: JobContext,
    ) {
        for (change in installing) {
            context.checkCancelled()
            val source = packPath(content, change.path)
            val target = packPath(root, change.path)
            target.parent.createDirectories()
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
            state.installed.add(target)
        }
    }

    /** 落新账本，并把新归档经临时文件原子改名到目标位置。 */
    private suspend fun writeLedgerAndArchive(
        state: UpgradeWriteState,
        archiveSource: Path,
        ownership: PackOwnership,
        context: JobContext,
    ) {
        context.checkCancelled()
        PackOwnershipStore.write(root, ownership)
        state.ledgerWritten = true
        val parent = archiveParent(state.archive)
        parent.createDirectories()
        val part = Files.createTempFile(parent, "${state.archive.fileName}.", ".part")
        try {
            Files.copy(archiveSource, part, StandardCopyOption.REPLACE_EXISTING)
            try {
                Files.move(
                    part,
                    state.archive,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(part, state.archive, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            part.deleteIfExists()
        }
        state.archiveWritten = true
    }

    /** 升级事务里已完成的写入；回滚时按此逐项撤销。 */
    private class UpgradeWriteState(
        val ledger: Path,
        val archive: Path,
    ) {
        val backups: MutableMap<Path, Path> = linkedMapOf()
        val installed: MutableList<Path> = mutableListOf()
        var ledgerBackup: Path? = null
        var archiveBackup: Path? = null
        var ledgerWritten: Boolean = false
        var archiveWritten: Boolean = false
        var instanceUpdated: Boolean = false
    }

    private fun validateUpgradeDirectories(
        content: Path,
        archiveSource: Path,
    ) {
        require(content.startsWith(root) && content != root) {
            I18n.t("packsB2.packUpgrade.stagingOutsideRoot")
        }
        require(archiveSource.isRegularFile() && !Files.isSymbolicLink(archiveSource)) {
            I18n.t("core.packUpgrade.18")
        }
    }

    private fun validateUpgradePlan(plan: PackUpgradePlan) {
        require(
            plan.changes
                .map { packRelative(it.path) }
                .distinct()
                .size == plan.changes.size,
        ) {
            I18n.t("core.packUpgrade.19")
        }
    }

    private fun validateStagedFiles(
        content: Path,
        installing: List<PackFileChange>,
    ) {
        for (change in installing) {
            val source = packPath(content, change.path)
            require(source.isRegularFile() && !Files.isSymbolicLink(source)) {
                I18n.t("core.packUpgrade.20", change.path)
            }
        }
    }

    private fun requireUpgradeFile(
        path: Path,
        message: String,
    ) {
        require(path.isRegularFile() && !Files.isSymbolicLink(path)) { message }
    }

    private fun archiveParent(archive: Path): Path = archive.parent ?: throw IOException(I18n.t("core.packUpgrade.21"))

    /** 回滚已写入的文件、账本、归档与实例记录；有未恢复项时保留备份目录并返回带说明的异常。 */
    private fun rollbackUpgrade(
        error: Throwable,
        instanceId: String,
        oldInstance: GameInstance,
        state: UpgradeWriteState,
    ): Throwable {
        val failures = mutableListOf<Throwable>()
        runCatching {
            state.installed.asReversed().forEach { Files.deleteIfExists(it) }
            for ((target, saved) in state.backups.entries
                .toList()
                .asReversed()) {
                Files.move(
                    saved,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            if (state.ledgerWritten) restoreOrDelete(state.ledger, state.ledgerBackup)
            if (state.archiveWritten) restoreOrDelete(state.archive, state.archiveBackup)
        }.onFailure { failures += it }
        if (state.instanceUpdated) {
            runCatching { store.updateInstance(instanceId) { oldInstance } }
                .onFailure { failures += it }
        }
        if (failures.isNotEmpty()) {
            retain = true
            return IOException(I18n.t("core.packUpgrade.22", directory), error).also {
                failures.forEach(it::addSuppressed)
            }
        }
        cleanup()
        return error
    }

    fun cleanup() {
        if (retain) return
        if (!directory.exists()) return
        runCatching {
            check(directory.parent == root && directory.name.startsWith(".molifox-pack-upgrade-"))
            check(!Files.isSymbolicLink(directory)) { I18n.t("core.packUpgrade.23") }
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }.onFailure { store.log(I18n.t("packsB2.packUpgrade.cleanupFailed", directory), true) }
    }
}

private fun restoreOrDelete(
    target: Path,
    backup: Path?,
) {
    if (backup == null) {
        Files.deleteIfExists(target)
    } else {
        Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING)
    }
}
