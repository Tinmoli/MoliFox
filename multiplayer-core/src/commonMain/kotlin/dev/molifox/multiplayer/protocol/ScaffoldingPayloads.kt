// 固定格式 Scaffolding 协议体的轻量编解码；JSON 协议体（玩家心跳/列表）留给平台层处理。
package dev.molifox.multiplayer.protocol

/** 固定格式协议体的编解码，只覆盖 `c:server_port` 与 `c:protocols`。 */
object ScaffoldingPayloads {
    private const val PORT_BYTES = 2
    private const val PORT_MAX = 65535
    private const val BYTE_BITS = 8
    private const val BYTE_MASK = 0xFF
    private const val NUL = '\u0000'

    /** 编码 `c:server_port` 响应体：大端序 u16。 */
    fun encodeServerPort(port: Int): ByteArray {
        require(port in 0..PORT_MAX) { "server port out of range: $port" }
        return byteArrayOf((port ushr BYTE_BITS).toByte(), port.toByte())
    }

    /** 解析 `c:server_port` 响应体；长度不是 2 时返回 null。 */
    fun decodeServerPort(body: ByteArray): Int? {
        if (body.size != PORT_BYTES) return null
        val high = body[0].toInt() and BYTE_MASK
        val low = body[1].toInt() and BYTE_MASK
        return (high shl BYTE_BITS) or low
    }

    /** 编码 `c:protocols` 请求/响应体：NUL 分隔的协议名；空列表编码为空数组。 */
    fun encodeProtocolNames(names: List<String>): ByteArray {
        for (name in names) {
            require(isWellFormedFrameType(name)) { "invalid protocol name: $name" }
        }
        return buildString {
            names.forEachIndexed { index, name ->
                if (index > 0) append(NUL)
                append(name)
            }
        }.encodeToByteArray()
    }

    /** 解析 `c:protocols` 请求/响应体；空体表示空列表，格式非法返回 null。 */
    fun decodeProtocolNames(body: ByteArray): List<String>? {
        if (body.isEmpty()) return emptyList()
        val names = splitByNul(body.decodeToString())
        return if (names.all { name -> isWellFormedFrameType(name) }) names else null
    }

    private fun splitByNul(text: String): List<String> {
        val names = mutableListOf<String>()
        var start = 0
        for (index in text.indices) {
            if (text[index] == NUL) {
                names += text.substring(start, index)
                start = index + 1
            }
        }
        names += text.substring(start)
        return names
    }
}
