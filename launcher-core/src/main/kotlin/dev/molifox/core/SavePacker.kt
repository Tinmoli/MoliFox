// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责把世界目录打包成备份，或整树复制到目标临时目录。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.outputStream

/** 原版备份文件名：`yyyy-MM-dd_HH-mm-ss_<世界名>.zip`。 */
private val saveBackupStamp =
    DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneId.systemDefault())

/** 同一秒内连续备份时最多往后顺延的秒数。 */
private const val MAX_BACKUP_NAME_ATTEMPTS = 1000

/** 空世界目录打包时的进度：没有文件可报，固定报一半。 */
private const val PROGRESS_EMPTY_DIRECTORY = 0.5f

/** 解压/打包的缓冲大小。 */
private const val SAVE_COPY_BUFFER_BYTES = 64 * 1024

/** 存档目录的打包与整树复制；都先写同目录临时文件，成功后原子改名。 */
internal class SavePacker(
    private val store: LauncherStore,
) {
    /** 把存档打包进 `backups/`：目录按内容打包，`saves/` 里的 `.zip` 直接整包复制。 */
    suspend fun packSave(
        instanceId: String,
        source: Path,
        label: String,
        context: JobContext?,
        progressLabel: String,
    ): SaveBackupInfo {
        val directory = backupDirectoryOf(store, instanceId)
        if (directory.exists(LinkOption.NOFOLLOW_LINKS)) requireOrdinaryDirectory(directory)
        directory.createDirectories()
        val (archive, createdAt) = allocateArchive(directory, label)
        if (!source.isDirectory(LinkOption.NOFOLLOW_LINKS)) {
            copyIntoArchive(source, archive)
        } else {
            packDirectory(source, archive, context, progressLabel)
        }
        return SaveBackupInfo(
            id = archive.name.removeSuffix(".zip"),
            saveName = label,
            archive = archive,
            createdAt = createdAt,
            size = archive.fileSize(),
            restorable = zipHasRootLevelDat(archive),
        )
    }

    /** 分配一个不冲突的原版备份文件名：同一秒内连续备份时把秒数顺延 1 秒。 */
    private fun allocateArchive(
        directory: Path,
        label: String,
    ): Pair<Path, Instant> {
        var instant = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        var archive = directory.resolve("${saveBackupStamp.format(instant)}_$label.zip")
        var attempt = 0
        while (archive.exists(LinkOption.NOFOLLOW_LINKS)) {
            attempt++
            require(attempt < MAX_BACKUP_NAME_ATTEMPTS) { I18n.t("saveA2.packer.nameConflict") }
            instant = instant.plusSeconds(1)
            archive = directory.resolve("${saveBackupStamp.format(instant)}_$label.zip")
        }
        return archive to instant
    }

    /** 把世界目录打包成 zip：按相对路径排序，跳过符号链接与越出世界目录的路径，先写 `.part`。 */
    private suspend fun packDirectory(
        source: Path,
        archive: Path,
        context: JobContext?,
        label: String,
    ) {
        val part = siblingPart(archive)
        try {
            val files = collectSaveFiles(source)
            ZipOutputStream(part.outputStream().buffered()).use { zip ->
                if (files.isEmpty()) {
                    context?.progress(PROGRESS_EMPTY_DIRECTORY, I18n.t("saveA2.packer.emptyDirectory", label))
                }
                files.forEachIndexed { index, (relative, file) ->
                    context?.checkCancelled()
                    zip.putNextEntry(ZipEntry(relative))
                    file.inputStream().use { input ->
                        input.copyTo(zip, SAVE_COPY_BUFFER_BYTES)
                    }
                    zip.closeEntry()
                    context?.progress(
                        (index + 1).toFloat() / files.size,
                        I18n.t("core.isolationMigration.32", label, index + 1, files.size),
                    )
                }
            }
            commitSavePath(part, archive)
        } finally {
            part.deleteIfExists()
        }
    }

    /** `.zip` 存档当成整包备份：直接复制到 `.part` 再改名，不嵌套压缩。 */
    private fun copyIntoArchive(
        source: Path,
        archive: Path,
    ) {
        val part = siblingPart(archive)
        try {
            Files.copy(source, part)
            commitSavePath(part, archive)
        } finally {
            part.deleteIfExists()
        }
    }

    /** 跨实例复制世界目录：先写临时目录，逐文件复制并检查取消，符号链接与越界路径跳过。 */
    suspend fun copySaveTree(
        source: Path,
        staging: Path,
        context: JobContext?,
    ) {
        val files = collectSaveFiles(source)
        staging.createDirectories()
        files.forEachIndexed { index, (relative, file) ->
            context?.checkCancelled()
            val target = safePath(staging, relative)
            target.parent.createDirectories()
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING)
            context?.progress(
                (index + 1).toFloat() / files.size,
                I18n.t("saveA2.packer.copyProgress", index + 1, files.size),
            )
        }
    }

    private fun siblingPart(archive: Path): Path = archive.resolveSibling("${archive.fileName}.part")
}
