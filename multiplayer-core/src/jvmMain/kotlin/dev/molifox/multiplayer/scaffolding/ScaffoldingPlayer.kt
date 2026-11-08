// Scaffolding 玩家模型与协议 JSON 编解码；字段名与 Scaffolding 协议保持一致。
package dev.molifox.multiplayer.scaffolding

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import dev.molifox.core.i18n.I18n

/** 协议 JSON 中玩家的 `kind` 字段：房主或普通玩家。 */
enum class ScaffoldingPlayerKind {
    HOST,
    GUEST,
}

/** Scaffolding 房间玩家；[machineId] 由客户端稳定生成，用于去重与超时清理。 */
data class ScaffoldingPlayer(
    val machineId: String,
    val name: String,
    val vendor: String = "",
    val kind: ScaffoldingPlayerKind = ScaffoldingPlayerKind.GUEST,
)

internal fun encodePlayerPing(player: ScaffoldingPlayer): ByteArray =
    JsonObject()
        .apply {
            addProperty("name", player.name)
            addProperty("machine_id", player.machineId)
            addProperty("vendor", player.vendor)
        }.toString()
        .encodeToByteArray()

internal fun encodePlayerProfiles(players: List<ScaffoldingPlayer>): ByteArray =
    JsonArray()
        .apply { players.forEach { player -> add(profileJson(player)) } }
        .toString()
        .encodeToByteArray()

internal fun parsePlayerPing(body: ByteArray): Result<ScaffoldingPlayer> =
    parseJson(body).mapCatching { element ->
        val json = element.asObject("player_ping")
        ScaffoldingPlayer(
            machineId = json.requireString("machine_id"),
            name = json.requireString("name"),
            vendor = json.stringOrNull("vendor").orEmpty(),
        )
    }

internal fun parsePlayerProfiles(body: ByteArray): Result<List<ScaffoldingPlayer>> =
    parseJson(body).mapCatching { element ->
        if (!element.isJsonArray) {
            throw IllegalArgumentException(I18n.t("multiplayer.scaffoldingPlayer.1"))
        }
        element.asJsonArray.map { item -> profileFromJson(item.asObject(I18n.t("multiplayer.scaffoldingPlayer.2"))) }
    }

private fun profileJson(player: ScaffoldingPlayer): JsonObject =
    JsonObject().apply {
        addProperty("name", player.name)
        addProperty("machine_id", player.machineId)
        addProperty("vendor", player.vendor)
        addProperty("kind", player.kind.name)
    }

private fun profileFromJson(json: JsonObject): ScaffoldingPlayer =
    ScaffoldingPlayer(
        machineId = json.requireString("machine_id"),
        name = json.requireString("name"),
        vendor = json.stringOrNull("vendor").orEmpty(),
        kind =
            when (json.stringOrNull("kind")?.uppercase()) {
                "HOST" -> ScaffoldingPlayerKind.HOST
                else -> ScaffoldingPlayerKind.GUEST
            },
    )

private fun parseJson(body: ByteArray): Result<JsonElement> =
    try {
        Result.success(JsonParser.parseString(body.decodeToString()))
    } catch (failure: JsonParseException) {
        Result.failure(IllegalArgumentException(I18n.t("multiplayer.scaffoldingPlayer.3"), failure))
    }

private fun JsonElement.asObject(what: String): JsonObject =
    takeIf { it.isJsonObject }?.asJsonObject
        ?: throw IllegalArgumentException(I18n.t("multiplayer.scaffoldingPlayer.4", what))

private fun JsonObject.stringOrNull(key: String): String? =
    get(key)
        ?.takeIf { it.isJsonPrimitive }
        ?.asJsonPrimitive
        ?.takeIf { it.isString }
        ?.asString

private fun JsonObject.requireString(key: String): String =
    stringOrNull(key)?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException(I18n.t("multiplayer.scaffoldingPlayer.5", key))
