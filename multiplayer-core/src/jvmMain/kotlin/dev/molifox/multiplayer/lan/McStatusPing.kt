// Minecraft 1.7+ status 协议的最小实现：只取 description 纯文本与版本名，失败返回 null。
package dev.molifox.multiplayer.lan

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import dev.molifox.core.i18n.I18n
import dev.molifox.multiplayer.common.LanWorld
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 用 Minecraft status ping（1.7+）确认回环端口上确实有一个可加入的局域网世界。
 *
 * 连接、写入 handshake + status request，再读回一帧 JSON。连接被拒、超时、响应帧
 * 结构不对或 JSON 非法时返回 null，让发现层忽略该候选而不影响其它世界。
 */
class McStatusPing : MinecraftStatusPinger {
    override fun ping(
        host: String,
        port: Int,
        timeoutMillis: Long,
    ): LanWorld? =
        try {
            val effectiveTimeout =
                timeoutMillis.coerceIn(MIN_PING_TIMEOUT_MILLIS, MAX_PING_TIMEOUT_MILLIS)
            requestStatus(host, port, effectiveTimeout.toInt())
        } catch (_: IOException) {
            // 对端没开、已关闭或超时都属于“没有世界”，按 ping 契约返回 null。
            null
        }

    private fun requestStatus(
        host: String,
        port: Int,
        timeoutMillis: Int,
    ): LanWorld? =
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMillis)
            socket.soTimeout = timeoutMillis
            writeHandshake(socket.getOutputStream(), host, port)
            readStatus(socket.getInputStream(), port)
        }

    private fun writeHandshake(
        output: OutputStream,
        host: String,
        port: Int,
    ) {
        val address = host.encodeToByteArray()
        val payload = ByteArrayOutputStream()
        writeVarInt(payload, STATUS_PACKET_ID)
        writeVarInt(payload, HANDSHAKE_PROTOCOL_VERSION)
        writeVarInt(payload, address.size)
        payload.write(address)
        // Minecraft 协议里的端口固定是大端无符号 short。
        payload.write((port ushr PORT_SHIFT_BITS) and BYTE_MASK)
        payload.write(port and BYTE_MASK)
        writeVarInt(payload, NEXT_STATE_STATUS)
        output.write(frame(payload.toByteArray()))
        output.write(frame(STATUS_REQUEST_PAYLOAD))
        output.flush()
    }

    private fun readStatus(
        input: InputStream,
        port: Int,
    ): LanWorld? {
        val length = readVarInt(input)
        val packet =
            if (length == null || length !in 1..MAX_STATUS_PACKET_BYTES) {
                null
            } else {
                readFully(input, length)
            }
        return packet?.let { parseStatusPacket(it, port) }
    }

    private fun parseStatusPacket(
        packet: ByteArray,
        port: Int,
    ): LanWorld? {
        val packetInput = ByteArrayInputStream(packet)
        val packetId = readVarInt(packetInput)
        val jsonLength = if (packetId == STATUS_PACKET_ID) readVarInt(packetInput) else null
        val remaining = packetInput.available()
        if (jsonLength == null || jsonLength !in 1..remaining) {
            return null
        }
        val offset = packet.size - remaining
        return parseStatusJson(String(packet, offset, jsonLength, Charsets.UTF_8), port)
    }

    private fun frame(payload: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(payload.size + MAX_VARINT_BYTES)
        writeVarInt(output, payload.size)
        output.write(payload)
        return output.toByteArray()
    }
}

/** 把 status JSON 映射成 [LanWorld]；结构不合法时返回 null，供 ping 与单测共用。 */
internal fun parseStatusJson(
    json: String,
    port: Int,
): LanWorld? {
    val root =
        try {
            JsonParser.parseString(json)
        } catch (_: JsonParseException) {
            null
        }
    if (root == null || !root.isJsonObject) {
        return null
    }
    val rootObject = root.asJsonObject
    val versionElement = rootObject.get("version")
    val versionObject =
        if (versionElement != null && versionElement.isJsonObject) {
            versionElement.asJsonObject
        } else {
            null
        }
    val name = flattenDescription(rootObject.get("description")).trim()
    return LanWorld(
        name = name.ifBlank { DEFAULT_WORLD_NAME },
        version = textOf(versionObject?.get("name")),
        port = port,
    )
}

/** description 可能是字符串、组件对象或组件数组，这里统一取纯文本。 */
private fun flattenDescription(element: JsonElement?): String =
    when {
        element == null -> ""
        element.isJsonPrimitive -> element.asString
        element.isJsonArray ->
            element.asJsonArray.joinToString("") { child ->
                flattenDescription(child)
            }
        element.isJsonObject -> flattenDescriptionObject(element.asJsonObject)
        else -> ""
    }

private fun flattenDescriptionObject(element: JsonObject): String =
    textOf(element.get("text")) + flattenDescription(element.get("extra"))

private fun textOf(element: JsonElement?): String =
    if (element != null && element.isJsonPrimitive) element.asString else ""

/** VarInt 编码：最多 5 字节，每段 7 位，最高位表示是否还有后续字节。 */
internal fun writeVarInt(
    output: OutputStream,
    value: Int,
) {
    var remaining = value
    do {
        val segment = remaining and VARINT_SEGMENT_MASK
        remaining = remaining ushr VARINT_SHIFT_BITS
        output.write(if (remaining != 0) segment or VARINT_CONTINUATION_BIT else segment)
    } while (remaining != 0)
}

/** 读一个 VarInt；EOF 或超过 5 字节时返回 null，不让坏报文无限占用连接。 */
internal fun readVarInt(input: InputStream): Int? {
    var result = 0
    var shift = 0
    var byte = 0
    do {
        byte = input.read()
        if (byte < 0) {
            return null
        }
        result = result or ((byte and VARINT_SEGMENT_MASK) shl shift)
        shift += VARINT_SHIFT_BITS
    } while (byte and VARINT_CONTINUATION_BIT != 0 && shift < MAX_VARINT_BITS)
    return result
}

internal fun readFully(
    input: InputStream,
    length: Int,
): ByteArray? {
    val buffer = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val read = input.read(buffer, offset, length - offset)
        if (read < 0) {
            return null
        }
        offset += read
    }
    return buffer
}

private const val HANDSHAKE_PROTOCOL_VERSION = 4
private const val NEXT_STATE_STATUS = 1
private const val STATUS_PACKET_ID = 0
private const val MAX_VARINT_BYTES = 5
private const val VARINT_SEGMENT_MASK = 0x7F
private const val VARINT_CONTINUATION_BIT = 0x80
private const val VARINT_SHIFT_BITS = 7
private const val MAX_VARINT_BITS = MAX_VARINT_BYTES * VARINT_SHIFT_BITS
private const val BYTE_MASK = 0xFF
private const val PORT_SHIFT_BITS = 8
private const val MAX_STATUS_PACKET_BYTES = 1_048_576
private const val MIN_PING_TIMEOUT_MILLIS = 1L
private const val MAX_PING_TIMEOUT_MILLIS = 10_000L

private val STATUS_REQUEST_PAYLOAD = byteArrayOf(STATUS_PACKET_ID.toByte())

/** description 与 version 都缺失时的兜底名称；只用于界面展示。 */
internal val DEFAULT_WORLD_NAME: String
    get() = I18n.t("multiplayer.mcStatusPing.1")
