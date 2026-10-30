// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责保留所有 NBT 类型、根名称和压缩形式的服务器列表写回编码。

package dev.molifox.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.GZIPOutputStream

internal fun encodeNbt(document: NbtDocument): ByteArray {
    val buffer = ByteArrayOutputStream()
    DataOutputStream(buffer).use { output ->
        output.writeByte(10)
        output.writeUTF(document.rootName)
        writeNbtTag(output, document.root, 0)
    }
    val bytes = buffer.toByteArray()
    require(bytes.size <= 8 * 1024 * 1024) { "NBT 数据过大" }
    if (!document.compressed) return bytes
    return ByteArrayOutputStream()
        .also { encoded ->
            GZIPOutputStream(encoded).use { it.write(bytes) }
        }.toByteArray()
}

private fun nbtType(tag: NbtTag): Int =
    when (tag) {
        is NbtByte -> 1
        is NbtShort -> 2
        is NbtInt -> 3
        is NbtLong -> 4
        is NbtFloat -> 5
        is NbtDouble -> 6
        is NbtByteArray -> 7
        is NbtString -> 8
        is NbtList -> 9
        is NbtCompound -> 10
        is NbtIntArray -> 11
        is NbtLongArray -> 12
    }

private fun writeNbtTag(
    output: DataOutputStream,
    tag: NbtTag,
    depth: Int,
) {
    require(depth <= 64) { "NBT 嵌套过深" }
    when (tag) {
        is NbtByte -> output.writeByte(tag.value.toInt())
        is NbtShort -> output.writeShort(tag.value.toInt())
        is NbtInt -> output.writeInt(tag.value)
        is NbtLong -> output.writeLong(tag.value)
        is NbtFloat -> output.writeInt(tag.value.toRawBits())
        is NbtDouble -> output.writeLong(tag.value.toRawBits())
        is NbtString -> output.writeUTF(tag.value)
        is NbtByteArray -> {
            output.writeInt(tag.value.size)
            output.write(tag.value)
        }
        is NbtIntArray -> {
            output.writeInt(tag.value.size)
            tag.value.forEach(output::writeInt)
        }
        is NbtLongArray -> {
            output.writeInt(tag.value.size)
            tag.value.forEach(output::writeLong)
        }
        is NbtList -> {
            require(
                tag.elements.size <= 1_000_000 &&
                    tag.elements.all { nbtType(it) == tag.elementType },
            ) {
                "NBT 列表类型无效"
            }
            output.writeByte(tag.elementType)
            output.writeInt(tag.elements.size)
            tag.elements.forEach { writeNbtTag(output, it, depth + 1) }
        }
        is NbtCompound -> {
            tag.entries.forEach { (name, value) ->
                output.writeByte(nbtType(value))
                output.writeUTF(name)
                writeNbtTag(output, value, depth + 1)
            }
            output.writeByte(0)
        }
    }
}
