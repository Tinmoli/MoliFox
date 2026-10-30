// 解压陶瓦联机核心的 .tar.gz 安装包：只接受包内的普通文件，拒绝链接、目录穿越与超限内容。
package dev.molifox.multiplayer.data

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream

internal object TarGzExtractor {
    private const val BLOCK_BYTES = 512
    private const val NAME_OFFSET = 0
    private const val NAME_LENGTH = 100
    private const val SIZE_OFFSET = 124
    private const val SIZE_LENGTH = 12
    private const val TYPE_OFFSET = 156
    private const val PREFIX_OFFSET = 345
    private const val PREFIX_LENGTH = 155
    private const val MAX_ENTRIES = 16
    private const val MAX_FILE_BYTES = 64L * 1024 * 1024
    private const val OCTAL_RADIX = 8
    private const val BUFFER_BYTES = 64 * 1024

    /** 把 [archive] 解压到空目录 [destination]；只允许普通文件与目录。 */
    fun extract(
        archive: Path,
        destination: Path,
    ) {
        Files.createDirectories(destination)
        GZIPInputStream(Files.newInputStream(archive)).use { input ->
            require(readEntries(input, destination) > 0) { "联机核心安装包为空，请重新下载" }
        }
    }

    /** 逐个读取 512 字节头并解压，返回条目数。 */
    private fun readEntries(
        input: InputStream,
        destination: Path,
    ): Int {
        val header = ByteArray(BLOCK_BYTES)
        var count = 0
        var total = 0L
        while (readHeader(input, header)) {
            val name = readString(header, NAME_OFFSET, NAME_LENGTH)
            val prefix = readString(header, PREFIX_OFFSET, PREFIX_LENGTH)
            val fullName = if (prefix.isEmpty()) name else "$prefix/$name"
            val size = readOctal(header, SIZE_OFFSET, SIZE_LENGTH)
            val padding = ((BLOCK_BYTES - (size % BLOCK_BYTES).toInt()) % BLOCK_BYTES).toLong()
            require(size in 0..MAX_FILE_BYTES) { "联机核心安装包里的文件大小异常：$fullName" }
            require(count < MAX_ENTRIES) { "联机核心安装包条目过多" }
            when (header[TYPE_OFFSET].toInt().toChar()) {
                '0', '\u0000' -> {
                    requireSafeName(fullName)
                    val target = destination.resolve(fullName).normalize()
                    require(target.parent == destination.normalize()) { "联机核心安装包路径异常：$fullName" }
                    copyExactly(input, target, size)
                    skipExactly(input, padding)
                    total += size
                    require(total <= MAX_FILE_BYTES * MAX_ENTRIES) { "联机核心安装包解压后过大" }
                }
                '5' -> skipExactly(input, size + padding)
                'x', 'g', 'L' -> error("联机核心安装包包含不支持的扩展头（$fullName），请重新下载")
                else -> error("联机核心安装包包含不支持的条目类型（$fullName）")
            }
            count++
        }
        return count
    }

    private fun readHeader(
        input: InputStream,
        header: ByteArray,
    ): Boolean = readFully(input, header) && !header.all { it == 0.toByte() }

    private fun requireSafeName(name: String) {
        require(".." !in name && '/' !in name) { "联机核心安装包路径异常：$name" }
    }

    private fun readFully(
        input: InputStream,
        buffer: ByteArray,
    ): Boolean {
        var read = 0
        while (read < buffer.size) {
            val count = input.read(buffer, read, buffer.size - read)
            if (count < 0) return read > 0
            read += count
        }
        return true
    }

    private fun copyExactly(
        input: InputStream,
        target: Path,
        size: Long,
    ) {
        Files.createDirectories(target.parent)
        val buffer = ByteArray(BUFFER_BYTES)
        var remaining = size
        Files.newOutputStream(target).use { output ->
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                check(count > 0) { "联机核心安装包在读取 ${target.fileName} 时提前结束" }
                output.write(buffer, 0, count)
                remaining -= count
            }
        }
    }

    private fun skipExactly(
        input: InputStream,
        count: Long,
    ) {
        var remaining = count
        val buffer = ByteArray(BUFFER_BYTES)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            check(read > 0) { "联机核心安装包在跳过多余内容时提前结束" }
            remaining -= read
        }
    }

    private fun readString(
        block: ByteArray,
        offset: Int,
        length: Int,
    ): String {
        val end =
            (offset until offset + length)
                .firstOrNull { block[it] == 0.toByte() }
                ?: (offset + length)
        return String(block, offset, end - offset, Charsets.UTF_8).trim()
    }

    private fun readOctal(
        block: ByteArray,
        offset: Int,
        length: Int,
    ): Long {
        val text = readString(block, offset, length).trim()
        return if (text.isEmpty()) 0 else text.toLong(OCTAL_RADIX)
    }
}
