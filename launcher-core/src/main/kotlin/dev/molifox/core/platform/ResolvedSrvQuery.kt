// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core.platform

import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.ptr.ShortByReference
import java.io.IOException

private const val BUS_ERROR_BYTES = 24L
private const val BUS_ERROR_MESSAGE_OFFSET = 8L
private const val METHOD_TIMEOUT_MICROS = 1_500_000L

// systemd ResolveService: NO_TXT (1<<6)、NO_ADDRESS (1<<7)，只读取 SRV，不附带 TXT/地址查询。
private const val SRV_ONLY_FLAGS = 192L
private const val DBUS_ARRAY_TYPE = 97
private const val DBUS_STRUCT_TYPE = 114
private const val DBUS_WORD_TYPE = 113
private const val DBUS_STRING_TYPE = 115
private const val UNSIGNED_WORD_MASK = 0xffff

/** null 仅表示本机未提供此系统服务；DNS 故障不降级到其他解析路径。 */
internal fun queryResolvedSrvRecords(name: String): List<String>? {
    val library =
        try {
            NativeLibrary.getInstance("systemd")
        } catch (_: UnsatisfiedLinkError) {
            return null
        }
    val connection = PointerByReference()
    if (library.getFunction("sd_bus_open_system").invokeInt(arrayOf(connection)) < 0) return null
    val bus = connection.value ?: return null
    try {
        checkBus(library, "sd_bus_set_method_call_timeout", bus, METHOD_TIMEOUT_MICROS)
        return resolvedQuery(library, bus, name)
    } finally {
        library.getFunction("sd_bus_flush_close_unref").invokePointer(arrayOf(bus))
    }
}

private fun resolvedQuery(
    library: NativeLibrary,
    bus: Pointer,
    name: String,
): List<String>? =
    Memory(BUS_ERROR_BYTES).use { error ->
        error.clear()
        val reply = PointerByReference()
        try {
            val status =
                library.getFunction("sd_bus_call_method").invokeInt(
                    arrayOf(
                        bus,
                        "org.freedesktop.resolve1",
                        "/org/freedesktop/resolve1",
                        "org.freedesktop.resolve1.Manager",
                        "ResolveService",
                        error,
                        reply,
                        "isssit",
                        0,
                        "",
                        "",
                        name,
                        0,
                        SRV_ONLY_FLAGS,
                    ),
                )
            if (status < 0) return@use resolvedError(error, status)
            val response = reply.value ?: throw IOException("System DNS service returned no reply")
            readResolvedServices(library, response)
        } finally {
            reply.value?.let { library.getFunction("sd_bus_message_unref").invokePointer(arrayOf(it)) }
            library.getFunction("sd_bus_error_free").invokeVoid(arrayOf(error))
        }
    }

private fun resolvedError(
    error: Pointer,
    status: Int,
): List<String>? {
    val name = error.getPointer(0)?.getString(0).orEmpty()
    return when {
        name.endsWith("ServiceUnknown") || name.endsWith("NameHasNoOwner") || name.endsWith("UnknownMethod") -> null

        name.endsWith("NoSuchRR") || name.endsWith("DnsError.NXDOMAIN") -> emptyList()

        name.endsWith("NoSuchService") -> listOf("0 0 0 .")

        else -> throw IOException(
            "System DNS service failed: $name (${error.getPointer(
                BUS_ERROR_MESSAGE_OFFSET,
            )?.getString(0)}), error=$status",
        )
    }
}

private fun readResolvedServices(
    library: NativeLibrary,
    reply: Pointer,
): List<String> =
    buildList {
        checkBus(library, "sd_bus_message_enter_container", reply, DBUS_ARRAY_TYPE, "(qqqsa(iiay)s)")
        while (checkBus(library, "sd_bus_message_enter_container", reply, DBUS_STRUCT_TYPE, "qqqsa(iiay)s") > 0) {
            val priority = readResolvedWord(library, reply)
            val weight = readResolvedWord(library, reply)
            val port = readResolvedWord(library, reply)
            val host = PointerByReference()
            checkBus(library, "sd_bus_message_read_basic", reply, DBUS_STRING_TYPE, host)
            val target = host.value?.getString(0) ?: throw IOException("System DNS service returned no SRV target")
            add("$priority $weight $port $target")
            checkBus(library, "sd_bus_message_skip", reply, "a(iiay)s")
            checkBus(library, "sd_bus_message_exit_container", reply)
        }
        checkBus(library, "sd_bus_message_exit_container", reply)
    }

private fun readResolvedWord(
    library: NativeLibrary,
    reply: Pointer,
): Int {
    val value = ShortByReference()
    checkBus(library, "sd_bus_message_read_basic", reply, DBUS_WORD_TYPE, value)
    return value.value.toInt() and UNSIGNED_WORD_MASK
}

private fun checkBus(
    library: NativeLibrary,
    operation: String,
    vararg arguments: Any?,
): Int {
    val status = library.getFunction(operation).invokeInt(arguments)
    if (status < 0) throw IOException("$operation failed: error=$status")
    return status
}
