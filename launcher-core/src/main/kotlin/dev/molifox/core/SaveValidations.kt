// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责恢复/替换前的存档完整性校验：level.dat 与非空区域文件。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** 解析备份标识并确认压缩包带 `level.dat`；不满足时报出与恢复流程一致的中文提示。 */
internal fun requireRestorableBackup(
    directory: Path,
    backupId: String,
): SaveBackupInfo {
    val archive = resolveBackupArchive(directory, backupId)
    val info =
        describeSaveBackup(archive) ?: error(I18n.t("saveA2.validations.unrecognizedBackup", backupId))
    require(info.restorable) { I18n.t("saveA2.validations.missingLevelDat", archive.name) }
    return info
}

/** 解压后的世界必须带非空的 `level.dat`，否则不是可恢复的存档。 */
internal fun requireRestorableWorld(
    directory: Path,
    source: String,
) {
    val level = directory.resolve("level.dat")
    require(level.isRegularFile(LinkOption.NOFOLLOW_LINKS) && !isLinkPath(level)) {
        I18n.t("saveA2.validations.sourceMissingLevelDat", source)
    }
    require(level.fileSize() > 0) { I18n.t("saveA2.validations.sourceEmptyLevelDat", source) }
    runCatching { Files.newInputStream(level).use { it.read() } }
        .getOrElse { error(I18n.t("saveA2.validations.sourceUnreadableLevelDat", source)) }
}

/** 替换前的额外校验：除 level.dat 外还要有非空的区域文件，避免把空壳目录换进世界位置。 */
internal fun requireReplaceableWorld(directory: Path) {
    requireRestorableWorld(directory, I18n.t("core.saveManager.8"))
    var regions = 0
    Files.walkFileTree(
        directory,
        object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(
                dir: Path,
                attrs: BasicFileAttributes,
            ): FileVisitResult =
                if (dir != directory && (attrs.isOther || attrs.isSymbolicLink)) {
                    FileVisitResult.SKIP_SUBTREE
                } else {
                    FileVisitResult.CONTINUE
                }

            override fun visitFile(
                file: Path,
                attrs: BasicFileAttributes,
            ): FileVisitResult {
                val regionFile =
                    attrs.isRegularFile && !attrs.isOther && file.name.endsWith(".mca", true)
                if (regionFile && attrs.size() > 0) {
                    regions++
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(
                file: Path,
                error: IOException,
            ): FileVisitResult = FileVisitResult.CONTINUE
        },
    )
    require(regions > 0) { I18n.t("saveA2.validations.noRegionFiles") }
}
