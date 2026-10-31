// status ping 测试用的单次假 Minecraft 状态服务器：读两帧请求后回一帧 JSON。
package dev.molifox.multiplayer.lan

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

internal class FakeStatusServer(
    private val responseJson: String,
) : AutoCloseable {
    private val serverSocket = ServerSocket(0, TEST_BACKLOG, InetAddress.getByName(TEST_HOST))
    private val receivedFrames = CopyOnWriteArrayList<ByteArray>()
    private val failure = AtomicReference<Throwable?>(null)
    private val worker = thread(isDaemon = true, name = "fake-mc-status") { serveOne() }

    val port: Int
        get() = serverSocket.localPort

    /** 客户端实际发来的原始帧载荷，按顺序；用于断言 handshake 的字段。 */
    val frames: List<ByteArray>
        get() = receivedFrames.toList()

    override fun close() {
        serverSocket.close()
        worker.join(TEST_JOIN_TIMEOUT_MILLIS)
        failure.get()?.let { cause -> throw AssertionError("假状态服务器处理失败", cause) }
    }

    private fun serveOne() {
        try {
            serverSocket.accept().use { socket ->
                val input = DataInputStream(socket.getInputStream())
                repeat(EXPECTED_REQUEST_FRAMES) {
                    val length = readVarInt(input) ?: return
                    val payload = ByteArray(length)
                    input.readFully(payload)
                    receivedFrames += payload
                }
                writeResponse(socket.getOutputStream())
            }
        } catch (failure: IOException) {
            if (!serverSocket.isClosed) {
                this.failure.set(failure)
            }
        }
    }

    private fun writeResponse(output: OutputStream) {
        val json = responseJson.encodeToByteArray()
        val payload = ByteArrayOutputStream()
        writeVarInt(payload, RESPONSE_PACKET_ID)
        writeVarInt(payload, json.size)
        payload.write(json)
        val body = payload.toByteArray()
        val frame = ByteArrayOutputStream()
        writeVarInt(frame, body.size)
        frame.write(body)
        output.write(frame.toByteArray())
        output.flush()
    }
}

private const val RESPONSE_PACKET_ID = 0
private const val EXPECTED_REQUEST_FRAMES = 2
private const val TEST_BACKLOG = 4
private const val TEST_JOIN_TIMEOUT_MILLIS = 2_000L
