// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责实例删除范围、确认信息和文件删除执行。

package dev.molifox.core

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
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
            } catch (failure: Exception) {
                throw InstanceDeletionBlockedException(
                    "无法删除：${failure.message ?: "游戏目录已被移除"}",
                    failure,
                )
            }
        val root =
            try {
                declared.toRealPath()
            } catch (failure: Exception) {
                throw InstanceDeletionBlockedException("无法删除：游戏目录不存在（$declared）", failure)
            }
        if (!Files.isDirectory(root)) {
            throw InstanceDeletionBlockedException("无法删除：游戏目录不是文件夹（$root）")
        }
        val targets = mutableListOf<Path>()
        val preserved = mutableListOf<String>()
        val handled = mutableSetOf<Path>()
        val versionId = instance.versionId
        if (versionId.isNotBlank()) {
            val name = runCatching { safeFilename(versionId) }.getOrNull()
            if (name == null) {
                preserved += "versions/$versionId（版本标识不合法，未删除）"
            } else {
                val folder = ownedTarget(root, root.resolve("versions").resolve(name))
                if (folder != null) {
                    handled.add(folder)
                    requireNotShared(instance, root, name)
                    targets.add(folder)
                }
            }
        }
        ownedTarget(root, store.versionGamePath(instance))?.let { folder ->
            if (folder in handled) return@let
            requireNotShared(instance, root, folder.fileName.toString())
            if (folder !in targets) targets.add(folder)
        }
        // 兼容尚未迁移的旧目录，不触及已经移入备份的内容。
        ownedTarget(root, root.resolve("instances").resolve(instance.id))?.let { targets.add(it) }
        return InstanceDeletionPlan(targets, preserved)
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
            "无法删除：版本目录仍被实例${sharers.joinToString("、") { "「$it」" }}使用；请先处理该实例",
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
        val freed = plan.targets.sumOf { bytesOf(it) }
        val removed = mutableListOf<String>()
        var trashedAny = false
        var permanentFallback = false
        for (target in plan.targets) {
            if (!target.exists(LinkOption.NOFOLLOW_LINKS)) {
                // 规划后被别处删掉：目标确实不再存在，不能因此留下记录。
                removed += target.toString()
                store.log("实例文件已不存在，视为已删除：$target")
                continue
            }
            var trashedThis = false
            try {
                if (permanent) {
                    deleteTree(target)
                } else {
                    try {
                        store.platform.moveToTrash(target)
                        trashedAny = true
                        trashedThis = true
                    } catch (failure: Exception) {
                        store.log("回收站不可用，改为永久删除：$target（${failure.message}）", true)
                        permanentFallback = true
                        deleteTree(target)
                    }
                }
            } catch (failure: Exception) {
                store.log("删除实例文件失败：$target（${failure.message}）", true)
                throw IOException(
                    "删除实例文件失败：$target（已处理 ${removed.size} 项）；${failure.message}",
                    failure,
                )
            }
            if (target.exists(LinkOption.NOFOLLOW_LINKS)) {
                store.log("删除后文件仍存在：$target", true)
                throw IOException("无法删除：$target 仍存在，已保留实例记录")
            }
            removed += target.toString()
            store.log(
                if (trashedThis) {
                    "实例文件已移入回收站：$target"
                } else if (permanent) {
                    "实例文件已永久删除：$target"
                } else {
                    "实例文件已改为永久删除：$target"
                },
            )
        }
        // 二次核对：任何自有目录还在，就不能让调用方把它当成「删干净了」。
        val remaining = plan.targets.filter { it.exists(LinkOption.NOFOLLOW_LINKS) }
        if (remaining.isNotEmpty()) {
            throw IOException("无法删除：${remaining.joinToString("、")} 仍存在，已保留实例记录")
        }
        return InstanceDeletionReport(
            removed,
            freed,
            !permanent && trashedAny && !permanentFallback,
            plan.preserved,
        )
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
        val target = versionFolder(root, versionId) ?: return emptyList()
        val targetKey = physical(target)
        return store.data.instances
            .filter { other ->
                other.id != instance.id &&
                    runCatching {
                        val otherRoot = store.minecraftRoot(other).toRealPath()
                        otherRoot == root &&
                            (
                                versionFolder(otherRoot, other.versionId)
                                    ?.let { physical(it) == targetKey } == true ||
                                    referencesVersion(other, otherRoot, targetKey, versionId)
                            )
                    }.getOrDefault(false)
            }.map { it.name }
    }

    private fun versionFolder(
        root: Path,
        id: String,
    ): Path? = runCatching { safePath(root.resolve("versions"), safeFilename(id)) }.getOrNull()

    /** 存在时取真实路径；目录还没创建时用规范化的绝对路径，保证两侧同一种比较。 */
    private fun physical(path: Path): Path =
        runCatching { path.toRealPath() }.getOrElse { path.toAbsolutePath().normalize() }

    /** 顺着 [other] 的继承链检查 `jar`/`inheritsFrom` 是否真的指向目标版本目录。 */
    private fun referencesVersion(
        other: GameInstance,
        otherRoot: Path,
        targetKey: Path,
        targetId: String,
    ): Boolean {
        var id = other.versionId
        val visited = mutableSetOf<String>()
        while (id.isNotBlank() && visited.add(id) && visited.size <= 64) {
            val file =
                runCatching { safePath(otherRoot, "versions/${safeFilename(id)}/$id.json") }
                    .getOrNull() ?: return false
            if (!file.isRegularFile()) return false
            val json = runCatching { readObject(file) }.getOrNull() ?: return false
            if (json.string("jar") == targetId) return true
            val parent = json.string("inheritsFrom")
            if (parent.isBlank()) return false
            if (parent == targetId) return true
            val parentFolder = versionFolder(otherRoot, parent) ?: return false
            if (physical(parentFolder) == targetKey) return true
            id = parent
        }
        return false
    }

    /**
     * 校验 [candidate] 确实位于 [root] 之下、正好两层的版本目录或实例目录，且不是符号链接。
     *
     * 返回解析后的真实路径（与 [root] 同一套真实路径比较，避免符号链接把删除带出游戏目录）。
     */
    private fun ownedTarget(
        root: Path,
        candidate: Path,
    ): Path? {
        if (!candidate.exists(LinkOption.NOFOLLOW_LINKS)) return null
        if (Files.isSymbolicLink(candidate)) throw IOException("实例路径是符号链接，不能删除：$candidate")
        if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) return null
        val normalized = candidate.toAbsolutePath().normalize()
        if (!normalized.startsWith(root)) throw IOException("拒绝删除游戏目录以外的路径：$normalized")
        val real = normalized.toRealPath()
        if (!real.startsWith(root) || real == root) throw IOException("拒绝删除游戏目录本身：$real")
        if (real.nameCount != root.nameCount + 2) throw IOException("拒绝删除共享目录：$real")
        val parent = real.parent?.fileName?.toString()
        if (parent != "versions" && parent != "instances") throw IOException("拒绝删除共享目录：$real")
        return real
    }

    /**
     * 递归删除 [target]：不跟随符号链接，符号链接只删除链接本身；缺失的子项跳过。
     *
     * `Files.walkFileTree` 默认不进入符号链接目录，因此链接指向的外部内容不会被删除。
     */
    private fun deleteTree(target: Path) {
        if (!target.exists(LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isSymbolicLink(target)) {
            Files.deleteIfExists(target)
            return
        }
        Files.walkFileTree(
            target,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    failure: IOException,
                ): FileVisitResult {
                    // 并发删除或本来就不存在：视为已删除；其余（权限等）向上抛出。
                    if (failure is NoSuchFileException) return FileVisitResult.CONTINUE
                    throw failure
                }

                override fun postVisitDirectory(
                    dir: Path,
                    failure: IOException?,
                ): FileVisitResult {
                    if (failure != null) throw failure
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    /**
     * 统计目录/文件原本占用的字节数：不跟随符号链接，符号链接与读不到的文件按 0 计。
     *
     * 在删除之前统计，供「已删除 X MB」提示使用。
     */
    private fun bytesOf(path: Path): Long {
        if (!path.exists(LinkOption.NOFOLLOW_LINKS)) return 0L
        if (Files.isSymbolicLink(path)) return 0L
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return runCatching { path.fileSize() }.getOrDefault(0L)
        }
        var total = 0L
        runCatching {
            Files.walkFileTree(
                path,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(
                        file: Path,
                        attributes: BasicFileAttributes,
                    ): FileVisitResult {
                        if (attributes.isRegularFile && !attributes.isSymbolicLink) {
                            total += runCatching { attributes.size() }.getOrDefault(0L)
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        file: Path,
                        failure: IOException,
                    ) = FileVisitResult.CONTINUE
                },
            )
        }
        return total
    }
}
