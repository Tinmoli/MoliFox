// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core.platform

import java.io.EOFException
import java.io.IOException

private const val DNS_HEADER_BYTES = 12
private const val DNS_SRV_TYPE = 33
private const val DNS_RESPONSE_FLAG = 0x8000
private const val DNS_TRUNCATED_FLAG = 0x0200
private const val DNS_RCODE_MASK = 0x000f
private const val DNS_NXDOMAIN = 3
private const val DNS_POINTER_MASK = 0xc0
private const val DNS_POINTER_HIGH_MASK = 0x3f
private const val DNS_NAME_LIMIT = 255
private const val DNS_TTL_BYTES = 4
private const val DNS_SRV_FIELDS_BYTES = 6
private const val MAX_DNS_ANSWERS = 256
private const val BYTE_MASK = 0xff
private const val BYTE_BITS = 8

/** 验证原生 resolver 返回的响应，展开压缩域名；越界、循环指针与截断均属于查询失败。 */
internal fun parseDnsSrvPacket(bytes: ByteArray): List<String> {
    if (bytes.size < DNS_HEADER_BYTES) throw EOFException("Truncated DNS header")
    val packet = DnsPacket(bytes)
    packet.word()
    val flags = packet.word()
    checkDns(flags and DNS_RESPONSE_FLAG != 0 && flags and DNS_TRUNCATED_FLAG == 0, "Invalid DNS response flags")
    val rcode = flags and DNS_RCODE_MASK
    checkDns(rcode == 0 || rcode == DNS_NXDOMAIN, "DNS response failed: rcode=$rcode")
    val questions = packet.word()
    val answers = packet.word()
    packet.position = DNS_HEADER_BYTES
    if (rcode == DNS_NXDOMAIN) return emptyList()
    checkDns(answers <= MAX_DNS_ANSWERS, "Too many DNS answer records")
    repeat(questions) {
        packet.name()
        packet.skip(DNS_TTL_BYTES)
    }
    return buildList {
        repeat(answers) { packet.record()?.let(::add) }
    }
}

private class DnsPacket(
    private val bytes: ByteArray,
) {
    var position: Int = 0

    fun word(): Int = (octet() shl BYTE_BITS) or octet()

    private fun octet(): Int {
        if (position !in bytes.indices) throw EOFException("Truncated DNS response")
        return bytes[position++].toInt() and BYTE_MASK
    }

    fun skip(size: Int) {
        if (size < 0 || position > bytes.size - size) throw EOFException("Truncated DNS record")
        position += size
    }

    fun name(): String {
        val labels = mutableListOf<String>()
        val visited = mutableSetOf<Int>()
        var resume = -1
        var length = 0
        while (true) {
            checkDns(visited.add(position), "Cyclic DNS name pointer")
            val count = octet()
            if (count == 0) break
            if (count and DNS_POINTER_MASK == DNS_POINTER_MASK) {
                val target = ((count and DNS_POINTER_HIGH_MASK) shl BYTE_BITS) or octet()
                if (resume < 0) resume = position
                position = target
            } else {
                checkDns(count and DNS_POINTER_MASK == 0, "Invalid DNS label")
                val start = position
                skip(count)
                length += count + 1
                checkDns(length <= DNS_NAME_LIMIT, "DNS name is too long")
                labels += String(bytes, start, count, Charsets.US_ASCII)
            }
        }
        if (resume >= 0) position = resume
        return labels.joinToString(".").ifEmpty { "." }
    }

    fun record(): String? {
        name()
        val type = word()
        val recordClass = word()
        skip(DNS_TTL_BYTES)
        val size = word()
        val start = position
        skip(size)
        val end = position
        if (type != DNS_SRV_TYPE || recordClass != 1) return null
        if (size < DNS_SRV_FIELDS_BYTES + 1) throw IOException("Truncated DNS SRV data")
        position = start
        val priority = word()
        val weight = word()
        val port = word()
        val host = name()
        if (position != end) throw IOException("Invalid DNS SRV data length")
        return "$priority $weight $port $host"
    }
}

private fun checkDns(
    valid: Boolean,
    message: String,
) {
    if (!valid) throw IOException(message)
}
