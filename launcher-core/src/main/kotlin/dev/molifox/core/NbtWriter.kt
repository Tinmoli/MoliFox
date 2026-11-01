// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责保留所有 NBT 类型、根名称和压缩形式的服务器列表写回编码。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.GZIPOutputStream

private const val NBT_TYPE_END = 0
private const val NBT_TYPE_BYTE = 1
private const val NBT_TYPE_SHORT = 2
private const val NBT_TYPE_INT = 3
private const val NBT_TYPE_LONG = 4
private const val NBT_TYPE_FLOAT = 5
private const val NBT_TYPE_DOUBLE = 6
private const val NBT_TYPE_BYTE_ARRAY = 7
private const val NBT_TYPE_STRING = 8
private const val NBT_TYPE_LIST = 9
private const val NBT_TYPE_COMPOUND = 10
private const val NBT_TYPE_INT_ARRAY = 11
private const val NBT_TYPE_LONG_ARRAY = 12
private const val MAX_NBT_BYTES = 8 * 1024 * 1024
private const val MAX_NBT_DEPTH = 64
private const val MAX_NBT_LIST_ELEMENTS = 1_000_000

internal fun encodeNbt(document: NbtDocument): ByteArray {
    val buffer = ByteArrayOutputStream()
    DataOutputStream(buffer).use { output ->
        output.writeByte(NBT_TYPE_COMPOUND)
        output.writeUTF(document.rootName)
        writeNbtTag(output, document.root, 0)
    }
    val bytes = buffer.toByteArray()
    require(bytes.size <= MAX_NBT_BYTES) { I18n.t("saveA1.nbt.dataTooLarge") }
    if (!document.compressed) return bytes
    return ByteArrayOutputStream()
        .also { encoded ->
            GZIPOutputStream(encoded).use { it.write(bytes) }
        }.toByteArray()
}

private fun nbtType(tag: NbtTag): Int =
    when (tag) {
        is NbtByte -> NBT_TYPE_BYTE
        is NbtShort -> NBT_TYPE_SHORT
        is NbtInt -> NBT_TYPE_INT
        is NbtLong -> NBT_TYPE_LONG
        is NbtFloat -> NBT_TYPE_FLOAT
        is NbtDouble -> NBT_TYPE_DOUBLE
        is NbtByteArray -> NBT_TYPE_BYTE_ARRAY
        is NbtString -> NBT_TYPE_STRING
        is NbtList -> NBT_TYPE_LIST
        is NbtCompound -> NBT_TYPE_COMPOUND
        is NbtIntArray -> NBT_TYPE_INT_ARRAY
        is NbtLongArray -> NBT_TYPE_LONG_ARRAY
    }

private fun writeNbtTag(
    output: DataOutputStream,
    tag: NbtTag,
    depth: Int,
) {
    require(depth <= MAX_NBT_DEPTH) { I18n.t("saveA1.nbt.tooDeep") }
    when (tag) {
        is NbtByte -> output.writeByte(tag.value.toInt())
        is NbtShort -> output.writeShort(tag.value.toInt())
        is NbtInt -> output.writeInt(tag.value)
        is NbtLong -> output.writeLong(tag.value)
        is NbtFloat -> output.writeInt(tag.value.toRawBits())
        is NbtDouble -> output.writeLong(tag.value.toRawBits())
        is NbtString -> output.writeUTF(tag.value)
        is NbtByteArray -> writeNbtByteArray(output, tag)
        is NbtIntArray -> writeNbtIntArray(output, tag)
        is NbtLongArray -> writeNbtLongArray(output, tag)
        is NbtList -> writeNbtList(output, tag, depth)
        is NbtCompound -> writeNbtCompound(output, tag, depth)
    }
}

private fun writeNbtByteArray(
    output: DataOutputStream,
    tag: NbtByteArray,
) {
    output.writeInt(tag.value.size)
    output.write(tag.value)
}

private fun writeNbtIntArray(
    output: DataOutputStream,
    tag: NbtIntArray,
) {
    output.writeInt(tag.value.size)
    tag.value.forEach(output::writeInt)
}

private fun writeNbtLongArray(
    output: DataOutputStream,
    tag: NbtLongArray,
) {
    output.writeInt(tag.value.size)
    tag.value.forEach(output::writeLong)
}

private fun writeNbtList(
    output: DataOutputStream,
    tag: NbtList,
    depth: Int,
) {
    require(
        tag.elements.size <= MAX_NBT_LIST_ELEMENTS &&
            tag.elements.all { nbtType(it) == tag.elementType },
    ) {
        I18n.t("saveA1.nbt.invalidListType")
    }
    output.writeByte(tag.elementType)
    output.writeInt(tag.elements.size)
    tag.elements.forEach { writeNbtTag(output, it, depth + 1) }
}

private fun writeNbtCompound(
    output: DataOutputStream,
    tag: NbtCompound,
    depth: Int,
) {
    tag.entries.forEach { (name, value) ->
        output.writeByte(nbtType(value))
        output.writeUTF(name)
        writeNbtTag(output, value, depth + 1)
    }
    output.writeByte(NBT_TYPE_END)
}
