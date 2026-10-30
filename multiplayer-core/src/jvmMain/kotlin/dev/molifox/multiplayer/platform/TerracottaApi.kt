// 陶瓦核心的 HTTP 控制接口：只访问 127.0.0.1 上的本机端口，解析 /state 与日志。
package dev.molifox.multiplayer.platform

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** 陶瓦核心 `/state` 的一次快照；字段按核心返回原样保留，解析缺失时容忍。 */
data class TerracottaSnapshot(
    val index: Int,
    val state: String,
    val room: String? = null,
    val url: String? = null,
    val difficulty: String? = null,
    val profiles: List<RemoteProfile> = emptyList(),
    val exceptionType: Int? = null,
)

/** 房间里的一名玩家，字段与陶瓦核心的 profile 结构一致。 */
data class RemoteProfile(
    val machineId: String?,
    val name: String?,
    val kind: String?,
)

/** 本机控制接口客户端；[port] 由核心启动后写入端口文件。 */
class TerracottaApi(
    private val port: Int,
) : TerracottaControl {
    override fun snapshot(): TerracottaSnapshot {
        val json = JsonParser.parseString(get("/state")).asJsonObject
        return TerracottaSnapshot(
            index = json.intOr("index", -1),
            state = json.stringOr("state", "unknown"),
            room = json.stringOrNull("room"),
            url = json.stringOrNull("url"),
            difficulty = json.stringOrNull("difficulty"),
            profiles = json.profiles(),
            exceptionType = json.intOrNull("type"),
        )
    }

    override fun startScanning(
        player: String,
        nodes: List<String>,
    ) {
        get("/state/scanning", playerParams(player) + nodeParams(nodes))
    }

    override fun startGuesting(
        room: String,
        player: String,
        nodes: List<String>,
    ) {
        get(
            "/state/guesting",
            listOf("room" to room) + playerParams(player) + nodeParams(nodes),
        )
    }

    override fun idle() {
        get("/state/ide")
    }

    /** 导出核心日志，用于排查；失败返回 null，不影响界面。 */
    fun logs(): String? =
        runCatching {
            get("/log", listOf("fetch" to "true"))
        }.getOrNull()

    private fun playerParams(player: String): List<Pair<String, String>> =
        listOf("player" to player)

    private fun nodeParams(nodes: List<String>): List<Pair<String, String>> =
        nodes.map { "public_nodes" to it }

    private fun get(
        path: String,
        params: List<Pair<String, String>> = emptyList(),
    ): String {
        val query =
            params.joinToString("&") { (key, value) ->
                "${URLEncoder.encode(key, Charsets.UTF_8)}=" +
                    URLEncoder.encode(value, Charsets.UTF_8)
            }
        val base = "http://127.0.0.1:$port$path"
        val url = if (query.isEmpty()) base else "$base?$query"
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.setRequestProperty("User-Agent", "MoliFox-Launcher")
        return try {
            val code = connection.responseCode
            if (code !in HTTP_OK) throw IOException("陶瓦核心返回 HTTP $code（$path）")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TIMEOUT_MILLIS = 5_000
        private val HTTP_OK = 200..299
    }
}

private fun JsonObject.stringOr(
    key: String,
    fallback: String,
): String = stringOrNull(key) ?: fallback

private fun JsonObject.stringOrNull(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

private fun JsonObject.intOr(
    key: String,
    fallback: Int,
): Int = intOrNull(key) ?: fallback

private fun JsonObject.intOrNull(key: String): Int? =
    runCatching { get(key)?.takeIf { it.isJsonPrimitive }?.asInt }.getOrNull()

private fun JsonObject.profiles(): List<RemoteProfile> {
    val array = get("profiles")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
    return array.mapNotNull { element ->
        runCatching {
            val item = element.asJsonObject
            RemoteProfile(
                machineId = item.stringOrNull("machine_id"),
                name = item.stringOrNull("name"),
                kind = item.stringOrNull("kind"),
            )
        }.getOrNull()
    }
}
