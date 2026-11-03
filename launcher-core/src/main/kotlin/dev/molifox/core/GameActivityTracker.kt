// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责结合官方 Quick Play 记录和客户端日志追踪每个游戏进程的实际进入与离开。

package dev.molifox.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.molifox.core.i18n.I18n
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/** 单个服务器地址允许的最大端口号；聊天行里解析出的端口超出范围即视为无效。 */
private const val MAX_SERVER_PORT = 65535

internal class GameActivityTracker(
    private val store: LauncherStore,
    private val instance: GameInstance,
    private val recording: Path? = null,
) {
    private val sessionId = UUID.randomUUID().toString()
    private var pendingServer: String? = null
    private var lastContents = ""
    private var lastOfficialEntry = Instant.EPOCH

    @Synchronized
    fun accept(line: String) {
        val message = activityMessage(line) ?: return
        CONNECTING.matchEntire(message)?.let { match ->
            store.recentUsage.endSession(sessionId, true)
            pendingServer = null
            val host = match.groupValues[1].trim().removeSurrounding("[", "]")
            val port = match.groupValues[2].toIntOrNull() ?: return
            val invalidAddress =
                port !in 1..MAX_SERVER_PORT ||
                    host.isBlank() ||
                    host.any { it.isWhitespace() }
            if (invalidAddress) return
            pendingServer = canonicalAddress(formatServerAddress(ServerAddress(host, port)))
            return
        }
        if (DISCONNECTED.containsMatchIn(message)) {
            store.recentUsage.endSession(sessionId)
            pendingServer = null
            return
        }
        val server = pendingServer ?: return
        if (ENTERED.matches(message)) {
            store.recentUsage.beginSession(sessionId, EntryKind.Server, instance.id, server)
            pendingServer = null
        }
    }

    @Synchronized
    fun pollQuickPlay() {
        val path = recording ?: return
        runCatching {
            val text =
                path.takeIf { it.isRegularFile() && it.fileSize() <= MAX_RECORD_BYTES }?.readText()
            val newest =
                text
                    ?.takeIf { it != lastContents }
                    ?.let { quickPlayEntries(JsonParser.parseString(it)) }
                    ?.maxByOrNull { it.entered }
            if (text != null && newest != null) {
                if (newest.entered > lastOfficialEntry) {
                    val key =
                        if (newest.kind == EntryKind.Server) {
                            canonicalAddress(newest.key)
                        } else {
                            newest.key
                        }
                    store.recentUsage.beginSession(
                        sessionId,
                        newest.kind,
                        instance.id,
                        key,
                        newest.entered,
                    )
                    lastOfficialEntry = newest.entered
                    pendingServer = null
                }
                lastContents = text
            }
        }.onFailure {
            store.log(I18n.t("miscA1.activityTracker.readFailed", it.javaClass.simpleName))
        }
    }

    @Synchronized fun finish() = store.recentUsage.endSession(sessionId, true)

    private fun canonicalAddress(address: String): String =
        listServers(store.gamePath(instance))
            .firstOrNull {
                sameServerAddress(parseServerAddress(it.ip), parseServerAddress(address))
            }?.ip ?: address

    /** 同时接受 Minecraft 旧式纯文本行和 [GameLogDecoder] 输出的 Log4j XML 事件。 */
    private fun activityMessage(line: String): String? =
        TIMESTAMPED_LINE.matchEntire(line)?.groupValues?.get(1)
            ?: DECODED_LINE.matchEntire(line)?.groupValues?.get(1)

    private companion object {
        const val MAX_RECORD_BYTES = 128 * 1024
        val TIMESTAMPED_LINE =
            Regex("^\\[[^\\]]+]\\s*\\[[^\\]]+/(?:INFO|WARN|ERROR)]\\s*:?\\s*(.*)$")
        val DECODED_LINE = Regex("^\\[[^\\]]+/(?:INFO|WARN|ERROR)]\\s*(.*)$")
        val CONNECTING = Regex("^Connecting to (.+), (\\d{1,5})$")
        val DISCONNECTED =
            Regex(
                "^(?:Disconnected|Lost connection|Stopping!|" +
                    "Connecting to Realms|Couldn't connect|Failed to connect|" +
                    "Starting integrated|Starting minecraft server)",
            )
        val ENTERED = Regex("(?:Loaded \\d+ advancements|\\[(?:System|CHAT)](?: \\[CHAT])?.+)")
    }
}

internal data class QuickPlayEntry(
    val kind: EntryKind,
    val key: String,
    val entered: Instant,
)

internal fun quickPlayEntries(element: JsonElement): List<QuickPlayEntry> {
    if (element.isJsonArray) return element.asJsonArray.flatMap(::quickPlayEntries)
    if (!element.isJsonObject) return emptyList()
    val json = element.asJsonObject
    val world = json.get("world")?.takeIf { it.isJsonObject }?.asJsonObject ?: json
    // null 表示当前对象不是 Quick Play 记录，按原逻辑继续往子元素里找。
    return quickPlayEntry(json, world)
        ?: json.entrySet().flatMap { (_, value) -> quickPlayEntries(value) }
}

/**
 * 解析一条 Quick Play 记录。
 *
 * 返回 null 表示当前对象不是记录，调用方应继续遍历子元素；返回空列表表示确实是记录但
 * 字段缺失或地址解析失败，沿用原逻辑在此停止搜索。
 */
private fun quickPlayEntry(
    json: JsonObject,
    world: JsonObject,
): List<QuickPlayEntry>? {
    val kind = quickPlayKind(world) ?: return null
    val key = quickPlayEntryKey(world, kind) ?: return emptyList()
    val entered = quickPlayEnteredAt(json, world) ?: return emptyList()
    if (key.isBlank()) return null
    return listOf(QuickPlayEntry(kind, key, entered))
}

private fun quickPlayKind(world: JsonObject): EntryKind? =
    when (world.string("type").lowercase()) {
        "multiplayer" -> EntryKind.Server
        "singleplayer" -> EntryKind.World
        else -> null
    }

/** 服务器条目把 identifier 归一化成带端口的地址；地址解析失败返回 null。 */
private fun quickPlayEntryKey(
    world: JsonObject,
    kind: EntryKind,
): String? {
    val key = world.string("identifier")
    if (kind != EntryKind.Server) return key
    val port = world.int("port", DEFAULT_SERVER_PORT)
    val address = parseServerAddress(key) ?: return null
    return formatServerAddress(
        address.copy(port = port.takeIf { it != DEFAULT_SERVER_PORT } ?: address.port),
    )
}

private fun quickPlayEnteredAt(
    json: JsonObject,
    world: JsonObject,
): Instant? {
    val rawTime = json.get("lastPlayedTime") ?: world.get("lastPlayedTime") ?: return null
    return runCatching {
        if (rawTime.asJsonPrimitive.isNumber) {
            Instant.ofEpochMilli(rawTime.asLong)
        } else {
            Instant.parse(rawTime.asString)
        }
    }.getOrNull()
}

fun sameServerAddress(
    left: ServerAddress?,
    right: ServerAddress?,
): Boolean = left != null && right != null && left.port == right.port && left.host.equals(right.host, true)

internal fun formatServerAddress(address: ServerAddress): String {
    val host = if (':' in address.host) "[${address.host}]" else address.host
    return if (address.port == DEFAULT_SERVER_PORT) host else "$host:${address.port}"
}
