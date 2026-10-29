// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责结合官方 Quick Play 记录和客户端日志追踪每个游戏进程的实际进入与离开。

package dev.molifox.core

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.io.path.*

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
        val message = INFO_LINE.matchEntire(line)?.groupValues?.get(1) ?: return
        CONNECTING.matchEntire(message)?.let { match ->
            store.recentUsage.endSession(sessionId, true)
            pendingServer = null
            val host = match.groupValues[1].trim().removeSurrounding("[", "]")
            val port = match.groupValues[2].toIntOrNull() ?: return
            if (port !in 1..65535 || host.isBlank() || host.any { it.isWhitespace() }) return
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
            if (!path.isRegularFile() || path.fileSize() > MAX_RECORD_BYTES) return
            val text = path.readText()
            if (text == lastContents) return
            val newest =
                quickPlayEntries(JsonParser.parseString(text)).maxByOrNull { it.entered } ?: return
            if (newest.entered > lastOfficialEntry) {
                val key =
                    if (newest.kind == EntryKind.Server) canonicalAddress(newest.key)
                    else newest.key
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
            .onFailure { store.log("读取游戏进入记录失败：${it.javaClass.simpleName}") }
    }

    @Synchronized fun finish() = store.recentUsage.endSession(sessionId, true)

    private fun canonicalAddress(address: String): String =
        listServers(store.gamePath(instance))
            .firstOrNull {
                sameServerAddress(parseServerAddress(it.ip), parseServerAddress(address))
            }
            ?.ip ?: address

    private companion object {
        const val MAX_RECORD_BYTES = 128 * 1024
        val INFO_LINE = Regex("^\\[[^\\]]+]\\s*\\[[^\\]]+/(?:INFO|WARN|ERROR)]\\s*:?\\s*(.*)$")
        val CONNECTING = Regex("^Connecting to (.+), (\\d{1,5})$")
        val DISCONNECTED =
            Regex(
                "^(?:Disconnected|Lost connection|Stopping!|Connecting to Realms|Couldn't connect|Failed to connect|Starting integrated|Starting minecraft server)"
            )
        val ENTERED = Regex("(?:Loaded \\d+ advancements|\\[(?:System|CHAT)](?: \\[CHAT])?.+)")
    }
}

internal data class QuickPlayEntry(val kind: EntryKind, val key: String, val entered: Instant)

internal fun quickPlayEntries(element: JsonElement): List<QuickPlayEntry> {
    if (element.isJsonArray) return element.asJsonArray.flatMap(::quickPlayEntries)
    if (!element.isJsonObject) return emptyList()
    val json = element.asJsonObject
    val world = json.get("world")?.takeIf { it.isJsonObject }?.asJsonObject ?: json
    val kind =
        when (world.string("type").lowercase()) {
            "multiplayer" -> EntryKind.Server
            "singleplayer" -> EntryKind.World
            else -> null
        }
    if (kind != null) {
        var key = world.string("identifier")
        if (kind == EntryKind.Server) {
            val port = world.int("port", DEFAULT_SERVER_PORT)
            val address = parseServerAddress(key) ?: return emptyList()
            key =
                formatServerAddress(
                    address.copy(port = port.takeIf { it != DEFAULT_SERVER_PORT } ?: address.port)
                )
        }
        val rawTime =
            json.get("lastPlayedTime") ?: world.get("lastPlayedTime") ?: return emptyList()
        val entered =
            runCatching {
                if (rawTime.asJsonPrimitive.isNumber) Instant.ofEpochMilli(rawTime.asLong)
                else Instant.parse(rawTime.asString)
            }
                .getOrNull() ?: return emptyList()
        if (key.isNotBlank()) return listOf(QuickPlayEntry(kind, key, entered))
    }
    return json.entrySet().flatMap { (_, value) -> quickPlayEntries(value) }
}

fun sameServerAddress(left: ServerAddress?, right: ServerAddress?): Boolean =
    left != null && right != null && left.port == right.port && left.host.equals(right.host, true)

internal fun formatServerAddress(address: ServerAddress): String {
    val host = if (':' in address.host) "[${address.host}]" else address.host
    return if (address.port == DEFAULT_SERVER_PORT) host else "$host:${address.port}"
}
