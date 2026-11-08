// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

/** 仅属于当前选择预览的文件信息；不读取内容，不写入缓存或归档。 */
internal data class ExportFileStamp(
    val size: Long,
    val modified: FileTime,
    val created: FileTime,
    val fileKey: Any?,
) {
    companion object {
        fun read(path: Path): ExportFileStamp {
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            return ExportFileStamp(
                attributes.size(),
                attributes.lastModifiedTime(),
                attributes.creationTime(),
                attributes.fileKey(),
            )
        }
    }
}

internal fun MrpackExportPlan.requireUnchangedExportFile(
    file: MrpackExportFile,
    source: Path,
) {
    val expected = fileStamps[file.path] ?: return
    require(ExportFileStamp.read(source) == expected) {
        I18n.t("packsA2.packExport.fileChangedPreviewAgain", file.path)
    }
}
