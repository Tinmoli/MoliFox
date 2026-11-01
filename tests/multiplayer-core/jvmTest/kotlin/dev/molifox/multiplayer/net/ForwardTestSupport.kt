// 本机转发测试用的回环 TCP echo 服务器：并发接收连接并原样回显。
package dev.molifox.multiplayer.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

internal const val FORWARD_TEST_HOST = "127.0.0.1"
internal const val FORWARD_TEST_TIMEOUT_MILLIS = 3_000L

internal class LoopbackEchoServer : AutoCloseable {
    private val serverSocket =
        ServerSocket(0, TEST_BACKLOG, InetAddress.getByName(FORWARD_TEST_HOST))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionCount = AtomicInteger(0)

    val port: Int
        get() = serverSocket.localPort

    /** 已经 accept 过的连接数，用于确认并发连接确实到达了目标端口。 */
    val acceptedConnections: Int
        get() = connectionCount.get()

    init {
        scope.launch { acceptLoop() }
    }

    override fun close() {
        try {
            serverSocket.close()
        } catch (_: IOException) {
            // 关闭监听 socket 失败不影响测试结论。
        }
        scope.cancel()
    }

    private fun acceptLoop() {
        while (true) {
            val client =
                try {
                    serverSocket.accept()
                } catch (_: IOException) {
                    return
                }
            connectionCount.incrementAndGet()
            scope.launch { echo(client) }
        }
    }

    private fun echo(client: Socket) {
        try {
            client.use { socket -> copyBack(socket) }
        } catch (_: IOException) {
            // 转发 close 或客户端断开导致的读写异常是预期收尾，不算失败。
        }
    }

    private fun copyBack(socket: Socket) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val buffer = ByteArray(ECHO_BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            output.write(buffer, 0, read)
            output.flush()
        }
    }
}

/** 打开一条到 [port] 的测试连接，发送 [payload] 并读回等长响应。 */
internal fun exchange(
    port: Int,
    payload: String,
): String {
    Socket().use { socket ->
        connectTestSocket(socket, port)
        socket.getOutputStream().apply {
            write(payload.encodeToByteArray())
            flush()
        }
        return readExactly(socket, payload.encodeToByteArray().size).decodeToString()
    }
}

internal fun connectTestSocket(
    socket: Socket,
    port: Int,
) {
    socket.connect(InetSocketAddress(FORWARD_TEST_HOST, port), FORWARD_TEST_TIMEOUT_MILLIS.toInt())
    socket.soTimeout = FORWARD_TEST_TIMEOUT_MILLIS.toInt()
}

internal fun readExactly(
    socket: Socket,
    length: Int,
): ByteArray {
    val received = ByteArray(length)
    DataInputStream(socket.getInputStream()).readFully(received)
    return received
}

private const val TEST_BACKLOG = 8
private const val ECHO_BUFFER_BYTES = 4096
