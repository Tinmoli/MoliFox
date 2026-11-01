// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责把存档目录项与备份压缩包描述成界面使用的数据：体积、时间与 level.dat 探测。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.zip.ZipFile
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** 备份标识 = `<时间戳>_<世界名>`；第 1 组是时间戳、第 2 组是世界名。 */
private val saveBackupIdPattern = Regex("^(\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2})_(.+)$")

/** 反向解析备份创建时间用的同一套格式（本地时区）。 */
private val parseBackupStamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

/** 把一个直接子项描述成 [SaveEntry]；不认识的类型、链接与读不出属性的子项返回 null。 */
internal fun describeSaveEntry(
    path: Path,
    store: LauncherStore,
): SaveEntry? {
    val attributes =
        runCatching {
            Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        }.getOrNull() ?: return null
    // 符号链接与目录联接（Windows junction）都不列出，避免把实例外的世界当成存档。
    if (attributes.isSymbolicLink || attributes.isOther) return null
    val name = path.fileName?.toString() ?: return null
    val modified = runCatching { path.getLastModifiedTime().toInstant() }.getOrNull()
    return when {
        attributes.isDirectory ->
            SaveEntry(
                name = name,
                path = path,
                size = directorySize(path, store),
                lastModified = modified,
                hasLevelDat =
                    path.resolve("level.dat").isRegularFile(LinkOption.NOFOLLOW_LINKS) &&
                        !Files.isSymbolicLink(path.resolve("level.dat")),
                backup = false,
            )
        attributes.isRegularFile && name.endsWith(".zip", true) ->
            SaveEntry(
                name = name,
                path = path,
                size = runCatching { path.fileSize() }.getOrDefault(0L),
                lastModified = modified,
                hasLevelDat = zipHasLevelDat(path),
                backup = false,
            )
        else -> null
    }
}

/** 备份压缩包 -> 展示信息：优先按原版文件名解析世界名与时间，否则退回文件名与修改时间。 */
internal fun describeSaveBackup(archive: Path): SaveBackupInfo? {
    val id = archive.name.removeSuffixIgnoreCase(".zip")
    require(id.isNotBlank())
    val parsed = parseSaveBackupId(id)
    return SaveBackupInfo(
        id = id,
        saveName = parsed?.second ?: id,
        archive = archive,
        createdAt =
            parsed?.first
                ?: runCatching { archive.getLastModifiedTime().toInstant() }.getOrNull()
                ?: Instant.EPOCH,
        size = runCatching { archive.fileSize() }.getOrDefault(0L),
        restorable = zipHasRootLevelDat(archive),
    )
}

/** 目录体积：只统计普通文件，跳过符号链接与目录联接，读不出的大小时按 0 计。 */
private fun directorySize(
    directory: Path,
    store: LauncherStore,
): Long {
    var total = 0L
    runCatching {
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
                    if (attrs.isRegularFile && !attrs.isOther) {
                        total += runCatching { file.fileSize() }.getOrDefault(0L)
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    error: IOException,
                ): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
    }.onFailure { store.log(I18n.t("saveA2.descriptions.sizeReadFailed", it.message ?: directory)) }
    return total
}

/** `.zip` 存档里是否有 `level.dat`；损坏的压缩包按「不像世界」处理，不抛异常。 */
private fun zipHasLevelDat(archive: Path): Boolean =
    runCatching {
        ZipFile(archive.toFile()).use { zip ->
            Collections.list(zip.entries()).any { entry ->
                !entry.isDirectory &&
                    (
                        entry.name.equals("level.dat", true) ||
                            entry.name.endsWith("/level.dat", true)
                    )
            }
        }
    }.getOrDefault(false)

/** 压缩包根目录是否有 `level.dat`：决定这个备份能否被还原成一个世界。 */
internal fun zipHasRootLevelDat(archive: Path): Boolean =
    runCatching {
        ZipFile(archive.toFile()).use { zip ->
            Collections.list(zip.entries()).any { entry ->
                !entry.isDirectory && entry.name.trimEnd('/').equals("level.dat", true)
            }
        }
    }.getOrDefault(false)

/** 原版备份文件名里的 `(创建时间, 世界名)`；不符合命名时返回 null，不因此隐藏备份。 */
private fun parseSaveBackupId(id: String): Pair<Instant, String>? {
    val match = saveBackupIdPattern.matchEntire(id) ?: return null
    val name = match.groupValues[2].trim()
    if (name.isBlank()) return null
    val instant =
        runCatching {
            LocalDateTime
                .parse(match.groupValues[1], parseBackupStamp)
                .atZone(ZoneId.systemDefault())
                .toInstant()
        }.getOrNull() ?: return null
    return instant to name
}
