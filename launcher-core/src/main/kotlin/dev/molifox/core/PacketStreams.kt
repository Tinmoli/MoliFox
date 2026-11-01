// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责 Minecraft 协议包的变长整数与字节流读写。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** VarInt：每字节 7 位有效位、最高位是继续标志，最多允许 5 字节（28 位有效位移）。 */
private const val VAR_INT_VALUE_MASK = 0x7F
private const val VAR_INT_CONTINUATION_BIT = 0x80
private const val VAR_INT_BITS_PER_BYTE = 7
private const val VAR_INT_MAX_SHIFT = 28

/** unsigned short 的大端写出：高字节位移与单字节掩码。 */
private const val HIGH_BYTE_SHIFT = 8
private const val BYTE_MASK = 0xFF

internal fun InputStream.readVarInt(): Int {
    var result = 0
    var shift = 0
    while (true) {
        val byte = read()
        if (byte < 0) throw EOFException(I18n.t("miscA2.packetStream.unexpectedEof"))
        result = result or ((byte and VAR_INT_VALUE_MASK) shl shift)
        if ((byte and VAR_INT_CONTINUATION_BIT) == 0) return result
        shift += VAR_INT_BITS_PER_BYTE
        if (shift > VAR_INT_MAX_SHIFT) throw IOException(I18n.t("miscA2.packetStream.varIntTooLong"))
    }
}

internal fun InputStream.readFully(length: Int): ByteArray {
    if (length < 0) throw IOException(I18n.t("miscA2.packetStream.negativeLength"))
    val bytes = ByteArray(length)
    var read = 0
    while (read < length) {
        val count = read(bytes, read, length - read)
        if (count < 0) throw EOFException(I18n.t("miscA2.packetStream.unexpectedEof"))
        read += count
    }
    return bytes
}

internal fun OutputStream.writeVarInt(value: Int) {
    var remaining = value
    while (true) {
        if ((remaining and VAR_INT_VALUE_MASK.inv()) == 0) {
            write(remaining)
            return
        }
        write((remaining and VAR_INT_VALUE_MASK) or VAR_INT_CONTINUATION_BIT)
        remaining = remaining ushr VAR_INT_BITS_PER_BYTE
    }
}

internal fun OutputStream.writeString(text: String) {
    val bytes = text.toByteArray(Charsets.UTF_8)
    writeVarInt(bytes.size)
    write(bytes)
}

internal fun OutputStream.writeUnsignedShort(value: Int) {
    write((value ushr HIGH_BYTE_SHIFT) and BYTE_MASK)
    write(value and BYTE_MASK)
}

internal fun OutputStream.writePacket(payload: ByteArray) {
    writeVarInt(payload.size)
    write(payload)
}
