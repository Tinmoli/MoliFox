// Scaffolding 帧增量解析：支持半包、粘包与多帧同连接，超长或非法帧立即拒绝。
package dev.molifox.multiplayer.protocol

/** 请求帧增量解析器；每个 TCP 连接持有一个实例。 */
class RequestFrameDecoder {
    private val buffer = ByteAccumulator()

    /** 尚未组成完整帧的缓存字节数，便于诊断半包。 */
    val pendingBytes: Int
        get() = buffer.size

    /** 追加一段网络数据，返回本次可读出的全部完整请求帧。 */
    fun feed(chunk: ByteArray): List<ScaffoldingRequest> {
        buffer.append(chunk)
        val frames = mutableListOf<ScaffoldingRequest>()
        while (buffer.size > 0) {
            val frame = readFrame() ?: break
            frames += frame
        }
        return frames
    }

    /** 丢弃缓存；连接关闭或出错后调用。 */
    fun reset() {
        buffer.clear()
    }

    /** 读出一帧；数据不足返回 null，非法帧抛出 [ScaffoldingProtocolException]。 */
    private fun readFrame(): ScaffoldingRequest? {
        val typeLength = buffer.peekOrNull(0) ?: return null
        rejectInvalidTypeLength(typeLength)
        return readCompleteFrame(typeLength)
    }

    private fun readCompleteFrame(typeLength: Int): ScaffoldingRequest? {
        val headerLength = REQUEST_FIXED_HEADER_BYTES + typeLength
        if (buffer.size < headerLength) return null
        val type = buffer.asciiString(REQUEST_TYPE_LENGTH_BYTES, typeLength)
        validateFrameType(type)
        val bodyLength = buffer.unsignedIntAt(REQUEST_TYPE_LENGTH_BYTES + typeLength)
        validateFrameBodyLength(bodyLength)
        return consumeRequest(type, headerLength, bodyLength)
    }

    private fun consumeRequest(
        type: String,
        headerLength: Int,
        bodyLength: Int,
    ): ScaffoldingRequest? {
        if (buffer.size < headerLength + bodyLength) return null
        buffer.take(headerLength)
        return ScaffoldingRequest(type, buffer.take(bodyLength))
    }
}

/** 响应帧增量解析器；每个 TCP 连接持有一个实例。 */
class ResponseFrameDecoder {
    private val buffer = ByteAccumulator()

    /** 尚未组成完整帧的缓存字节数，便于诊断半包。 */
    val pendingBytes: Int
        get() = buffer.size

    /** 追加一段网络数据，返回本次可读出的全部完整响应帧。 */
    fun feed(chunk: ByteArray): List<ScaffoldingResponse> {
        buffer.append(chunk)
        val frames = mutableListOf<ScaffoldingResponse>()
        while (buffer.size > 0) {
            val frame = readFrame() ?: break
            frames += frame
        }
        return frames
    }

    /** 丢弃缓存；连接关闭或出错后调用。 */
    fun reset() {
        buffer.clear()
    }

    /** 读出一帧；数据不足返回 null，非法帧抛出 [ScaffoldingProtocolException]。 */
    private fun readFrame(): ScaffoldingResponse? {
        val status = buffer.peekOrNull(0) ?: return null
        validateFrameStatus(status)
        return readCompleteFrame(status)
    }

    private fun readCompleteFrame(status: Int): ScaffoldingResponse? {
        if (buffer.size < RESPONSE_HEADER_BYTES) return null
        val bodyLength = buffer.unsignedIntAt(RESPONSE_STATUS_BYTES)
        validateFrameBodyLength(bodyLength)
        return consumeResponse(status, bodyLength)
    }

    private fun consumeResponse(
        status: Int,
        bodyLength: Int,
    ): ScaffoldingResponse? {
        if (buffer.size < RESPONSE_HEADER_BYTES + bodyLength) return null
        buffer.take(RESPONSE_HEADER_BYTES)
        return ScaffoldingResponse(status, buffer.take(bodyLength))
    }
}

/** 请求类型长度必须在 1..MAX_TYPE_LENGTH，否则在读到首字节时立即拒绝。 */
private fun rejectInvalidTypeLength(typeLength: Int) {
    if (typeLength == 0) {
        rejectFrame(ScaffoldingFrameError.EmptyType, "frame type is empty")
    }
    if (typeLength > ScaffoldingProtocol.MAX_TYPE_LENGTH) {
        rejectFrame(
            ScaffoldingFrameError.TypeTooLong,
            "frame type length $typeLength is too long",
        )
    }
}

/** 字节缓存：支持追加、窥视、按帧消费，消费后压缩以限制内存占用。 */
private class ByteAccumulator {
    private var data = ByteArray(INITIAL_CAPACITY)
    private var start = 0
    private var end = 0

    val size: Int
        get() = end - start

    fun append(chunk: ByteArray) {
        val required = end + chunk.size
        if (required > data.size) grow(required)
        chunk.copyInto(data, end)
        end += chunk.size
    }

    fun peekOrNull(index: Int): Int? =
        if (index >= size) null else data[start + index].toInt() and BYTE_MASK

    fun unsignedByteAt(index: Int): Int = data[start + index].toInt() and BYTE_MASK

    fun asciiString(
        offset: Int,
        length: Int,
    ): String {
        val builder = StringBuilder(length)
        for (index in 0 until length) builder.append(unsignedByteAt(offset + index).toChar())
        return builder.toString()
    }

    fun unsignedIntAt(offset: Int): Int {
        var value = 0
        for (index in 0 until INT_BYTES) {
            value = (value shl BYTE_BITS) or unsignedByteAt(offset + index)
        }
        return value
    }

    fun take(count: Int): ByteArray {
        val result = data.copyOfRange(start, start + count)
        start += count
        compact()
        return result
    }

    fun clear() {
        start = 0
        end = 0
    }

    private fun grow(required: Int) {
        var capacity = data.size
        while (capacity < required) capacity *= GROWTH_FACTOR
        data = data.copyOf(capacity)
    }

    private fun compact() {
        if (start == end) {
            start = 0
            end = 0
        } else if (start >= data.size / COMPACT_DIVISOR) {
            data.copyInto(data, 0, start, end)
            end -= start
            start = 0
        }
    }
}

private const val INITIAL_CAPACITY = 256
private const val GROWTH_FACTOR = 2
private const val COMPACT_DIVISOR = 2
private const val BYTE_BITS = 8
private const val BYTE_MASK = 0xFF
private const val INT_BYTES = 4
private const val REQUEST_TYPE_LENGTH_BYTES = 1
private const val RESPONSE_STATUS_BYTES = 1
private const val LENGTH_FIELD_BYTES = 4
private const val REQUEST_FIXED_HEADER_BYTES = REQUEST_TYPE_LENGTH_BYTES + LENGTH_FIELD_BYTES
private const val RESPONSE_HEADER_BYTES = RESPONSE_STATUS_BYTES + LENGTH_FIELD_BYTES
