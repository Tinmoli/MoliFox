// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责世界包的结构校验、世界名解析与解压。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Collections
import kotlin.io.path.createDirectories
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import org.apache.commons.compress.archivers.zip.ZipFile as ArchiveZipFile

/** 世界包解压前的安全检查：条目数与解压体积上限与存档导入保持一致。 */
private const val MAX_WORLD_ENTRIES = 100_000
private const val MAX_WORLD_ARCHIVE_BYTES = 64L * 1024 * 1024 * 1024
private const val MAX_WORLD_ENTRY_BYTES = 32L * 1024 * 1024 * 1024
private const val WORLD_COPY_BUFFER_BYTES = 64 * 1024

/** 下载在世界安装进度里的占比上限，剩余区间留给解压与落盘。 */
internal const val WORLD_DOWNLOAD_PROGRESS_END = 0.85f

/** 游戏打开世界时持有的锁文件；解压出来只会让新世界一启动就报「世界正在使用」。 */
private const val SESSION_LOCK_FILE = "session.lock"

/**
 * 世界目录名：包装目录优先（作者给的名称），其次 `level.dat` 的 `Data.LevelName`，
 * 最后用项目名或压缩包名兜底；全部候选都经过文件名安全化。
 */
internal fun worldPackName(
    layout: SaveArchiveLayout,
    worldDirectory: Path,
    projectTitle: String,
    archiveName: String,
): String {
    val levelName =
        readCompound(worldDirectory.resolve("level.dat"))
            ?.compound("Data")
            ?.string("LevelName")
            .orEmpty()
    val wrapperName = layout.prefix.trimEnd('/').substringAfterLast('/')
    val candidates =
        listOf(
            wrapperName,
            levelName,
            projectTitle,
            archiveName.removeSuffixIgnoreCase(".zip"),
            I18n.t("packsB2.worldPack.defaultName"),
        )
    return candidates
        .filter { it.isNotBlank() }
        .map(::safeSaveLabel)
        .firstOrNull(::isPlainSaveName)
        ?: error(I18n.t("packsB2.worldPack.noName"))
}

/** 解压后复核世界目录：必须有非空、可读且不是链接的 `level.dat`。 */
internal fun requireWorldLevelDat(world: Path) {
    val level = world.resolve("level.dat")
    require(level.isRegularFile(LinkOption.NOFOLLOW_LINKS) && !isLinkPath(level)) {
        I18n.t("packsB2.worldPack.noLevelDat")
    }
    require(level.fileSize() > 0) { I18n.t("packsB2.worldPack.emptyLevelDat") }
    runCatching { Files.newInputStream(level).use { it.read() } }
        .getOrElse { error(I18n.t("packsB2.worldPack.unreadableLevelDat")) }
}

/**
 * 把世界包解压到 [destination]：剥掉 [prefix]（空串表示包根就是世界目录），
 * 拒绝路径穿越、绝对路径与符号链接条目，并跳过 `session.lock`。
 */
internal suspend fun extractWorldPack(
    archive: Path,
    destination: Path,
    prefix: String,
    context: JobContext?,
) {
    destination.createDirectories()
    ArchiveZipFile.builder().setPath(archive).get().use { zip ->
        val entries =
            Collections.list(zip.entries).filter { !it.isDirectory && !it.name.endsWith("/") }
        require(entries.isNotEmpty()) { I18n.t("packsB2.worldPack.empty") }
        require(entries.size <= MAX_WORLD_ENTRIES) { I18n.t("packsB2.worldPack.tooManyFiles") }
        val extraction = WorldPackExtraction(zip, destination, prefix)
        for (entry in entries) {
            context?.checkCancelled()
            if (!extraction.extract(entry)) continue
            val written = extraction.writtenEntries
            context?.progress(
                WORLD_DOWNLOAD_PROGRESS_END +
                    (1f - WORLD_DOWNLOAD_PROGRESS_END) * written / entries.size,
                I18n.t("packsB2.worldPack.progressEntries", written, entries.size),
            )
        }
    }
}

/** 单次解压会话：逐条校验条目名、累计声明体积与实写字节，并写入目标目录。 */
private class WorldPackExtraction(
    private val zip: ArchiveZipFile,
    destination: Path,
    private val prefix: String,
) {
    private val root = destination.toAbsolutePath().normalize()
    private var declaredBytes = 0L
    private var actualBytes = 0L

    var writtenEntries = 0
        private set

    /** 解压一条记录；返回 false 表示按规则跳过（空名称或 `session.lock`）。 */
    fun extract(entry: ZipArchiveEntry): Boolean {
        val relative = relativeName(entry)
        if (relative.isBlank()) return false
        if (relative.substringAfterLast('/').equals(SESSION_LOCK_FILE, true)) return false
        writeArchiveEntrySafely(zip, entry, root, relative) { input, output ->
            copyEntryBytes(entry, input, output)
        }
        writtenEntries++
        return true
    }

    private fun relativeName(entry: ZipArchiveEntry): String {
        val name = entry.name.replace('\\', '/')
        require(name.split('/').none { it == ".." }) {
            I18n.t("packsB2.worldPack.unsafeParentPath", entry.name)
        }
        require(!name.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(name)) {
            I18n.t("packsB2.worldPack.absolutePath", entry.name)
        }
        require(!entry.isUnixSymlink) { I18n.t("packsB2.worldPack.symlinkEntry", entry.name) }
        val relative =
            if (prefix.isBlank()) {
                name
            } else {
                require(name.startsWith(prefix, ignoreCase = true)) {
                    I18n.t("packsB2.worldPack.entryOutsideWorld", entry.name)
                }
                name.substring(prefix.length)
            }
        if (relative.isNotBlank() && entry.size > 0) declaredBytes += entry.size
        require(declaredBytes <= MAX_WORLD_ARCHIVE_BYTES) { I18n.t("packsB2.worldPack.sizeLimit") }
        return relative
    }

    /** 逐段复制条目内容，并按条目体积上限校验已写入的字节数。 */
    private fun copyEntryBytes(
        entry: ZipArchiveEntry,
        input: InputStream,
        output: OutputStream,
    ) {
        var actual = 0L
        val buffer = ByteArray(WORLD_COPY_BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            actual += read
            actualBytes += read
            require(actualBytes <= MAX_WORLD_ARCHIVE_BYTES) {
                I18n.t("packsB2.worldPack.sizeLimit")
            }
            require(actual <= MAX_WORLD_ENTRY_BYTES) {
                I18n.t("packsB2.worldPack.entryTooLarge", entry.name)
            }
            output.write(buffer, 0, read)
        }
    }
}
