// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责实例删除时的路径核对、共享引用判定与磁盘删除辅助，不持有业务状态。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile

/** 版本继承链最多追溯的层数，防止配置里的循环引用导致无限读取。 */
private const val MAX_VERSION_CHAIN_DEPTH = 64

/** 存在时取真实路径；目录还没创建时用规范化的绝对路径，保证两侧同一种比较。 */
internal fun deletionPhysical(path: Path): Path =
    runCatching { path.toRealPath() }.getOrElse { path.toAbsolutePath().normalize() }

internal fun deletionVersionFolder(
    root: Path,
    id: String,
): Path? = runCatching { safePath(root.resolve("versions"), safeFilename(id)) }.getOrNull()

/**
 * 版本/实例目录解析后的真实路径。
 *
 * 校验 [candidate] 确实位于 [root] 之下、正好两层的版本目录或实例目录，且不是符号链接；
 * 与 [root] 同一套真实路径比较，避免符号链接把删除带出游戏目录。
 */
internal fun deletionOwnedTarget(
    root: Path,
    candidate: Path,
): Path? {
    if (!candidate.exists(LinkOption.NOFOLLOW_LINKS)) return null
    if (Files.isSymbolicLink(candidate)) {
        throw IOException(I18n.t("instA1.deletionPaths.symbolicLink", candidate))
    }
    if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) return null
    val normalized = candidate.toAbsolutePath().normalize()
    if (!normalized.startsWith(root)) {
        throw IOException(I18n.t("instA1.deletionPaths.outsideGameFolder", normalized))
    }
    val real = normalized.toRealPath()
    return deletionRequireOwnedLayout(root, real)
}

/** 真实路径必须在游戏目录之内、且正好是 `versions/<名>` 或 `instances/<名>`。 */
internal fun deletionRequireOwnedLayout(
    root: Path,
    real: Path,
): Path {
    if (!real.startsWith(root) || real == root) {
        throw IOException(I18n.t("instA1.deletionPaths.gameFolderItself", real))
    }
    if (!deletionHasTwoLevelLayout(root, real)) {
        throw IOException(I18n.t("instA1.deletionPaths.sharedFolder", real))
    }
    return real
}

internal fun deletionHasTwoLevelLayout(
    root: Path,
    real: Path,
): Boolean {
    if (real.nameCount != root.nameCount + 2) return false
    val parent = real.parent?.fileName?.toString()
    return parent == "versions" || parent == "instances"
}

/** 顺着 [other] 的继承链检查 `jar`/`inheritsFrom` 是否真的指向目标版本目录。 */
internal fun deletionReferencesVersion(
    other: GameInstance,
    otherRoot: Path,
    targetKey: Path,
    targetId: String,
): Boolean {
    var id = other.versionId
    val visited = mutableSetOf<String>()
    while (id.isNotBlank() && visited.add(id) && visited.size <= MAX_VERSION_CHAIN_DEPTH) {
        val file =
            runCatching { safePath(otherRoot, "versions/${safeFilename(id)}/$id.json") }
                .getOrNull() ?: return false
        if (!file.isRegularFile()) return false
        val json = runCatching { readObject(file) }.getOrNull() ?: return false
        if (json.string("jar") == targetId) return true
        val parent = json.string("inheritsFrom")
        if (parent.isBlank()) return false
        if (parent == targetId) return true
        val parentFolder = deletionVersionFolder(otherRoot, parent) ?: return false
        if (deletionPhysical(parentFolder) == targetKey) return true
        id = parent
    }
    return false
}

/**
 * 目录链接（reparse point）识别：Windows 目录联接（junction / mount point）**不是**符号链接，
 * `Files.isSymbolicLink` 返回 false、`NOFOLLOW_LINKS` 也拦不住，并且 `walkFileTree` 会真的进入它的子树。
 *
 * 这类目录的属性特征是 `isDirectory == true && isOther == true`（普通目录的 `isOther` 恒为 false）。
 * 只靠 `fileKey == null` 不可靠：Windows 上普通目录也可能没有 fileKey。
 */
private fun BasicFileAttributes.isDirectoryLink(): Boolean = isDirectory && isOther

/**
 * 递归删除 [target]：不跟随符号链接与目录链接，链接只删除链接本身；缺失的子项跳过。
 *
 * `Files.walkFileTree` 默认不进入符号链接目录，但**会进入 Windows 目录联接**，因此必须在
 * `preVisitDirectory` 里显式识别并跳过，否则会把链接外部（用户自己的存档、游戏目录）的真实文件删掉。
 */
internal fun deletionDeleteTree(target: Path) {
    if (!target.exists(LinkOption.NOFOLLOW_LINKS)) return
    val targetAttributes =
        runCatching {
            Files.readAttributes(target, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }.getOrNull()
    if (targetAttributes?.isSymbolicLink == true || targetAttributes?.isDirectoryLink() == true) {
        Files.deleteIfExists(target)
        return
    }
    Files.walkFileTree(
        target,
        object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(
                dir: Path,
                attributes: BasicFileAttributes,
            ): FileVisitResult {
                // 链接本身可以删，但它指向的内容属于别处，绝不能进入。
                if (dir != target && (attributes.isSymbolicLink || attributes.isDirectoryLink())) {
                    Files.deleteIfExists(dir)
                    return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }

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
 * 统计目录/文件原本占用的字节数：不跟随符号链接与目录链接，链接与读不到的文件按 0 计。
 *
 * 与 [deletionDeleteTree] 用同一套链接判据：目录联接背后的文件不属于本实例，不能算进占用。
 * 在删除之前统计，供「已删除 X MB」提示使用。
 */
internal fun deletionBytesOf(path: Path): Long {
    if (!path.exists(LinkOption.NOFOLLOW_LINKS)) return 0L
    val attributes =
        runCatching {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }.getOrNull() ?: return 0L
    if (attributes.isSymbolicLink || attributes.isDirectoryLink()) return 0L
    if (!attributes.isDirectory) {
        return runCatching { path.fileSize() }.getOrDefault(0L)
    }
    var total = 0L
    runCatching {
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    if (dir != path && (attributes.isSymbolicLink || attributes.isDirectoryLink())) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    return FileVisitResult.CONTINUE
                }

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
