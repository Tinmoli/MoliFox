// Scaffolding 协议常量：帧上限、响应状态、标准协议名与状态判定，供服务端与客户端共用。
package dev.molifox.multiplayer.protocol

/** Scaffolding 协议常量；数值与协议文档一致，修改前需确认互通影响。 */
object ScaffoldingProtocol {
    /** 请求类型最大字节数；超过即视为非法帧并断开连接。 */
    const val MAX_TYPE_LENGTH = 128

    /** 请求体/响应体最大字节数；超过即视为非法帧并断开连接。 */
    const val MAX_BODY_LENGTH = 65536

    /** 成功响应。 */
    const val STATUS_OK = 0

    /** 协议自定义错误的起始状态码（含）。 */
    const val STATUS_PROTOCOL_ERROR_MIN = 32

    /** 协议自定义错误的结束状态码（不含）。 */
    const val STATUS_PROTOCOL_ERROR_END = 64

    /** 未知错误；响应体为 UTF-8 错误说明。 */
    const val STATUS_UNKNOWN_ERROR = 255

    /** `c:server_port` 在 Minecraft 服务端未就绪时返回的状态。 */
    const val STATUS_SERVER_NOT_READY = 32

    /** 测试联机中心是否正常运行，原样回显请求体。 */
    const val PING = "c:ping"

    /** 协商双方支持的协议列表，`\0` 分隔。 */
    const val PROTOCOLS = "c:protocols"

    /** 获取 Minecraft 服务端端口，响应体为大端 u16。 */
    const val SERVER_PORT = "c:server_port"

    /** 玩家心跳/登记，请求体为 JSON。 */
    const val PLAYER_PING = "c:player_ping"

    /** 获取房间玩家列表，响应体为 JSON 数组。 */
    const val PLAYER_PROFILES_LIST = "c:player_profiles_list"

    /** 5 个基础协议，顺序即协议协商时的声明顺序。 */
    val STANDARD_PROTOCOLS: List<String> =
        listOf(
            PING,
            PROTOCOLS,
            SERVER_PORT,
            PLAYER_PING,
            PLAYER_PROFILES_LIST,
        )

    /** 是否是协议定义的错误状态码（32..63）。 */
    fun isProtocolError(status: Int): Boolean = status in STATUS_PROTOCOL_ERROR_MIN until STATUS_PROTOCOL_ERROR_END

    /** 是否是合法响应状态（0、32..63、255）；其他值说明对端实现异常。 */
    fun isKnownStatus(status: Int): Boolean =
        status == STATUS_OK || isProtocolError(status) || status == STATUS_UNKNOWN_ERROR
}
