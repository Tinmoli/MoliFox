// Scaffolding 帧模型与编码：请求 `u8 typeLen + type + u32BE bodyLen + body`，响应
// `u8 status + u32BE bodyLen + body`；增量解析见 ScaffoldingFrameDecoder.kt。
package dev.molifox.multiplayer.protocol

/** 帧级拒绝原因；出现后调用方应断开连接并记录技术日志。 */
enum class ScaffoldingFrameError {
    /** 请求类型长度为 0。 */
    EmptyType,

    /** 请求类型超过 [ScaffoldingProtocol.MAX_TYPE_LENGTH] 字节。 */
    TypeTooLong,

    /** 请求类型含非 ASCII 字节。 */
    TypeNotAscii,

    /** 请求类型不符合 `namespace:value` 且只含小写字母、数字、下划线的约定。 */
    TypeMalformed,

    /** 请求体或响应体超过 [ScaffoldingProtocol.MAX_BODY_LENGTH] 字节。 */
    BodyTooLong,

    /** 响应状态不是 0、32..63 或 255。 */
    StatusInvalid,
}

/** 非法帧；[detail] 只用于技术日志，不直接展示给用户。 */
class ScaffoldingProtocolException(
    val error: ScaffoldingFrameError,
    detail: String,
) : Exception(detail)

/** 请求帧；[body] 为原始字节，内容由具体协议解释。 */
class ScaffoldingRequest(
    val type: String,
    val body: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScaffoldingRequest) return false
        return type == other.type && body.contentEquals(other.body)
    }

    override fun hashCode(): Int = type.hashCode() * HASH_PRIME + body.contentHashCode()
}

/** 响应帧；[status] 为 0、32..63 或 255，[body] 为原始字节。 */
class ScaffoldingResponse(
    val status: Int,
    val body: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScaffoldingResponse) return false
        return status == other.status && body.contentEquals(other.body)
    }

    override fun hashCode(): Int = status * HASH_PRIME + body.contentHashCode()
}

/** 帧编码与合法性校验；不负责 socket 读写。 */
object ScaffoldingFrame {
    private const val REQUEST_TYPE_LENGTH_BYTES = 1
    private const val RESPONSE_STATUS_BYTES = 1
    private const val LENGTH_FIELD_BYTES = 4
    private const val INT_BYTES = 4
    private const val BYTE_BITS = 8

    /** 编码请求帧；类型或用请求体非法时抛出 [ScaffoldingProtocolException]。 */
    fun encodeRequest(
        type: String,
        body: ByteArray = ByteArray(0),
    ): ByteArray {
        validateFrameType(type)
        validateFrameBodyLength(body.size)
        val typeBytes = type.encodeToByteArray()
        val bodyOffset = REQUEST_TYPE_LENGTH_BYTES + typeBytes.size + LENGTH_FIELD_BYTES
        val output = ByteArray(bodyOffset + body.size)
        output[0] = typeBytes.size.toByte()
        typeBytes.copyInto(output, REQUEST_TYPE_LENGTH_BYTES)
        writeUnsignedInt(output, REQUEST_TYPE_LENGTH_BYTES + typeBytes.size, body.size)
        body.copyInto(output, bodyOffset)
        return output
    }

    /** 编码响应帧；状态或响应体非法时抛出 [ScaffoldingProtocolException]。 */
    fun encodeResponse(
        status: Int,
        body: ByteArray = ByteArray(0),
    ): ByteArray {
        validateFrameStatus(status)
        validateFrameBodyLength(body.size)
        val bodyOffset = RESPONSE_STATUS_BYTES + LENGTH_FIELD_BYTES
        val output = ByteArray(bodyOffset + body.size)
        output[0] = status.toByte()
        writeUnsignedInt(output, RESPONSE_STATUS_BYTES, body.size)
        body.copyInto(output, bodyOffset)
        return output
    }

    private fun writeUnsignedInt(
        target: ByteArray,
        offset: Int,
        value: Int,
    ) {
        for (index in 0 until INT_BYTES) {
            val shift = BYTE_BITS * (INT_BYTES - 1 - index)
            target[offset + index] = (value ushr shift).toByte()
        }
    }
}

/** 请求类型合法性：长度 ≤ 128、纯 ASCII、且符合 `namespace:value`。 */
internal fun validateFrameType(type: String) {
    validateFrameTypeLength(type)
    validateFrameTypeCharacters(type)
}

/** 请求体/响应体长度合法性：不能超过 65536 字节。 */
internal fun validateFrameBodyLength(length: Int) {
    if (length > ScaffoldingProtocol.MAX_BODY_LENGTH) {
        rejectFrame(
            ScaffoldingFrameError.BodyTooLong,
            "frame body length $length exceeds ${ScaffoldingProtocol.MAX_BODY_LENGTH}",
        )
    }
}

/** 响应状态合法性：只接受 0、32..63 与 255。 */
internal fun validateFrameStatus(status: Int) {
    if (!ScaffoldingProtocol.isKnownStatus(status)) {
        rejectFrame(ScaffoldingFrameError.StatusInvalid, "unknown response status: $status")
    }
}

/** 是否符合 `namespace:value`：仅小写字母、数字、下划线，单冒号分隔且两端非空。 */
internal fun isWellFormedFrameType(type: String): Boolean {
    val separator = type.indexOf(NAMESPACE_SEPARATOR)
    if (separator <= 0 || separator == type.lastIndex) return false
    if (type.indexOf(NAMESPACE_SEPARATOR, separator + 1) >= 0) return false
    return type.all { character ->
        isTypeNameCharacter(character) || character == NAMESPACE_SEPARATOR
    }
}

/** 抛出帧异常；返回 [Nothing] 以便在条件分支里直接调用。 */
internal fun rejectFrame(
    error: ScaffoldingFrameError,
    detail: String,
): Nothing = throw ScaffoldingProtocolException(error, detail)

private fun validateFrameTypeLength(type: String) {
    if (type.isEmpty()) rejectFrame(ScaffoldingFrameError.EmptyType, "frame type is empty")
    if (type.length > ScaffoldingProtocol.MAX_TYPE_LENGTH) {
        rejectFrame(
            ScaffoldingFrameError.TypeTooLong,
            "frame type length ${type.length} exceeds ${ScaffoldingProtocol.MAX_TYPE_LENGTH}",
        )
    }
}

private fun validateFrameTypeCharacters(type: String) {
    if (type.any { character -> character.code > ASCII_MAX }) {
        rejectFrame(ScaffoldingFrameError.TypeNotAscii, "frame type contains non-ASCII characters")
    }
    if (!isWellFormedFrameType(type)) {
        rejectFrame(ScaffoldingFrameError.TypeMalformed, "frame type is not namespace:value")
    }
}

private fun isTypeNameCharacter(character: Char): Boolean =
    character in 'a'..'z' || character in '0'..'9' || character == '_'

private const val HASH_PRIME = 31
private const val ASCII_MAX = 127
private const val NAMESPACE_SEPARATOR = ':'
