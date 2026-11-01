// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责路径安全校验、压缩包条目校验与目录复制。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

/** 写入磁盘的相对路径长度上限；超长按异常输入拒绝。 */
private const val MAX_RELATIVE_PATH_LENGTH = 4096

/** 单个压缩包解压后的默认体积上限（4 GiB）。 */
private const val DEFAULT_MAX_ARCHIVE_BYTES = 4L * 1024 * 1024 * 1024

/** 单个压缩包允许的条目数上限。 */
private const val MAX_ARCHIVE_ENTRY_COUNT = 50000

/** Reject traversal, drive prefixes, ADS, and Windows reserved names on every OS. */
fun safePath(
    root: Path,
    relative: String,
): Path {
    require(
        relative.isNotBlank() &&
            relative.length < MAX_RELATIVE_PATH_LENGTH &&
            '\u0000' !in relative &&
            ':' !in relative &&
            !relative.startsWith('/') &&
            !relative.startsWith('\\'),
    ) {
        I18n.t("diagA1.fileSafety.unsafePath")
    }
    val segments = relative.replace('\\', '/').split('/')
    require(
        segments.none {
            it == ".." ||
                it.endsWith('.') ||
                it.endsWith(' ') ||
                Regex("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?").matches(it)
        },
    ) {
        I18n.t("diagA1.fileSafety.unsafePath")
    }
    val absoluteRoot = root.toAbsolutePath().normalize()
    val target = absoluteRoot.resolve(segments.joinToString("/")).normalize()
    require(target != absoluteRoot && target.startsWith(absoluteRoot)) { I18n.t("diagA1.fileSafety.outsideRoot") }
    var parent: Path? = target
    while (parent != null && parent != absoluteRoot.parent) {
        require(!Files.isSymbolicLink(parent)) { I18n.t("diagA1.fileSafety.symlinkInPath") }
        parent = parent.parent
    }
    return target
}

internal fun safeFilename(name: String): String {
    require('/' !in name && '\\' !in name) { I18n.t("diagA1.fileSafety.directoryInFilename") }
    safePath(Path.of(System.getProperty("java.io.tmpdir")), name)
    return name
}

internal fun validateArchive(
    zip: ZipFile,
    prefix: String,
    root: Path,
    maxBytes: Long = DEFAULT_MAX_ARCHIVE_BYTES,
): List<Pair<java.util.zip.ZipEntry, Path>> {
    val entries =
        zip
            .entries()
            .asSequence()
            .filter { it.name.startsWith(prefix) && !it.isDirectory }
            .toList()
    require(entries.size <= MAX_ARCHIVE_ENTRY_COUNT) { I18n.t("diagA1.fileSafety.tooManyEntries") }
    var size = 0L
    return entries.map { entry ->
        require(entry.size >= 0) { I18n.t("diagA1.fileSafety.invalidEntrySize") }
        size = Math.addExact(size, entry.size)
        require(size <= maxBytes) { I18n.t("diagA1.fileSafety.archiveTooLarge") }
        entry to safePath(root, entry.name.removePrefix(prefix))
    }
}

internal fun copyTree(
    source: Path,
    destination: Path,
    excluded: Set<String> = emptySet(),
) {
    require(!Files.isSymbolicLink(source)) { I18n.t("diagA1.fileSafety.symlinkImportUnsupported") }
    if (!source.exists(LinkOption.NOFOLLOW_LINKS)) return
    if (source.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
        destination.parent.createDirectories()
        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
        return
    }
    Files.walk(source).use { paths ->
        paths.forEach { file ->
            if (file == source) return@forEach
            val relative = source.relativize(file)
            if (relative.any { it.toString() in excluded } || Files.isSymbolicLink(file)) {
                return@forEach
            }
            val target = safePath(destination, relative.toString())
            if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                target.createDirectories()
            } else {
                target.parent.createDirectories()
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
