// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责存档操作的一次性临时目录与失败清理。

package dev.molifox.core

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

/** 临时目录前缀：解压与复制都先落在 `saves/` 内的隐藏目录，成功后一次改名。 */
private const val SAVE_STAGING_PREFIX = ".molifox-saves-"

/** `saves/` 内的一次性临时目录；成功后改名就位，失败时由调用方删除。 */
internal fun saveStagingPath(saves: Path): Path = safePath(saves, "$SAVE_STAGING_PREFIX${UUID.randomUUID()}")

/** 删除本次操作产生的目录/文件；链接与目录联接只删链接本身，不进入目标目录。 */
internal fun deleteSaveTree(path: Path) {
    if (!path.exists(LinkOption.NOFOLLOW_LINKS)) return
    if (isLinkPath(path) || path.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
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
                if (dir != path && (attrs.isOther || attrs.isSymbolicLink)) {
                    FileVisitResult.SKIP_SUBTREE
                } else {
                    FileVisitResult.CONTINUE
                }

            override fun visitFile(
                file: Path,
                attrs: BasicFileAttributes,
            ): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(
                dir: Path,
                error: IOException?,
            ): FileVisitResult {
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        },
    )
}
