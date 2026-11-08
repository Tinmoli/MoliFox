// JVM Scaffolding 客户端门面：连接、握手、心跳与玩家/端口状态流。
package dev.molifox.multiplayer.scaffolding

import dev.molifox.core.i18n.I18n
import dev.molifox.multiplayer.protocol.ScaffoldingPayloads
import dev.molifox.multiplayer.protocol.ScaffoldingProtocol
import dev.molifox.multiplayer.protocol.ScaffoldingResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

/**
 * 连接房主 Scaffolding 服务端的客户端。
 *
 * [connect] 建立 TCP 连接后依次发送 `c:player_ping` 与 `c:server_port`；之后每
 * [heartbeatIntervalMillis] 毫秒重发一次 `c:player_ping` 作为心跳。连接断开、EOF 或
 * 心跳失败时触发 [onDisconnected]，后台错误通过 [onError] 上报。
 *
 * 客户端单次使用：失败或 [close] 后需要重新创建实例。
 *
 * @param player 本机玩家资料，`machineId` 必须在同一房间内稳定且唯一。
 * @param heartbeatIntervalMillis 心跳间隔，默认 5 秒。
 * @param requestTimeoutMillis 单次请求等待响应的超时，也用于 TCP 连接超时。
 * @param onDisconnected 断线回调；参数为 null 表示对端正常关闭。
 * @param onError 非断线类后台错误（如收到没有等待者的响应）。
 */
class ScaffoldingClient(
    private val host: String,
    private val port: Int,
    private val player: ScaffoldingPlayer,
    private val heartbeatIntervalMillis: Long = DEFAULT_HEARTBEAT_INTERVAL_MILLIS,
    private val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
    onDisconnected: (Throwable?) -> Unit = { failure -> failure?.printStackTrace() },
    onError: (Throwable) -> Unit = { failure -> failure.printStackTrace() },
) : AutoCloseable {
    init {
        require(host.isNotBlank()) { I18n.t("multiplayer.scaffoldingClient.1") }
        require(port in MIN_PORT..MAX_PORT) { I18n.t("multiplayer.scaffoldingClient.2", MIN_PORT, MAX_PORT, port) }
        require(heartbeatIntervalMillis > 0) { I18n.t("multiplayer.scaffoldingClient.3") }
        require(requestTimeoutMillis > 0) { I18n.t("multiplayer.scaffoldingClient.4") }
    }

    private val session =
        ScaffoldingClientSession(
            host = host,
            port = port,
            player = player,
            heartbeatIntervalMillis = heartbeatIntervalMillis,
            requestTimeoutMillis = requestTimeoutMillis,
            onDisconnected = onDisconnected,
            onError = onError,
        )
    private val mutablePlayers = MutableStateFlow<List<ScaffoldingPlayer>>(emptyList())
    private val mutableServerPort = MutableStateFlow<Int?>(null)

    /** 最近一次 `c:player_profiles_list` 的结果；连接前为空列表。 */
    val players: StateFlow<List<ScaffoldingPlayer>> = mutablePlayers.asStateFlow()

    /** 房主声明的 MC 端口；null 表示房主尚未就绪。 */
    val serverPort: StateFlow<Int?> = mutableServerPort.asStateFlow()

    /** 连接并完成握手；失败时返回明确原因并释放连接。 */
    suspend fun connect(): Result<Unit> {
        val open = session.open()
        if (open.isFailure) {
            return open
        }
        val handshake = handshake()
        if (handshake.isFailure) {
            session.close()
        }
        return handshake
    }

    /** 重新拉取玩家列表并更新 [players]。 */
    suspend fun refreshPlayers(): Result<List<ScaffoldingPlayer>> =
        session.request(ScaffoldingProtocol.PLAYER_PROFILES_LIST, ByteArray(0)).fold(
            onSuccess = { response ->
                if (response.status == ScaffoldingProtocol.STATUS_OK) {
                    parsePlayerProfiles(response.body).onSuccess { players ->
                        mutablePlayers.value = players
                    }
                } else {
                    Result.failure(
                        IOException(I18n.t("multiplayer.scaffoldingClient.5", response.status)),
                    )
                }
            },
            onFailure = { failure -> Result.failure(failure) },
        )

    /** 重新询问房主 MC 端口并更新 [serverPort]。 */
    suspend fun refreshServerPort(): Result<Int?> =
        session.request(ScaffoldingProtocol.SERVER_PORT, ByteArray(0)).fold(
            onSuccess = { response ->
                serverPortFrom(response).onSuccess { port -> mutableServerPort.value = port }
            },
            onFailure = { failure -> Result.failure(failure) },
        )

    override fun close() {
        session.close()
    }

    private suspend fun handshake(): Result<Unit> =
        session.request(ScaffoldingProtocol.PLAYER_PING, encodePlayerPing(player)).fold(
            onSuccess = { response ->
                if (response.status == ScaffoldingProtocol.STATUS_OK) {
                    refreshServerPort().map { }
                } else {
                    Result.failure(
                        IOException(I18n.t("multiplayer.scaffoldingClient.6", response.status)),
                    )
                }
            },
            onFailure = { failure -> Result.failure(failure) },
        )

    private fun serverPortFrom(response: ScaffoldingResponse): Result<Int?> =
        when (response.status) {
            ScaffoldingProtocol.STATUS_OK -> {
                val port = ScaffoldingPayloads.decodeServerPort(response.body)
                if (port == null) {
                    Result.failure(IOException(I18n.t("multiplayer.scaffoldingClient.7")))
                } else {
                    Result.success(port)
                }
            }
            ScaffoldingProtocol.STATUS_SERVER_NOT_READY -> Result.success(null)
            else -> Result.failure(IOException(I18n.t("multiplayer.scaffoldingClient.8", response.status)))
        }
}

private const val MIN_PORT = 1
private const val MAX_PORT = 65_535
internal const val DEFAULT_HEARTBEAT_INTERVAL_MILLIS = 5_000L
internal const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 5_000L
