// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core.platform

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import java.io.IOException

// glibc res_state 的公开首部为 retrans、retry；为结构保留充足的零初始化空间。
private const val RESOLVER_STATE_BYTES = 4096L
private const val RESOLVER_RETRY_OFFSET = 4L
private const val RESOLVER_RETRY_SECONDS = 2
private const val MAX_DNS_PACKET_BYTES = 65535
private const val DNS_CLASS_INTERNET = 1
private const val DNS_TYPE_SRV = 33
private const val DNS_HEADER_FLAGS_OFFSET = 2L
private const val DNS_RESPONSE_BYTE = 0x80

internal fun queryLinuxSrvRecords(name: String): List<String> =
    queryResolvedSrvRecords(name) ?: queryLibresolvSrvRecords(name)

/** 每次独立初始化，跟随当前系统 resolv.conf；没有全局 _res 状态或硬编码 DNS 地址。 */
internal fun queryLibresolvSrvRecords(name: String): List<String> {
    val library = NativeLibrary.getInstance("resolv")
    val init = resolverFunction(library, "res_ninit")
    val close = resolverFunction(library, "res_nclose")
    val query = resolverFunction(library, "res_nquery")
    return Memory(RESOLVER_STATE_BYTES).use { state ->
        state.clear()
        if (init.invokeInt(arrayOf(state)) !=
            0
        ) {
            throw IOException("System resolver initialization failed: errno=${Native.getLastError()}")
        }
        try {
            state.setInt(0, RESOLVER_RETRY_SECONDS)
            state.setInt(RESOLVER_RETRY_OFFSET, 1)
            Memory(MAX_DNS_PACKET_BYTES.toLong()).use { answer ->
                answer.clear()
                val length =
                    query.invokeInt(
                        arrayOf(state, name, DNS_CLASS_INTERNET, DNS_TYPE_SRV, answer, MAX_DNS_PACKET_BYTES),
                    )
                readResolverAnswer(answer, length)
            }
        } finally {
            close.invokeVoid(arrayOf(state))
        }
    }
}

private fun readResolverAnswer(
    answer: Memory,
    length: Int,
): List<String> {
    if (length > MAX_DNS_PACKET_BYTES) throw IOException("System resolver returned an oversized response")
    if (length < 0) {
        // NXDOMAIN/NODATA 仍会留下 DNS 头；通信失败的缓冲为零，不能当成无记录。
        val response = answer.getByte(DNS_HEADER_FLAGS_OFFSET).toInt() and DNS_RESPONSE_BYTE != 0
        if (!response) throw IOException("System resolver SRV query failed: errno=${Native.getLastError()}")
        return parseDnsSrvPacket(answer.getByteArray(0, MAX_DNS_PACKET_BYTES))
    }
    return parseDnsSrvPacket(answer.getByteArray(0, length))
}

private fun resolverFunction(
    library: NativeLibrary,
    name: String,
): Function =
    try {
        library.getFunction(name)
    } catch (_: UnsatisfiedLinkError) {
        library.getFunction("__$name")
    }
