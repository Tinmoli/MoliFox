// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责 NBT 二进制标签的有界读取和保留根名称的文档描述。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/** Minecraft 的二进制数据标签，覆盖 1–12 号类型；标签值不可变，写回由 NbtWriter 负责。 */
sealed interface NbtTag

data class NbtByte(
    val value: Byte,
) : NbtTag

data class NbtShort(
    val value: Short,
) : NbtTag

data class NbtInt(
    val value: Int,
) : NbtTag

data class NbtLong(
    val value: Long,
) : NbtTag

data class NbtFloat(
    val value: Float,
) : NbtTag

data class NbtDouble(
    val value: Double,
) : NbtTag

data class NbtByteArray(
    val value: ByteArray,
) : NbtTag

data class NbtString(
    val value: String,
) : NbtTag

/** [elementType] 是元素的 NBT 标签号；空列表也保留原始类型，避免丢信息。 */
data class NbtList(
    val elements: List<NbtTag>,
    val elementType: Int,
) : NbtTag {
    /** 只保留列表里的复合标签，用于「服务器列表」这类同构列表。 */
    fun compounds(): List<NbtCompound> = elements.filterIsInstance<NbtCompound>()
}

data class NbtCompound(
    val entries: Map<String, NbtTag>,
) : NbtTag {
    operator fun get(key: String): NbtTag? = entries[key]

    fun string(key: String): String? = (entries[key] as? NbtString)?.value

    fun byte(key: String): Byte? = (entries[key] as? NbtByte)?.value

    fun int(key: String): Int? = (entries[key] as? NbtInt)?.value

    fun long(key: String): Long? = (entries[key] as? NbtLong)?.value

    fun compound(key: String): NbtCompound? = entries[key] as? NbtCompound

    fun list(key: String): NbtList? = entries[key] as? NbtList
}

data class NbtIntArray(
    val value: IntArray,
) : NbtTag

data class NbtLongArray(
    val value: LongArray,
) : NbtTag

data class NbtDocument(
    val rootName: String,
    val root: NbtCompound,
    val compressed: Boolean,
)

internal fun readNbtDocument(path: Path): NbtDocument {
    require(path.fileSize() <= MAX_NBT_FILE_BYTES) { I18n.t("saveA1.nbt.serversTooLarge") }
    val compressed = Files.newInputStream(path).use { it.read() == 0x1f && it.read() == 0x8b }
    val bytes = requireNotNull(readNbtBytes(path)) { I18n.t("saveA1.nbt.dataTooLarge") }
    val name =
        DataInputStream(ByteArrayInputStream(bytes)).use {
            require(it.readUnsignedByte() == 10) { I18n.t("saveA1.nbt.invalidRootTag") }
            it.readUTF()
        }
    return NbtDocument(name, parseNbtRoot(bytes), compressed)
}

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

/** 读取前的文件大小上限，与解压后上限分开：压缩文件本身也不该比这更大。 */
private const val MAX_NBT_FILE_BYTES = 8L * 1024 * 1024

/** 嵌套深度上限：正常 NBT 只有个位数层，挡住刻意构造的深递归。 */
private const val MAX_NBT_DEPTH = 64

/** 列表元素数量上限；真正的边界仍由剩余字节数决定。 */
private const val MAX_NBT_LIST = 1_000_000

/**
 * 读取一个 NBT 根复合标签，自动识别 gzip（`servers.dat`、`level.dat` 可能压缩也可能不压缩）。
 *
 * 文件缺失、格式错误、根标签不是复合标签、解压后超过 8 MB 时返回 null，从不抛异常； 根标签名按惯例为空串，这里不校验。文件只读，不修改。
 */
fun readCompound(path: Path): NbtCompound? =
    runCatching {
        if (!path.isRegularFile()) return@runCatching null
        val bytes = readNbtBytes(path) ?: return@runCatching null
        parseNbtRoot(bytes)
    }.getOrNull()

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
private fun readCompoundBody(
    cursor: Cursor,
    depth: Int,
): NbtCompound {
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

private fun readTag(
    cursor: Cursor,
    type: Int,
    depth: Int,
): NbtTag {
    if (depth > MAX_NBT_DEPTH) cursor.fail()
    return when (type) {
        TAG_BYTE -> NbtByte(cursor.i8())
        TAG_SHORT -> NbtShort(cursor.u16().toShort())
        TAG_INT -> NbtInt(cursor.i32())
        TAG_LONG -> NbtLong(cursor.i64())
        TAG_FLOAT -> NbtFloat(Float.fromBits(cursor.i32()))
        TAG_DOUBLE -> NbtDouble(Double.fromBits(cursor.i64()))
        TAG_STRING -> NbtString(cursor.utf())
        else -> readContainerTag(cursor, type, depth)
    }
}

/** 数组、列表和复合标签带长度前缀或嵌套，单独读取；未知类型同样在这里判错。 */
private fun readContainerTag(
    cursor: Cursor,
    type: Int,
    depth: Int,
): NbtTag =
    when (type) {
        TAG_BYTE_ARRAY -> NbtByteArray(cursor.bytes(cursor.i32()))
        TAG_LIST -> readListTag(cursor, depth)
        TAG_COMPOUND -> readCompoundBody(cursor, depth + 1)
        TAG_INT_ARRAY -> NbtIntArray(cursor.ints(cursor.i32()))
        TAG_LONG_ARRAY -> NbtLongArray(cursor.longs(cursor.i32()))
        else -> cursor.fail()
    }

private fun readListTag(
    cursor: Cursor,
    depth: Int,
): NbtList {
    val elementType = cursor.u8()
    val length = cursor.i32()
    if (length < 0 || length > MAX_NBT_LIST) cursor.fail()
    // 空列表允许元素类型为 TAG_End；非空列表必须是 1–12 号标签。
    if (length > 0 && elementType !in TAG_BYTE..TAG_LONG_ARRAY) cursor.fail()
    val elements = ArrayList<NbtTag>(minOf(length, 256))
    repeat(length) { elements += readTag(cursor, elementType, depth + 1) }
    return NbtList(elements, elementType)
}

/** 内部格式错误；只在外层 `runCatching` 里被吞掉，不会出现在公开 API 上。 */
private class NbtFormatException : Exception()

/** 按大端逐字节拼数值：下面这些位宽、掩码和元素字节数是 NBT 的固定格式。 */
private const val UNSIGNED_BYTE_MASK = 0xff
private const val UNSIGNED_INT_MASK = 0xffffffffL
private const val SHORT_SHIFT_BITS = 8
private const val INT_SHIFT_BITS = 16
private const val LONG_SHIFT_BITS = 32
private const val INT_SIZE_BYTES = 4
private const val LONG_SIZE_BYTES = 8

/** 大端字节游标；任何越界、负长度或非法标签都改抛 [NbtFormatException]。 */
private class Cursor(
    private val bytes: ByteArray,
) {
    private var position = 0

    fun fail(): Nothing = throw NbtFormatException()

    private fun remaining(): Int = bytes.size - position

    fun u8(): Int {
        if (remaining() < 1) fail()
        return bytes[position++].toInt() and UNSIGNED_BYTE_MASK
    }

    fun i8(): Byte = u8().toByte()

    fun u16(): Int = (u8() shl SHORT_SHIFT_BITS) or u8()

    fun i32(): Int = (u16() shl INT_SHIFT_BITS) or u16()

    fun i64(): Long {
        val high = i32().toLong() shl LONG_SHIFT_BITS
        val low = i32().toLong() and UNSIGNED_INT_MASK
        return high or low
    }

    fun bytes(length: Int): ByteArray {
        if (length < 0 || length > remaining()) fail()
        val value = bytes.copyOfRange(position, position + length)
        position += length
        return value
    }

    fun ints(length: Int): IntArray {
        if (length < 0 || length > remaining() / INT_SIZE_BYTES) fail()
        return IntArray(length) { i32() }
    }

    fun longs(length: Int): LongArray {
        if (length < 0 || length > remaining() / LONG_SIZE_BYTES) fail()
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
