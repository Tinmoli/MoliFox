// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责 Minecraft 服务器列表查询的现代与旧版协议实现。

package dev.molifox.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/** 现代握手使用的协议号：0x7F 表示「未指定版本」，服务端仍会返回状态 JSON。 */
private const val STATUS_PROTOCOL_VERSION = 0x7F
private const val NANOS_PER_MILLISECOND = 1_000_000L

/** 状态响应上限：正常只有几 KB，部分服务端会把整张 favicon 塞进来，留余量但不无限读。 */
private const val MAX_PACKET_BYTES = 2 * 1024 * 1024

/** 旧版（0xFE）响应的字符数上限；协议本身用 unsigned short 表示长度。 */
private const val MAX_LEGACY_CHARS = 32_768

/** 防止服务端用异常大的 sample 数组占用界面和内存；原版列表本来也只返回少量样本。 */
private const val MAX_PLAYER_SAMPLE = 100

/** 旧版（0xFE）查询的请求包 ID 与响应包 ID。 */
private const val LEGACY_PING_REQUEST_ID = 0xFE
private const val LEGACY_PING_RESPONSE_ID = 0xFF

/** 旧版响应按 `\u0000` 拆分后，在线人数与人数上限所在的下标。 */
private const val LEGACY_PLAYERS_FIELD_INDEX = 4
private const val LEGACY_MAX_PLAYERS_FIELD_INDEX = 5

/** 现代协议：握手 → status 请求 → 状态 JSON；任何一步不符合预期都返回 null 交给旧版兜底。 */
internal fun modernPing(
    host: String,
    port: Int,
    timeoutMillis: Int,
    connection: InetSocketAddress? = null,
    onFailure: (Exception) -> Unit = {},
): ServerStatus? {
    val socket = Socket()
    return try {
        val connectingAt = System.nanoTime()
        socket.tcpNoDelay = true
        socket.soTimeout = timeoutMillis
        socket.connect(connection ?: InetSocketAddress(host, port), timeoutMillis)
        socket.soTimeout =
            (timeoutMillis - (System.nanoTime() - connectingAt) / NANOS_PER_MILLISECOND).toInt().coerceAtLeast(1)
        val startedAt = System.nanoTime()
        val output = socket.getOutputStream()
        val handshake =
            ByteArrayOutputStream().apply {
                writeVarInt(0x00)
                writeVarInt(STATUS_PROTOCOL_VERSION)
                writeString(host)
                writeUnsignedShort(port)
                writeVarInt(1)
            }
        output.writePacket(handshake.toByteArray())
        output.writePacket(byteArrayOf(0x00))
        output.flush()
        readModernStatus(socket.getInputStream().buffered(), startedAt)
    } catch (failure: IOException) {
        onFailure(failure)
        null
    } catch (failure: IllegalArgumentException) {
        onFailure(failure)
        null
    } finally {
        runCatching { socket.close() }
    }
}

/** 读取 status 响应包；包长度、包 ID 或 JSON 长度不符合协议时返回 null。 */
private fun readModernStatus(
    input: InputStream,
    startedAt: Long,
): ServerStatus? {
    val packetLength = input.readVarInt()
    if (packetLength <= 0 || packetLength > MAX_PACKET_BYTES) return null
    val packet = ByteArrayInputStream(input.readFully(packetLength))
    val jsonLength = if (packet.readVarInt() == 0x00) packet.readVarInt() else 0
    return if (jsonLength <= 0 || jsonLength > packet.available()) {
        null
    } else {
        val json = String(packet.readFully(jsonLength), Charsets.UTF_8)
        val latency = ((System.nanoTime() - startedAt) / 1_000_000L).toInt().coerceAtLeast(0)
        statusFromJson(json, latency)
    }
}

/**
 * 旧版（1.4–1.6 及更早）查询：`0xFE 0x01`，响应是 `0xFF` + 长度 + UTF-16BE 字符串。 字段用 `\u0000`
 * 分隔：`§1`、协议号、版本名、MOTD、在线人数、上限（更早的版本没有后两项）。
 */
internal fun legacyPing(
    host: String,
    port: Int,
    timeoutMillis: Int,
    connection: InetSocketAddress? = null,
    onFailure: (Exception) -> Unit = {},
): ServerStatus? {
    val socket = Socket()
    return try {
        val connectingAt = System.nanoTime()
        socket.tcpNoDelay = true
        socket.soTimeout = timeoutMillis
        socket.connect(connection ?: InetSocketAddress(host, port), timeoutMillis)
        socket.soTimeout =
            (timeoutMillis - (System.nanoTime() - connectingAt) / NANOS_PER_MILLISECOND).toInt().coerceAtLeast(1)
        val startedAt = System.nanoTime()
        val output = socket.getOutputStream()
        output.write(LEGACY_PING_REQUEST_ID)
        output.write(0x01)
        output.flush()
        readLegacyStatus(socket.getInputStream(), startedAt)
    } catch (failure: IOException) {
        onFailure(failure)
        null
    } catch (failure: IllegalArgumentException) {
        onFailure(failure)
        null
    } finally {
        runCatching { socket.close() }
    }
}

/** 读取 `0xFF` + 长度 + UTF-16BE 文本；首字节、长度或文本上限不符合旧版协议时返回 null。 */
private fun readLegacyStatus(
    input: InputStream,
    startedAt: Long,
): ServerStatus? {
    if (input.read() != LEGACY_PING_RESPONSE_ID) return null
    val high = input.read()
    val low = input.read()
    val length = if (high < 0 || low < 0) 0 else (high shl 8) or low
    return if (length <= 0 || length > MAX_LEGACY_CHARS) {
        null
    } else {
        val text = String(input.readFully(length * 2), Charsets.UTF_16BE)
        val latency = ((System.nanoTime() - startedAt) / 1_000_000L).toInt().coerceAtLeast(0)
        statusFromLegacy(text, latency)
    }
}

internal fun statusFromJson(
    json: String,
    latency: Int,
): ServerStatus? {
    val root = runCatching { JsonParser.parseString(json) }.getOrNull()
    val obj = root?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    val players = obj.get("players")?.takeIf { it.isJsonObject }?.asJsonObject
    val version = obj.get("version")?.takeIf { it.isJsonObject }?.asJsonObject
    return ServerStatus(
        online = true,
        players = (players?.intValue("online") ?: 0).coerceAtLeast(0),
        maxPlayers = (players?.intValue("max") ?: 0).coerceAtLeast(0),
        motd = parseMotd(obj.get("description")),
        version = version?.stringValue("name").orEmpty(),
        ping = latency,
        playerSample = players?.playerSample().orEmpty(),
    )
}

private fun JsonObject.playerSample(): List<String> =
    get("sample")
        ?.takeIf { it.isJsonArray }
        ?.asJsonArray
        ?.asSequence()
        ?.mapNotNull { entry ->
            entry
                .takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.stringValue("name")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }?.distinct()
        ?.take(MAX_PLAYER_SAMPLE)
        ?.toList()
        .orEmpty()

private fun statusFromLegacy(
    text: String,
    latency: Int,
): ServerStatus {
    val parts = text.trimEnd('\u0000').split('\u0000')
    // 至少要有版本名；MOTD 行缺失时退回版本名，避免出现空行。
    val version = parts.getOrNull(2)?.trim().orEmpty()
    val motdText = parts.getOrNull(3) ?: version
    return ServerStatus(
        online = true,
        players =
            (parts.getOrNull(LEGACY_PLAYERS_FIELD_INDEX)?.trim()?.toIntOrNull() ?: 0)
                .coerceAtLeast(0),
        maxPlayers =
            (parts.getOrNull(LEGACY_MAX_PLAYERS_FIELD_INDEX)?.trim()?.toIntOrNull() ?: 0)
                .coerceAtLeast(0),
        motd = parseMotdText(motdText),
        version = version,
        ping = latency,
    )
}

private fun JsonObject.intValue(key: String): Int =
    runCatching { get(key)?.takeIf { it.isJsonPrimitive }?.asInt }.getOrNull() ?: 0

private fun JsonObject.stringValue(key: String): String =
    runCatching {
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    }.getOrNull()
        .orEmpty()
