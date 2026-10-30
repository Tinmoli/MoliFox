// 构造最小 tar.gz，供解压、安装与目录校验的离线测试使用。
package dev.molifox.multiplayer

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

internal data class TarEntry(
    val name: String,
    val content: ByteArray,
    val type: Char = '0',
)

internal object TarTestSupport {
    fun archive(entries: List<TarEntry>): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { gzip ->
            entries.forEach { entry ->
                gzip.write(header(entry))
                if (entry.type == '0') {
                    gzip.write(entry.content)
                    gzip.write(ByteArray(padding(entry.content.size.toLong())))
                }
            }
            gzip.write(ByteArray(1024))
        }
        return output.toByteArray()
    }

    private fun header(entry: TarEntry): ByteArray {
        val block = ByteArray(512)
        put(block, 0, entry.name.toByteArray(Charsets.UTF_8))
        put(block, 100, "0000644\u0000".toByteArray())
        put(block, 108, "0000000\u0000".toByteArray())
        put(block, 116, "0000000\u0000".toByteArray())
        val size = if (entry.type == '0') entry.content.size.toLong() else 0L
        put(block, 124, "%011o\u0000".format(size).toByteArray())
        put(block, 136, "%011o\u0000".format(System.currentTimeMillis() / 1000).toByteArray())
        block[156] = entry.type.code.toByte()
        put(block, 257, "ustar\u000000".toByteArray())
        for (index in 148 until 156) block[index] = ' '.code.toByte()
        val checksum = block.sumOf { it.toInt() and 0xFF }
        put(block, 148, "%06o\u0000 ".format(checksum).toByteArray())
        return block
    }

    private fun padding(size: Long): Int = ((512 - (size % 512)) % 512).toInt()

    private fun put(
        block: ByteArray,
        offset: Int,
        bytes: ByteArray,
    ) {
        bytes.copyInto(block, offset)
    }
}
