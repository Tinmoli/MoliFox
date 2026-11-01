// 把一条已解码的 Scaffolding 请求转成响应：只处理协议语义，不负责 socket 生命周期。
package dev.molifox.multiplayer.scaffolding

import dev.molifox.core.i18n.I18n
import dev.molifox.multiplayer.protocol.ScaffoldingPayloads
import dev.molifox.multiplayer.protocol.ScaffoldingProtocol
import dev.molifox.multiplayer.protocol.ScaffoldingRequest
import dev.molifox.multiplayer.protocol.ScaffoldingResponse
import java.net.Socket

internal class ScaffoldingRequestHandler(
    private val registry: ScaffoldingPlayerRegistry,
    private val minecraftPort: () -> Int?,
) {
    fun handle(
        socket: Socket,
        request: ScaffoldingRequest,
    ): ScaffoldingResponse =
        when (request.type) {
            ScaffoldingProtocol.PING -> success(request.body)
            ScaffoldingProtocol.PROTOCOLS -> negotiateProtocols(request.body)
            ScaffoldingProtocol.SERVER_PORT -> describeServerPort()
            ScaffoldingProtocol.PLAYER_PING -> registerPlayer(socket, request.body)
            ScaffoldingProtocol.PLAYER_PROFILES_LIST -> listPlayers()
            else -> failure(I18n.t("multiplayer.scaffoldingRequestHandler.1", request.type))
        }

    private fun negotiateProtocols(body: ByteArray): ScaffoldingResponse =
        if (ScaffoldingPayloads.decodeProtocolNames(body) == null) {
            failure(I18n.t("multiplayer.scaffoldingRequestHandler.2"))
        } else {
            success(ScaffoldingPayloads.encodeProtocolNames(ScaffoldingProtocol.STANDARD_PROTOCOLS))
        }

    private fun describeServerPort(): ScaffoldingResponse =
        minecraftPort()
            ?.let { port -> success(ScaffoldingPayloads.encodeServerPort(port)) }
            ?: notReady()

    private fun registerPlayer(
        socket: Socket,
        body: ByteArray,
    ): ScaffoldingResponse =
        parsePlayerPing(body).fold(
            onSuccess = { player ->
                registry.register(socket, player)
                success()
            },
            onFailure = { error -> failure(error.message ?: I18n.t("multiplayer.scaffoldingRequestHandler.3")) },
        )

    private fun listPlayers(): ScaffoldingResponse = success(encodePlayerProfiles(registry.players.value))

    private fun success(body: ByteArray = ByteArray(0)): ScaffoldingResponse =
        ScaffoldingResponse(ScaffoldingProtocol.STATUS_OK, body)

    private fun notReady(): ScaffoldingResponse =
        ScaffoldingResponse(ScaffoldingProtocol.STATUS_SERVER_NOT_READY, ByteArray(0))

    private fun failure(message: String): ScaffoldingResponse =
        ScaffoldingResponse(
            ScaffoldingProtocol.STATUS_UNKNOWN_ERROR,
            message.encodeToByteArray(),
        )
}
