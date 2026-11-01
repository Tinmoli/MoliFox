// 构造测试用 zip 的辅助工具：支持重复条目与非法路径，java.util.zip 的写入器会拒绝这些情况。
package dev.molifox.multiplayer.easytier

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.CRC32

internal data class ZipTestEntry(
    val name: String,
    val content: ByteArray = ByteArray(0),
)

/** 以 STORE（不压缩）方式生成 zip 字节，条目按给定顺序原样写入。 */
internal object ZipTestSupport {
    fun zip(entries: List<ZipTestEntry>): ByteArray {
        val body = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        entries.forEach { entry ->
            val name = entry.name.toByteArray(Charsets.UTF_8)
            val crc = crc32(entry.content)
            val offset = body.size()
            writeLocalHeader(body, name, entry.content.size, crc)
            body.write(entry.content)
            writeCentralHeader(central, name, entry.content.size, crc, offset)
        }
        val directory = central.toByteArray()
        val end = ByteArrayOutputStream()
        writeLe32(end, END_SIGNATURE)
        writeLe16(end, 0)
        writeLe16(end, 0)
        writeLe16(end, entries.size)
        writeLe16(end, entries.size)
        writeLe32(end, directory.size)
        writeLe32(end, body.size())
        writeLe16(end, 0)
        val output = ByteArrayOutputStream()
        body.writeTo(output)
        output.write(directory)
        end.writeTo(output)
        return output.toByteArray()
    }

    private fun writeLocalHeader(
        output: OutputStream,
        name: ByteArray,
        size: Int,
        crc: Long,
    ) {
        writeLe32(output, LOCAL_SIGNATURE)
        writeLe16(output, VERSION)
        writeLe16(output, 0)
        writeLe16(output, STORE_METHOD)
        writeLe16(output, 0)
        writeLe16(output, 0)
        writeLe32(output, crc)
        writeLe32(output, size)
        writeLe32(output, size)
        writeLe16(output, name.size)
        writeLe16(output, 0)
        output.write(name)
    }

    private fun writeCentralHeader(
        output: OutputStream,
        name: ByteArray,
        size: Int,
        crc: Long,
        offset: Int,
    ) {
        writeLe32(output, CENTRAL_SIGNATURE)
        writeLe16(output, VERSION)
        writeLe16(output, VERSION)
        writeLe16(output, 0)
        writeLe16(output, STORE_METHOD)
        writeLe16(output, 0)
        writeLe16(output, 0)
        writeLe32(output, crc)
        writeLe32(output, size)
        writeLe32(output, size)
        writeLe16(output, name.size)
        writeLe16(output, 0)
        writeLe16(output, 0)
        writeLe16(output, 0)
        writeLe16(output, 0)
        writeLe32(output, 0)
        writeLe32(output, offset)
        output.write(name)
    }

    private fun writeLe16(
        output: OutputStream,
        value: Int,
    ) {
        output.write(value and BYTE_MASK)
        output.write((value shr BITS_PER_BYTE) and BYTE_MASK)
    }

    private fun writeLe32(
        output: OutputStream,
        value: Int,
    ) {
        repeat(BYTES_PER_INT) { index ->
            output.write((value shr (index * BITS_PER_BYTE)) and BYTE_MASK)
        }
    }

    private fun writeLe32(
        output: OutputStream,
        value: Long,
    ) {
        writeLe32(output, value.toInt())
    }

    private fun crc32(content: ByteArray): Long {
        val crc = CRC32()
        crc.update(content)
        return crc.value
    }
}

private const val LOCAL_SIGNATURE = 0x04034b50
private const val CENTRAL_SIGNATURE = 0x02014b50
private const val END_SIGNATURE = 0x06054b50
private const val VERSION = 20
private const val STORE_METHOD = 0
private const val BYTE_MASK = 0xFF
private const val BITS_PER_BYTE = 8
private const val BYTES_PER_INT = 4
