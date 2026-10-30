// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责把存档压缩包解压到指定目录：拒绝危险条目并限制体积。

package dev.molifox.core

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.Collections
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream
import org.apache.commons.compress.archivers.zip.ZipFile as ArchiveZipFile

/** 单个压缩包允许的条目数上限。 */
private const val MAX_SAVE_ENTRIES = 100_000

/** 单个压缩包解压后的总体积上限（64 GiB）。 */
private const val MAX_SAVE_BYTES = 64L * 1024 * 1024 * 1024

/** 单个压缩包条目解压后的体积上限（32 GiB）。 */
private const val MAX_SAVE_ENTRY_BYTES = 32L * 1024 * 1024 * 1024

/** 解压/打包的缓冲大小。 */
private const val SAVE_COPY_BUFFER_BYTES = 64 * 1024

/** 解压到 [destination]：拒绝 `..`、绝对路径、盘符与符号链接条目，体积与条目数受限；可跳过 `session.lock`。 */
internal suspend fun extractArchive(
    archive: Path,
    destination: Path,
    context: JobContext?,
    skipSessionLock: Boolean = false,
    stripPrefix: String = "",
) {
    destination.createDirectories()
    ArchiveZipFile.builder().setPath(archive).get().use { zip ->
        val entries =
            Collections.list(zip.entries).filter { !it.isDirectory && !it.name.endsWith("/") }
        require(entries.isNotEmpty()) { "备份包是空的，无法恢复" }
        require(entries.size <= MAX_SAVE_ENTRIES) { "备份包文件过多，无法恢复" }
        val extraction = SaveExtraction(zip, destination, skipSessionLock, stripPrefix)
        for (entry in entries) {
            context?.checkCancelled()
            if (!extraction.extract(entry)) continue
            val written = extraction.writtenEntries
            context?.progress(
                if (entries.isEmpty()) 1f else written.toFloat() / entries.size,
                "恢复存档 · 文件 $written/${entries.size}",
            )
        }
    }
}

/** 单次解压会话：逐条校验条目名、累计声明体积与实写字节，并写入目标目录。 */
private class SaveExtraction(
    private val zip: ArchiveZipFile,
    destination: Path,
    private val skipSessionLock: Boolean,
    private val stripPrefix: String,
) {
    private val root = destination.toAbsolutePath().normalize()
    private var declaredBytes = 0L

    var writtenEntries = 0
        private set

    /** 解压一条记录；返回 false 表示按规则跳过（空名称或 `session.lock`）。 */
    fun extract(entry: ZipArchiveEntry): Boolean {
        val relative = relativeName(entry)
        if (relative.isBlank() || isSkipped(relative)) return false
        writeEntry(entry, relative)
        writtenEntries++
        return true
    }

    private fun relativeName(entry: ZipArchiveEntry): String {
        val name = entry.name.replace('\\', '/')
        require(name.split('/').none { it == ".." }) {
            "备份包包含向上跳转的路径：${entry.name}"
        }
        require(!name.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(name)) {
            "备份包包含绝对路径：${entry.name}"
        }
        require(!entry.isUnixSymlink) { "备份包包含符号链接条目：${entry.name}" }
        if (entry.size > 0) declaredBytes += entry.size
        require(declaredBytes <= MAX_SAVE_BYTES) { "备份包解压后体积超出限制" }
        // 导入的压缩包可能多出一层世界目录：只解压这一层里面的内容。
        if (stripPrefix.isBlank()) return name
        require(name.startsWith(stripPrefix, ignoreCase = true)) {
            "压缩包条目不在世界目录内：${entry.name}"
        }
        return name.substring(stripPrefix.length)
    }

    /** `session.lock` 是游戏打开世界时持有的锁文件；复制出来只会让新世界一启动就报「世界正在使用」。 */
    private fun isSkipped(relative: String): Boolean =
        skipSessionLock && relative.substringAfterLast('/').equals("session.lock", true)

    private fun writeEntry(
        entry: ZipArchiveEntry,
        relative: String,
    ) {
        val target = safePath(root, relative)
        target.parent.createDirectories()
        target.outputStream().use { output ->
            zip.getInputStream(entry).use { input ->
                copyEntryBytes(entry, input, output)
            }
        }
    }

    /** 逐段复制条目内容，并按条目体积上限校验已写入的字节数。 */
    private fun copyEntryBytes(
        entry: ZipArchiveEntry,
        input: InputStream,
        output: OutputStream,
    ) {
        var actual = 0L
        val buffer = ByteArray(SAVE_COPY_BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            actual += read
            require(actual <= MAX_SAVE_ENTRY_BYTES) {
                "备份包条目超出大小限制：${entry.name}"
            }
            output.write(buffer, 0, read)
        }
    }
}
