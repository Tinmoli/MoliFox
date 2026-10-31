// 联机协议测试的共享工具：回环连接、可控超时轮询与测试玩家构造。
package dev.molifox.multiplayer.scaffolding

import dev.molifox.multiplayer.protocol.ResponseFrameDecoder
import dev.molifox.multiplayer.protocol.ScaffoldingFrame
import dev.molifox.multiplayer.protocol.ScaffoldingResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

internal const val TEST_TIMEOUT_MILLIS = 5_000L
internal const val TEST_POLL_MILLIS = 10L
internal const val TEST_HOST = "127.0.0.1"

internal fun testPlayer(
    machineId: String,
    name: String,
    vendor: String = "MoliFox",
): ScaffoldingPlayer = ScaffoldingPlayer(machineId = machineId, name = name, vendor = vendor)

internal fun startTestServer(
    playerTimeoutMillis: Long = DEFAULT_PLAYER_TIMEOUT_MILLIS,
    cleanupIntervalMillis: Long = DEFAULT_CLEANUP_INTERVAL_MILLIS,
    hostProfile: ScaffoldingPlayer? = null,
    clock: () -> Long = { System.currentTimeMillis() },
    onError: (Throwable) -> Unit = {},
): Pair<ScaffoldingServer, Int> {
    val server =
        ScaffoldingServer(
            requestedPort = 0,
            playerTimeoutMillis = playerTimeoutMillis,
            cleanupIntervalMillis = cleanupIntervalMillis,
            hostProfile = hostProfile,
            clock = clock,
            onError = onError,
        )
    return server to server.start().getOrThrow()
}

internal suspend fun awaitTrue(
    timeoutMillis: Long = TEST_TIMEOUT_MILLIS,
    condition: () -> Boolean,
) {
    withTimeout(timeoutMillis) {
        while (!condition()) {
            delay(TEST_POLL_MILLIS)
        }
    }
}

internal class RawScaffoldingConnection(
    port: Int,
) : Closeable {
    private val socket = Socket()
    private val decoder = ResponseFrameDecoder()
    private val buffer = ByteArray(READ_BUFFER_BYTES)
    private val received = ArrayDeque<ScaffoldingResponse>()

    init {
        socket.connect(InetSocketAddress(TEST_HOST, port), TEST_TIMEOUT_MILLIS.toInt())
        socket.soTimeout = TEST_TIMEOUT_MILLIS.toInt()
    }

    fun send(
        type: String,
        body: ByteArray = ByteArray(0),
    ) {
        socket.getOutputStream().apply {
            write(ScaffoldingFrame.encodeRequest(type, body))
            flush()
        }
    }

    fun receive(): ScaffoldingResponse {
        while (received.isEmpty()) {
            val read = socket.getInputStream().read(buffer)
            if (read < 0) {
                throw IOException("服务端提前关闭连接")
            }
            received.addAll(decoder.feed(buffer.copyOf(read)))
        }
        return received.removeFirst()
    }

    fun request(
        type: String,
        body: ByteArray = ByteArray(0),
    ): ScaffoldingResponse {
        send(type, body)
        return receive()
    }

    fun awaitClosed() {
        val read = socket.getInputStream().read(buffer)
        if (read >= 0) {
            throw IOException("服务端未关闭连接，仍收到 $read 字节")
        }
    }

    override fun close() {
        socket.close()
    }

    private companion object {
        private const val READ_BUFFER_BYTES = 4096
    }
}
