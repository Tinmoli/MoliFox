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
import kotlin.io.path.*

/** 删除实例的计划：真正会永久删除的路径，以及因共用而保留的路径。 */
data class InstanceDeletionPlan(
    /** 要删除的绝对路径，只可能是 `<游戏根>/versions/<版本标识>` 或 `<游戏根>/instances/<实例 id>`。 */
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
)

/** 删除实例会一并删除的存档：名称与上次修改时间，只用于确认框展示。 */
data class InstanceDeletionWorld(val name: String, val modified: Instant?)

/**
 * 删除实例的版本文件夹与游戏文件夹：默认移入系统回收站，按住 Shift 时永久删除。
 *
 * 删除范围就是实例自己的两部分目录：
 * - `<游戏根>/versions/<版本标识>/`：版本 json、客户端 jar；另一个实例仍在用同一版本目录时保留。
 * - `<游戏根>/instances/<实例 id>/`：实例的独立游戏目录（含 `mods/`、`config/`、`saves/`、
 *   `resourcepacks/`、`shaderpacks/`、`logs/` 等全部内容）。
 *
 * 共用游戏根、`libraries/`、`assets/` 与非隔离实例的 `saves/` 仍不在删除范围内：它们不属于任何单个实例。
 * 默认按系统回收站处理（[delete] 的 `permanent = false`），用户按住 Shift 确认时永久删除；回收站不可用时回退为
 * 永久删除并在结果中注明。永久删除用 `Files.walkFileTree` 递归进行，不跟随符号链接（遇到符号链接只解除链接本身），
 * 缺失文件视为已删除，权限不足等失败会带中文路径报错并停止。
 */
class InstanceDeletionService(private val store: LauncherStore) {

    /**
     * 计算 [instanceId] 的删除计划；不修改磁盘。
     *
     * 无法解析实例的游戏目录、实例路径是符号链接，或目标落在共享目录上时抛出带中文说明的异常，
     * 让调用方拒绝删除而不是猜测。
     */
    fun plan(instanceId: String): InstanceDeletionPlan {
        val instance = store.instance(instanceId)
        val declared =
            try {
                store.minecraftRoot(instance)
            } catch (failure: Exception) {
                throw IOException(
                    "无法解析实例的游戏目录：${failure.message ?: "游戏目录已被移除"}",
                    failure,
                )
            }
        val root =
            try {
                declared.toRealPath()
            } catch (failure: Exception) {
                throw IOException("无法解析实例的游戏目录：$declared", failure)
            }
        if (!Files.isDirectory(root)) throw IOException("实例的游戏目录不是文件夹：$root")
        val targets = mutableListOf<Path>()
        val preserved = mutableListOf<String>()
        val versionId = instance.versionId
        if (versionId.isNotBlank()) {
            val name = runCatching { safeFilename(versionId) }.getOrNull()
            if (name == null) preserved += "versions/$versionId（版本标识不合法，未删除）"
            else {
                val folder = ownedTarget(root, root.resolve("versions").resolve(name))
                if (folder != null) {
                    if (sharesVersion(instance, root, name))
                        preserved += "$folder（另一个实例仍在使用同一版本目录）"
                    else targets.add(folder)
                }
            }
        }
        // 实例自己名下的游戏目录：存在就整目录删除（mods/config/saves 等全部在内）。
        ownedTarget(root, root.resolve("instances").resolve(instance.id))?.let { targets.add(it) }
        return InstanceDeletionPlan(targets, preserved)
    }

    /**
     * 执行 [plan]：统计字节数后逐个移入回收站（[permanent] 为 true 时永久删除）。
     *
     * 回收站不可用时回退为永久删除，并把 [InstanceDeletionReport.movedToTrash] 置为 false，界面据此提示。
     * 缺失的文件视为已删除；权限不足等失败抛出带路径的中文异常，已处理的部分不回滚。
     */
    fun delete(plan: InstanceDeletionPlan, permanent: Boolean = false): InstanceDeletionReport {
        val freed = plan.targets.sumOf { bytesOf(it) }
        val removed = mutableListOf<String>()
        var movedToTrash = plan.targets.isNotEmpty() && !permanent
        for (target in plan.targets) {
            try {
                if (permanent) {
                    deleteTree(target)
                } else {
                    try {
                        store.platform.moveToTrash(target)
                    } catch (failure: Exception) {
                        store.log("回收站不可用，改为永久删除：$target（${failure.message}）", true)
                        movedToTrash = false
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
            removed += target.toString()
            store.log(
                if (movedToTrash) "实例文件已移入回收站：$target"
                else if (permanent) "实例文件已永久删除：$target"
                else "实例文件已改为永久删除：$target"
            )
        }
        return InstanceDeletionReport(removed, freed, movedToTrash)
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
                    }
                    .map { world ->
                        InstanceDeletionWorld(
                            world.name,
                            runCatching { world.getLastModifiedTime().toInstant() }.getOrNull(),
                        )
                    }
                    .sortedByDescending { it.modified ?: Instant.EPOCH }
            }
            .getOrDefault(emptyList())
    }

    /** 同一游戏根下是否还有别的实例引用这个版本目录；有则不能删，否则会把别的实例一起删坏。 */
    private fun sharesVersion(instance: GameInstance, root: Path, versionId: String): Boolean =
        store.data.instances.any { other ->
            other.id != instance.id &&
                other.versionId == versionId &&
                runCatching { store.minecraftRoot(other).toRealPath() }.getOrNull() == root
        }

    /**
     * 校验 [candidate] 确实位于 [root] 之下、正好两层的版本目录或实例目录，且不是符号链接。
     *
     * 返回解析后的真实路径（与 [root] 同一套真实路径比较，避免符号链接把删除带出游戏目录）。
     */
    private fun ownedTarget(root: Path, candidate: Path): Path? {
        if (!candidate.exists(LinkOption.NOFOLLOW_LINKS)) return null
        if (Files.isSymbolicLink(candidate))
            throw IOException("实例路径是符号链接，不能删除：$candidate")
        if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) return null
        val normalized = candidate.toAbsolutePath().normalize()
        if (!normalized.startsWith(root))
            throw IOException("拒绝删除游戏目录以外的路径：$normalized")
        val real = normalized.toRealPath()
        if (!real.startsWith(root) || real == root)
            throw IOException("拒绝删除游戏目录本身：$real")
        if (real.nameCount != root.nameCount + 2)
            throw IOException("拒绝删除共享目录：$real")
        val parent = real.parent?.fileName?.toString()
        if (parent != "versions" && parent != "instances")
            throw IOException("拒绝删除共享目录：$real")
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

                override fun visitFileFailed(file: Path, failure: IOException): FileVisitResult {
                    // 并发删除或本来就不存在：视为已删除；其余（权限等）向上抛出。
                    if (failure is NoSuchFileException) return FileVisitResult.CONTINUE
                    throw failure
                }

                override fun postVisitDirectory(dir: Path, failure: IOException?): FileVisitResult {
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
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
            return runCatching { path.fileSize() }.getOrDefault(0L)
        var total = 0L
        runCatching {
            Files.walkFileTree(
                path,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(
                        file: Path,
                        attributes: BasicFileAttributes,
                    ): FileVisitResult {
                        if (attributes.isRegularFile && !attributes.isSymbolicLink)
                            total += runCatching { attributes.size() }.getOrDefault(0L)
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, failure: IOException) =
                        FileVisitResult.CONTINUE
                },
            )
        }
        return total
    }
}