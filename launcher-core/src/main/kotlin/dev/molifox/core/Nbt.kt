// 负责NBT 二进制标签的安全读取。

package dev.molifox.core

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlin.io.path.*

/**
 * 只读的 NBT 标签：Minecraft 的二进制数据格式，1–12 号标签全部覆盖。
 *
 * 这里只为读取 `servers.dat` 这类小文件服务，不提供写入；数值、字符串、数组、列表与复合标签 都是不可变的普通数据类，便于测试直接比较。
 */
sealed interface NbtTag

data class NbtByte(val value: Byte) : NbtTag

data class NbtShort(val value: Short) : NbtTag

data class NbtInt(val value: Int) : NbtTag

data class NbtLong(val value: Long) : NbtTag

data class NbtFloat(val value: Float) : NbtTag

data class NbtDouble(val value: Double) : NbtTag

data class NbtByteArray(val value: ByteArray) : NbtTag

data class NbtString(val value: String) : NbtTag

/** [elementType] 是元素的 NBT 标签号；空列表也保留原始类型，避免丢信息。 */
data class NbtList(val elements: List<NbtTag>, val elementType: Int) : NbtTag {
    /** 只保留列表里的复合标签，用于「服务器列表」这类同构列表。 */
    fun compounds(): List<NbtCompound> = elements.filterIsInstance<NbtCompound>()
}

data class NbtCompound(val entries: Map<String, NbtTag>) : NbtTag {
    operator fun get(key: String): NbtTag? = entries[key]

    fun string(key: String): String? = (entries[key] as? NbtString)?.value

    fun compound(key: String): NbtCompound? = entries[key] as? NbtCompound

    fun list(key: String): NbtList? = entries[key] as? NbtList
}

data class NbtIntArray(val value: IntArray) : NbtTag

data class NbtLongArray(val value: LongArray) : NbtTag

private const val TAG_END = 0
private const val TAG_BYTE = 1
private const val TAG_SHORT = 2
private const val TAG_INT = 3
private const val TAG_LONG = 4
private const val TAG_FLOAT = 5
private const val TAG_DOUBLE = 6
private const val TAG_BYTE_ARRAY = 7
private const val TAG_STRING = 8
private const val TAG_LIST = 9
private const val TAG_COMPOUND = 10
private const val TAG_INT_ARRAY = 11
private const val TAG_LONG_ARRAY = 12

/** 解压后超过 8 MB 的 NBT 直接放弃：启动器只需要小文件，避免恶意数据撑爆内存。 */
private const val MAX_NBT_BYTES = 8 * 1024 * 1024

/** 嵌套深度上限：正常 NBT 只有个位数层，挡住刻意构造的深递归。 */
private const val MAX_NBT_DEPTH = 64

/** 列表元素数量上限；真正的边界仍由剩余字节数决定。 */
private const val MAX_NBT_LIST = 1_000_000

/**
 * 读取一个 NBT 根复合标签，自动识别 gzip（`servers.dat`、`level.dat` 可能压缩也可能不压缩）。
 *
 * 文件缺失、格式错误、根标签不是复合标签、解压后超过 8 MB 时返回 null，从不抛异常； 根标签名按惯例为空串，这里不校验。文件只读，不修改。
 */
fun readCompound(path: Path): NbtCompound? = runCatching {
    if (!path.isRegularFile()) return@runCatching null
    val bytes = readNbtBytes(path) ?: return@runCatching null
    parseNbtRoot(bytes)
}
    .getOrNull()

/** 打开文件并嗅探 gzip 魔数；无论压缩与否都只读入最多 8 MB。 */
private fun readNbtBytes(path: Path): ByteArray? =
    Files.newInputStream(path).use { file ->
        val input = BufferedInputStream(file)
        input.mark(2)
        val gzipped = input.read() == 0x1f && input.read() == 0x8b
        input.reset()
        val stream: InputStream = if (gzipped) GZIPInputStream(input) else input
        readNbtLimited(stream)
    }

/** 读取到上限为止；超限返回 null，不保留半截数据。 */
private fun readNbtLimited(input: InputStream): ByteArray? {
    val buffer = ByteArray(64 * 1024)
    val output = ByteArrayOutputStream()
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return output.toByteArray()
        if (output.size() + count > MAX_NBT_BYTES) return null
        output.write(buffer, 0, count)
    }
}

private fun parseNbtRoot(bytes: ByteArray): NbtCompound {
    val cursor = Cursor(bytes)
    if (cursor.u8() != TAG_COMPOUND) cursor.fail()
    cursor.utf() // 根标签名，通常为空串
    return readCompoundBody(cursor, 1)
}

/** 复合标签一直读到 TAG_End；重复的名字按后写入者覆盖，与实际游戏行为一致。 */
private fun readCompoundBody(cursor: Cursor, depth: Int): NbtCompound {
    if (depth > MAX_NBT_DEPTH) cursor.fail()
    val entries = LinkedHashMap<String, NbtTag>()
    while (true) {
        val type = cursor.u8()
        if (type == TAG_END) break
        val name = cursor.utf()
        entries[name] = readTag(cursor, type, depth)
    }
    return NbtCompound(entries)
}

private fun readTag(cursor: Cursor, type: Int, depth: Int): NbtTag {
    if (depth > MAX_NBT_DEPTH) cursor.fail()
    return when (type) {
        TAG_BYTE -> NbtByte(cursor.i8())
        TAG_SHORT -> NbtShort(cursor.u16().toShort())
        TAG_INT -> NbtInt(cursor.i32())
        TAG_LONG -> NbtLong(cursor.i64())
        TAG_FLOAT -> NbtFloat(Float.fromBits(cursor.i32()))
        TAG_DOUBLE -> NbtDouble(Double.fromBits(cursor.i64()))
        TAG_BYTE_ARRAY -> NbtByteArray(cursor.bytes(cursor.i32()))
        TAG_STRING -> NbtString(cursor.utf())
        TAG_LIST -> {
            val elementType = cursor.u8()
            val length = cursor.i32()
            if (length < 0 || length > MAX_NBT_LIST) cursor.fail()
            // 空列表允许元素类型为 TAG_End；非空列表必须是 1–12 号标签。
            if (length > 0 && elementType !in TAG_BYTE..TAG_LONG_ARRAY) cursor.fail()
            val elements = ArrayList<NbtTag>(minOf(length, 256))
            repeat(length) { elements += readTag(cursor, elementType, depth + 1) }
            NbtList(elements, elementType)
        }
        TAG_COMPOUND -> readCompoundBody(cursor, depth + 1)
        TAG_INT_ARRAY -> NbtIntArray(cursor.ints(cursor.i32()))
        TAG_LONG_ARRAY -> NbtLongArray(cursor.longs(cursor.i32()))
        else -> cursor.fail()
    }
}

/** 内部格式错误；只在外层 `runCatching` 里被吞掉，不会出现在公开 API 上。 */
private class NbtFormatException : Exception()

/** 大端字节游标；任何越界、负长度或非法标签都改抛 [NbtFormatException]。 */
private class Cursor(private val bytes: ByteArray) {
    private var position = 0

    fun fail(): Nothing = throw NbtFormatException()

    private fun remaining(): Int = bytes.size - position

    fun u8(): Int {
        if (remaining() < 1) fail()
        return bytes[position++].toInt() and 0xff
    }

    fun i8(): Byte = u8().toByte()

    fun u16(): Int = (u8() shl 8) or u8()

    fun i32(): Int = (u16() shl 16) or u16()

    fun i64(): Long = (i32().toLong() shl 32) or (i32().toLong() and 0xffffffffL)

    fun bytes(length: Int): ByteArray {
        if (length < 0 || length > remaining()) fail()
        val value = bytes.copyOfRange(position, position + length)
        position += length
        return value
    }

    fun ints(length: Int): IntArray {
        if (length < 0 || length > remaining() / 4) fail()
        return IntArray(length) { i32() }
    }

    fun longs(length: Int): LongArray {
        if (length < 0 || length > remaining() / 8) fail()
        return LongArray(length) { i64() }
    }

    /** 与 `DataInput.readUTF` 一致：u16 字节长度 + 修改版 UTF-8，可正确处理中文与代理对。 */
    fun utf(): String {
        val length = u16()
        if (length > remaining()) fail()
        val text = DataInputStream(ByteArrayInputStream(bytes, position - 2, length + 2)).readUTF()
        position += length
        return text
    }
}
