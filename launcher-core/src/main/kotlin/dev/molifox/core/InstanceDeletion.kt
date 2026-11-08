// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责实例删除范围、确认信息和文件删除执行。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * 删除被拒绝：实例自己的版本/游戏目录仍被其他实例使用，或目录无法安全核对。
 *
 * 抛出这个异常时磁盘与记录都不动，界面直接展示 [message]，不会出现「记录删了、文件还在」的半成品。
 */
class InstanceDeletionBlockedException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** 删除实例的计划：待删除或移入回收站的路径，以及保留原因。 */
data class InstanceDeletionPlan(
    /** 实例版本目录与仍存在的旧实例目录；共享游戏根不在范围内。 */
    val targets: List<Path>,
    /** 保留不删的路径与原因，写入日志供用户核对。 */
    val preserved: List<String> = emptyList(),
)

/** 删除实例文件的结果。 */
data class InstanceDeletionReport(
    /** 已移入回收站或已永久删除的绝对路径。 */
    val removedPaths: List<String>,
    /** 这些路径下原本占用的字节数，用于「已删除 X MB」提示。 */
    val freedBytes: Long,
    /** true 表示确实进了回收站；false 表示永久删除（包括回收站不可用时的回退）。 */
    val movedToTrash: Boolean,
    /** 因其他实例真实引用而保留的路径与原因；非空时提示语必须说明保留了哪些内容。 */
    val preserved: List<String> = emptyList(),
)

/** 删除实例会一并删除的存档：名称与上次修改时间，只用于确认框展示。 */
data class InstanceDeletionWorld(
    val name: String,
    val modified: Instant?,
)

/**
 * 核对并删除实例版本目录，以及尚存的旧实例目录；不触及共享游戏根、assets 或 libraries。
 * 规划时发现真实共享引用或不安全路径就拒绝操作；执行完成后调用方才能移除实例记录。
 * 默认移入系统回收站；永久删除不跟随符号链接，处理失败时保留原因并停止。
 */
class InstanceDeletionService(
    private val store: LauncherStore,
) {
    /**
     * 计算 [instanceId] 的删除计划；不修改磁盘。
     *
     * 无法解析实例的游戏目录、实例路径是符号链接、目标落在共享目录上，或版本/游戏目录仍被另一个实例
     * 使用时抛出带中文说明的异常，让调用方拒绝删除而不是猜测；抛出时记录与文件都不动。
     */
    fun plan(instanceId: String): InstanceDeletionPlan {
        val instance = store.instance(instanceId)
        val declared =
            try {
                store.minecraftRoot(instance)
            } catch (failure: IllegalStateException) {
                throw blockedByGameDirectory(failure)
            } catch (failure: IllegalArgumentException) {
                throw blockedByGameDirectory(failure)
            }
        val root = deletionRoot(declared)
        val targets = mutableListOf<Path>()
        val preserved = mutableListOf<String>()
        val handled = mutableSetOf<Path>()
        val versionId = instance.versionId
        if (versionId.isNotBlank()) {
            val name = runCatching { safeFilename(versionId) }.getOrNull()
            if (name == null) {
                preserved += I18n.t("core.instanceDeletion.1", versionId)
            } else {
                val folder = deletionOwnedTarget(root, root.resolve("versions").resolve(name))
                if (folder != null) {
                    handled.add(folder)
                    requireNotShared(instance, root, name)
                    targets.add(folder)
                }
            }
        }
        deletionOwnedTarget(root, store.versionGamePath(instance))?.let { folder ->
            if (folder in handled) return@let
            requireNotShared(instance, root, folder.fileName.toString())
            if (folder !in targets) targets.add(folder)
        }
        // 兼容尚未迁移的旧目录，不触及已经移入备份的内容。
        deletionOwnedTarget(root, root.resolve("instances").resolve(instance.id))?.let {
            targets.add(it)
        }
        return InstanceDeletionPlan(targets, preserved)
    }

    /** 游戏目录解析失败（目录已移除或路径非法）时统一转成删除被拒绝的提示。 */
    private fun blockedByGameDirectory(failure: Exception): InstanceDeletionBlockedException =
        InstanceDeletionBlockedException(
            I18n.t("core.instanceDeletion.2", failure.message ?: I18n.t("instA1.deletion.gameDirectoryRemoved")),
            failure,
        )

    /** 解析游戏目录并确认它是文件夹；失败信息沿用删除被阻止的提示。 */
    private fun deletionRoot(declared: Path): Path {
        val root =
            try {
                declared.toRealPath()
            } catch (failure: IOException) {
                throw InstanceDeletionBlockedException(I18n.t("core.instanceDeletion.3", declared), failure)
            }
        if (!Files.isDirectory(root)) {
            throw InstanceDeletionBlockedException(I18n.t("core.instanceDeletion.4", root))
        }
        return root
    }

    /**
     * 版本/游戏目录仍被其他实例真实使用时拒绝整次删除：不能只删掉一半再留下一条「没有文件」的记录。
     *
     * 抛出的消息正是界面与日志要显示的一句话，包含占用者名称，告诉用户先处理哪个实例。
     */
    private fun requireNotShared(
        instance: GameInstance,
        root: Path,
        versionId: String,
    ) {
        val sharers = sharers(instance, root, versionId)
        if (sharers.isEmpty()) return
        throw InstanceDeletionBlockedException(
            I18n.t("core.instanceDeletion.5", sharers.joinToString("、") { "「$it」" }),
        )
    }

    /**
     * 执行 [plan]：统计字节数后逐个移入回收站（[permanent] 为 true 时永久删除）。
     *
     * 回收站不可用时回退为永久删除，并把 [InstanceDeletionReport.movedToTrash] 置为 false，界面据此提示。
     * 缺失的文件视为已删除；权限不足等失败抛出带路径的中文异常，已处理的部分不回滚。
     * 每个目标处理完都再核对一次是否真的消失：只要还有目标留在磁盘上就抛出异常，调用方不得移除记录。
     */
    fun delete(
        plan: InstanceDeletionPlan,
        permanent: Boolean = false,
    ): InstanceDeletionReport {
        val freed = plan.targets.sumOf { deletionBytesOf(it) }
        val removed = mutableListOf<String>()
        var trashedAny = false
        var permanentFallback = false
        for (target in plan.targets) {
            if (!target.exists(LinkOption.NOFOLLOW_LINKS)) {
                // 规划后被别处删掉：目标确实不再存在，不能因此留下记录。
                removed += target.toString()
                store.log(I18n.t("instA1.deletion.alreadyMissing", target))
                continue
            }
            val trashedThis =
                removeTarget(target, permanent, removed.size) { permanentFallback = true }
            if (trashedThis) trashedAny = true
            requireDeleted(target)
            removed += target.toString()
            logRemoval(target, trashedThis, permanent)
        }
        // 二次核对：任何自有目录还在，就不能让调用方把它当成「删干净了」。
        val remaining = plan.targets.filter { it.exists(LinkOption.NOFOLLOW_LINKS) }
        if (remaining.isNotEmpty()) {
            throw IOException(I18n.t("core.instanceDeletion.6", remaining.joinToString("、")))
        }
        return InstanceDeletionReport(
            removed,
            freed,
            !permanent && trashedAny && !permanentFallback,
            plan.preserved,
        )
    }

    /**
     * 移入回收站或永久删除一个目标，返回是否真的进了回收站。
     *
     * 回收站不可用时回退为永久删除，并通过 [onPermanentFallback] 告知调用方；
     * 失败抛出带目标路径的异常，[processed] 是本次已处理完的项数。
     */
    private fun removeTarget(
        target: Path,
        permanent: Boolean,
        processed: Int,
        onPermanentFallback: () -> Unit,
    ): Boolean =
        try {
            if (permanent) {
                deletionDeleteTree(target)
                false
            } else {
                try {
                    store.platform.moveToTrash(target)
                    true
                } catch (failure: IOException) {
                    store.log(I18n.t("instA1.deletion.trashUnavailable", target, failure.message), true)
                    onPermanentFallback()
                    deletionDeleteTree(target)
                    false
                }
            }
        } catch (failure: IOException) {
            store.log(I18n.t("instA1.deletion.deleteFailed", target, failure.message), true)
            throw IOException(
                I18n.t("core.instanceDeletion.7", target, processed, failure.message),
                failure,
            )
        }

    /** 单个目标处理完的日志：区分真正进回收站、永久删除与回收站回退。 */
    private fun logRemoval(
        target: Path,
        trashed: Boolean,
        permanent: Boolean,
    ) {
        store.log(
            if (trashed) {
                I18n.t("instA1.deletion.movedToTrash", target)
            } else if (permanent) {
                I18n.t("core.instanceDeletion.8", target)
            } else {
                I18n.t("core.instanceDeletion.9", target)
            },
        )
    }

    /** 删除动作结束后目标必须消失；仍在磁盘上就拒绝让调用方移除实例记录。 */
    private fun requireDeleted(target: Path) {
        if (!target.exists(LinkOption.NOFOLLOW_LINKS)) return
        store.log(I18n.t("instA1.deletion.stillPresentAfterDelete", target), true)
        throw IOException(I18n.t("core.instanceDeletion.6", target))
    }

    /**
     * 读取实例游戏目录下 `saves/` 的存档（目录或 `.zip`），按上次修改时间倒序；只读，用于确认框里的名单。
     *
     * 非隔离实例的存档在共用游戏根里、不会被删除，界面不应展示本列表；读取失败返回空列表。
     */
    fun worlds(instanceId: String): List<InstanceDeletionWorld> {
        val instance = store.instance(instanceId)
        val saves =
            runCatching { store.gamePath(instance).resolve("saves") }.getOrNull()
                ?: return emptyList()
        if (!Files.isDirectory(saves, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        if (Files.isSymbolicLink(saves)) return emptyList()
        return runCatching {
            saves
                .listDirectoryEntries()
                .filter { !Files.isSymbolicLink(it) }
                .filter {
                    Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) ||
                        it.name.endsWith(".zip", ignoreCase = true)
                }.map { world ->
                    InstanceDeletionWorld(
                        world.name,
                        runCatching { world.getLastModifiedTime().toInstant() }.getOrNull(),
                    )
                }.sortedByDescending { it.modified ?: Instant.EPOCH }
        }.getOrDefault(emptyList())
    }

    /**
     * 同一游戏根下真实使用这个版本目录的其他实例名称（用于说明保留原因）。
     *
     * 只认实际文件身份：另一个实例解析后的版本目录是同一物理路径，或它的版本 JSON 的
     * `inheritsFrom`/`jar` 真指向这个目录或版本标识。`gameVersion` 相同不算共用。
     */
    private fun sharers(
        instance: GameInstance,
        root: Path,
        versionId: String,
    ): List<String> {
        val target = deletionVersionFolder(root, versionId) ?: return emptyList()
        val targetKey = deletionPhysical(target)
        return store.data.instances
            .filter { other ->
                other.id != instance.id &&
                    runCatching {
                        val otherRoot = store.minecraftRoot(other).toRealPath()
                        otherRoot == root &&
                            (
                                deletionVersionFolder(otherRoot, other.versionId)
                                    ?.let { deletionPhysical(it) == targetKey } == true ||
                                    deletionReferencesVersion(
                                        other,
                                        otherRoot,
                                        targetKey,
                                        versionId,
                                    )
                            )
                    }.getOrDefault(false)
            }.map { it.name }
    }
}
