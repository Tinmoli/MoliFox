// 校验 Scaffolding 帧编解码：字节布局、半包/粘包、尺寸上限与非法帧拒绝。
package dev.molifox.multiplayer.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScaffoldingFrameTest {
    @Test
    fun `encodes request frame in protocol byte order`() {
        val encoded = ScaffoldingFrame.encodeRequest("c:ping", byteArrayOf(1, 2, 3))
        val expected = byteArrayOf(6, 99, 58, 112, 105, 110, 103, 0, 0, 0, 3, 1, 2, 3)
        assertContentEquals(expected, encoded)
    }

    @Test
    fun `encodes response frame in protocol byte order`() {
        val encoded = ScaffoldingFrame.encodeResponse(0, "hi".encodeToByteArray())
        assertContentEquals(byteArrayOf(0, 0, 0, 0, 2, 104, 105), encoded)
    }

    @Test
    fun `round trips request and response models`() {
        val requestFrames =
            RequestFrameDecoder().feed(ScaffoldingFrame.encodeRequest("c:server_port"))
        assertEquals(ScaffoldingRequest("c:server_port", ByteArray(0)), requestFrames.single())
        val body = "unknown".encodeToByteArray()
        val responseFrames = ResponseFrameDecoder().feed(ScaffoldingFrame.encodeResponse(255, body))
        assertEquals(ScaffoldingResponse(255, body), responseFrames.single())
    }

    @Test
    fun `buffers incomplete frames until last byte arrives`() {
        val encoded = ScaffoldingFrame.encodeRequest("c:ping", byteArrayOf(7, 8))
        val decoder = RequestFrameDecoder()
        for (index in 0 until encoded.lastIndex) {
            assertTrue(decoder.feed(byteArrayOf(encoded[index])).isEmpty())
        }
        assertEquals(encoded.size - 1, decoder.pendingBytes)
        val frames = decoder.feed(byteArrayOf(encoded.last()))
        assertContentEquals(byteArrayOf(7, 8), frames.single().body)
        assertEquals(0, decoder.pendingBytes)
    }

    @Test
    fun `parses multiple frames from a single chunk`() {
        val ping = ScaffoldingFrame.encodeRequest("c:ping")
        val protocols = ScaffoldingFrame.encodeRequest("c:protocols", byteArrayOf(65))
        val frames = RequestFrameDecoder().feed(ping + protocols + ping)
        assertEquals(listOf("c:ping", "c:protocols", "c:ping"), frames.map { it.type })
    }

    @Test
    fun `keeps partial trailing frame buffered after complete ones`() {
        val ping = ScaffoldingFrame.encodeRequest("c:ping")
        val protocols = ScaffoldingFrame.encodeRequest("c:protocols", byteArrayOf(65))
        val decoder = RequestFrameDecoder()
        val first = decoder.feed(ping + protocols.copyOfRange(0, SPLIT_BYTES))
        assertEquals(1, first.size)
        assertTrue(decoder.pendingBytes > 0)
        val second = decoder.feed(protocols.copyOfRange(SPLIT_BYTES, protocols.size))
        assertEquals(1, second.size)
        assertEquals(0, decoder.pendingBytes)
    }

    @Test
    fun `parses frames at every two chunk split`() {
        val payload =
            ScaffoldingFrame.encodeRequest("c:player_ping", "{}".encodeToByteArray()) +
                ScaffoldingFrame.encodeRequest("c:server_port")
        for (split in 0..payload.size) {
            val decoder = RequestFrameDecoder()
            val first = decoder.feed(payload.copyOfRange(0, split))
            val second = decoder.feed(payload.copyOfRange(split, payload.size))
            assertEquals(2, first.size + second.size)
        }
    }

    @Test
    fun `rejects invalid request frame headers`() {
        assertFrameError(
            ScaffoldingFrameError.EmptyType,
        ) { RequestFrameDecoder().feed(byteArrayOf(0)) }
        assertFrameError(ScaffoldingFrameError.TypeTooLong) {
            RequestFrameDecoder().feed(byteArrayOf(129.toByte()))
        }
        assertFrameError(ScaffoldingFrameError.TypeNotAscii) {
            RequestFrameDecoder().feed(rawRequest(byteArrayOf(0xC3.toByte()), 0))
        }
        for (type in listOf("c", "C:ping", "c:x:y")) {
            assertFrameError(ScaffoldingFrameError.TypeMalformed) {
                RequestFrameDecoder().feed(rawRequest(type.encodeToByteArray(), 0))
            }
        }
        assertFrameError(ScaffoldingFrameError.BodyTooLong) {
            RequestFrameDecoder().feed(rawRequest("c:ping".encodeToByteArray(), OVERSIZE_LENGTH))
        }
    }

    @Test
    fun `rejects invalid response statuses and lengths`() {
        for (status in listOf(1, 31, 64, 254)) {
            assertFrameError(ScaffoldingFrameError.StatusInvalid) {
                ResponseFrameDecoder().feed(byteArrayOf(status.toByte()))
            }
        }
        assertFrameError(ScaffoldingFrameError.BodyTooLong) {
            ResponseFrameDecoder().feed(rawResponse(0, OVERSIZE_LENGTH))
        }
    }

    @Test
    fun `accepts documented response statuses`() {
        for (status in listOf(0, 32, 55, 63, 255)) {
            val frames = ResponseFrameDecoder().feed(ScaffoldingFrame.encodeResponse(status))
            assertEquals(status, frames.single().status)
        }
    }

    @Test
    fun `keeps unknown error body bytes intact`() {
        val body = "未知错误".encodeToByteArray()
        val frames = ResponseFrameDecoder().feed(ScaffoldingFrame.encodeResponse(255, body))
        assertEquals(255, frames.single().status)
        assertContentEquals(body, frames.single().body)
    }

    @Test
    fun `accepts maximum type and body lengths`() {
        val type = "c:" + "a".repeat(ScaffoldingProtocol.MAX_TYPE_LENGTH - 2)
        val body = ByteArray(ScaffoldingProtocol.MAX_BODY_LENGTH) { index -> index.toByte() }
        val frames = RequestFrameDecoder().feed(ScaffoldingFrame.encodeRequest(type, body))
        assertEquals(type, frames.single().type)
        assertContentEquals(body, frames.single().body)
    }

    @Test
    fun `accepts maximum response body`() {
        val body = ByteArray(ScaffoldingProtocol.MAX_BODY_LENGTH)
        val frames = ResponseFrameDecoder().feed(ScaffoldingFrame.encodeResponse(0, body))
        assertContentEquals(body, frames.single().body)
    }

    @Test
    fun `rejects invalid encode input`() {
        assertFrameError(ScaffoldingFrameError.EmptyType) { ScaffoldingFrame.encodeRequest("") }
        assertFrameError(ScaffoldingFrameError.TypeTooLong) {
            ScaffoldingFrame.encodeRequest("c:" + "a".repeat(127))
        }
        assertFrameError(ScaffoldingFrameError.TypeNotAscii) {
            ScaffoldingFrame.encodeRequest("c:协议")
        }
        for (type in listOf("c", "c:", ":ping", "c:x:y", "C:ping", "c:ping!")) {
            assertFrameError(ScaffoldingFrameError.TypeMalformed) {
                ScaffoldingFrame.encodeRequest(type)
            }
        }
    }

    @Test
    fun `rejects oversized body and status on encode`() {
        assertFrameError(ScaffoldingFrameError.BodyTooLong) {
            ScaffoldingFrame.encodeRequest("c:ping", ByteArray(OVERSIZE_LENGTH))
        }
        assertFrameError(ScaffoldingFrameError.BodyTooLong) {
            ScaffoldingFrame.encodeResponse(0, ByteArray(OVERSIZE_LENGTH))
        }
        for (status in listOf(1, 31, 64, 254, 256)) {
            assertFrameError(ScaffoldingFrameError.StatusInvalid) {
                ScaffoldingFrame.encodeResponse(status)
            }
        }
    }

    @Test
    fun `reset drops buffered bytes`() {
        val decoder = RequestFrameDecoder()
        decoder.feed(ScaffoldingFrame.encodeRequest("c:ping").copyOfRange(0, SPLIT_BYTES))
        assertTrue(decoder.pendingBytes > 0)
        decoder.reset()
        assertEquals(0, decoder.pendingBytes)
    }
}

private fun assertFrameError(
    expected: ScaffoldingFrameError,
    action: () -> Unit,
) {
    val failure = assertFailsWith<ScaffoldingProtocolException> { action() }
    assertEquals(expected, failure.error)
}

private fun rawRequest(
    typeBytes: ByteArray,
    bodyLength: Int,
): ByteArray {
    val output = ByteArray(REQUEST_HEADER_BYTES + typeBytes.size)
    output[0] = typeBytes.size.toByte()
    typeBytes.copyInto(output, 1)
    writeLength(output, 1 + typeBytes.size, bodyLength)
    return output
}

private fun rawResponse(
    status: Int,
    bodyLength: Int,
): ByteArray {
    val output = ByteArray(RESPONSE_HEADER_BYTES)
    output[0] = status.toByte()
    writeLength(output, 1, bodyLength)
    return output
}

private fun writeLength(
    target: ByteArray,
    offset: Int,
    value: Int,
) {
    for (index in 0 until INT_BYTES) {
        val shift = BYTE_BITS * (INT_BYTES - 1 - index)
        target[offset + index] = (value ushr shift).toByte()
    }
}

private const val BYTE_BITS = 8
private const val INT_BYTES = 4
private const val REQUEST_HEADER_BYTES = 5
private const val RESPONSE_HEADER_BYTES = 5
private const val SPLIT_BYTES = 3
private const val OVERSIZE_LENGTH = 65_537
