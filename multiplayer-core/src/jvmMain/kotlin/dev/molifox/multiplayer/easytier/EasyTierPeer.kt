// 解析 easytier-cli 的 peer JSON，并按 MoliFox 自有主机名前缀定位房主节点。
package dev.molifox.multiplayer.easytier

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.molifox.core.i18n.I18n
import java.io.IOException

/** `easytier-cli -o json peer` 中本项目需要的一个节点。 */
data class EasyTierPeer(
    /** EasyTier 主机名；房主固定为 `MoliFox-server-<Scaffolding 端口>`。 */
    val hostname: String,
    /** 节点虚拟 IPv4；房客用它做端口转发的目标地址。 */
    val ipv4: String,
    /** 延迟毫秒；`-` 或无法解析时为 null。 */
    val latencyMillis: Double?,
    /** EasyTier 的 `cost`：`Local`/`p2p`/`relay(...)`，用于判断打洞还是中继。 */
    val cost: String? = null,
)

/** 已定位到的房主：虚拟 IP 与 hostname 末尾的 Scaffolding 端口。 */
data class EasyTierHost(
    val peer: EasyTierPeer,
    val scaffoldingPort: Int,
)

/** peer 列表解析与房主定位；纯函数，便于用固定 JSON 向量测试。 */
object EasyTierPeers {
    /** MoliFox 房主 hostname 前缀；刻意与其它启动器分离，避免错误加入不兼容房间。 */
    const val HOST_HOSTNAME_PREFIX = "MoliFox-server-"

    private const val FIELD_HOSTNAME = "hostname"
    private const val FIELD_IPV4 = "ipv4"
    private const val FIELD_LATENCY = "lat_ms"
    private const val FIELD_COST = "cost"
    private const val MIN_PORT = 1
    private const val MAX_PORT = 65_535

    /** 解析 CLI 输出；容忍前面混入的告警日志，只取第一段 JSON 数组。 */
    fun parse(output: String): Result<List<EasyTierPeer>> =
        extractJsonArray(output).mapCatching { array -> array.mapNotNull(::parsePeer) }

    /** 从 hostname 末尾解析 Scaffolding 端口；不是房主或端口非法时返回 null。 */
    fun portFromHostname(hostname: String): Int? {
        if (!hostname.startsWith(HOST_HOSTNAME_PREFIX)) return null
        val suffix = hostname.removePrefix(HOST_HOSTNAME_PREFIX)
        val port = suffix.toIntOrNull() ?: return null
        return port.takeIf { it in MIN_PORT..MAX_PORT }
    }

    /** 所有可识别的房主节点；用于等待循环里区分为“还没出现”与“出现多个”。 */
    fun hosts(peers: List<EasyTierPeer>): List<EasyTierHost> =
        peers.mapNotNull { peer ->
            portFromHostname(peer.hostname)?.let { port -> EasyTierHost(peer, port) }
        }

    /** 找到唯一的房主节点；没有或重复时给出可读失败原因。 */
    fun findHost(peers: List<EasyTierPeer>): Result<EasyTierHost> {
        val hosts = hosts(peers)
        return when {
            hosts.isEmpty() -> Result.failure(IOException(I18n.t("multiplayer.easyTierPeer.1")))
            hosts.size > 1 -> Result.failure(IOException(I18n.t("multiplayer.easyTierPeer.2")))
            else -> Result.success(hosts.first())
        }
    }

    private fun extractJsonArray(output: String): Result<List<JsonElement>> {
        val start = output.indexOf('[')
        val end = output.lastIndexOf(']')
        if (start < 0 || end <= start) {
            return Result.failure(IOException(I18n.t("multiplayer.easyTierPeer.3")))
        }
        val json = output.substring(start, end + 1)
        val element =
            runCatching { JsonParser.parseString(json) }
                .getOrElse { failure ->
                    return Result.failure(IOException(I18n.t("multiplayer.easyTierPeer.4"), failure))
                }
        if (!element.isJsonArray) {
            return Result.failure(IOException(I18n.t("multiplayer.easyTierPeer.5")))
        }
        return Result.success(element.asJsonArray.toList())
    }

    private fun parsePeer(element: JsonElement): EasyTierPeer? {
        if (!element.isJsonObject) return null
        val json = element.asJsonObject
        val hostname = json.stringOrNull(FIELD_HOSTNAME)?.takeIf(String::isNotBlank) ?: return null
        val ipv4 = json.stringOrNull(FIELD_IPV4)?.takeIf(String::isNotBlank) ?: return null
        return EasyTierPeer(
            hostname,
            ipv4,
            latencyMillis = latency(json),
            cost = json.stringOrNull(FIELD_COST),
        )
    }

    /** `lat_ms` 在 CLI 里既是数字字符串也可能是 `-`；无法解析按未知处理。 */
    private fun latency(json: JsonObject): Double? =
        json
            .stringOrNull(FIELD_LATENCY)
            ?.takeIf { it != "-" }
            ?.toDoubleOrNull()
}

private fun JsonObject.stringOrNull(key: String): String? {
    val value = get(key) ?: return null
    return if (value.isJsonPrimitive) value.asJsonPrimitive.asString else null
}
