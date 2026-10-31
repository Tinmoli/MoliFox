// Scaffolding 客户端的连接层：socket 生命周期、请求/响应队列、心跳与响应超时。
package dev.molifox.multiplayer.scaffolding

import dev.molifox.multiplayer.protocol.ResponseFrameDecoder
import dev.molifox.multiplayer.protocol.ScaffoldingFrame
import dev.molifox.multiplayer.protocol.ScaffoldingProtocol
import dev.molifox.multiplayer.protocol.ScaffoldingProtocolException
import dev.molifox.multiplayer.protocol.ScaffoldingResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

internal class ScaffoldingClientSession(
    private val host: String,
    private val port: Int,
    private val player: ScaffoldingPlayer,
    private val heartbeatIntervalMillis: Long,
    private val requestTimeoutMillis: Long,
    private val onDisconnected: (Throwable?) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ScaffoldingPendingResponses()
    private val requestMutex = Mutex()
    private val disconnectNotified = AtomicBoolean(false)

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var closed = false

    suspend fun open(): Result<Unit> =
        withContext(Dispatchers.IO) {
            when {
                closed || socket != null ->
                    Result.failure(IllegalStateException("Scaffolding 客户端已连接或已关闭"))
                else -> connectSocket()
            }
        }

    suspend fun request(
        type: String,
        body: ByteArray,
    ): Result<ScaffoldingResponse> =
        withContext(Dispatchers.IO) {
            requestMutex.withLock {
                val target = socket
                when {
                    closed || target == null ->
                        Result.failure(IllegalStateException("Scaffolding 客户端未连接或已关闭"))
                    else -> sendLocked(target, type, body)
                }
            }
        }

    fun close() {
        markDisconnected(IOException("Scaffolding 客户端已关闭"))
        scope.cancel()
    }

    private fun connectSocket(): Result<Unit> {
        val connected = Socket()
        return try {
            val timeoutMillis =
                requestTimeoutMillis
                    .coerceIn(
                        1L,
                        MAX_CONNECT_TIMEOUT_MILLIS,
                    ).toInt()
            connected.connect(InetSocketAddress(host, port), timeoutMillis)
            socket = connected
            scope.launch { readLoop(connected) }
            scope.launch { heartbeatLoop() }
            Result.success(Unit)
        } catch (failure: IOException) {
            closeSocketQuietly(connected, onError)
            Result.failure(failure)
        }
    }

    private suspend fun sendLocked(
        target: Socket,
        type: String,
        body: ByteArray,
    ): Result<ScaffoldingResponse> {
        val waiter = CompletableDeferred<ScaffoldingResponse>()
        pending.add(waiter)
        return try {
            target.getOutputStream().apply {
                write(ScaffoldingFrame.encodeRequest(type, body))
                flush()
            }
            Result.success(withTimeout(requestTimeoutMillis) { waiter.await() })
        } catch (failure: TimeoutCancellationException) {
            markDisconnected(IOException("Scaffolding 请求超时：$type", failure))
            Result.failure(IOException("Scaffolding 请求超时：$type", failure))
        } catch (failure: IOException) {
            markDisconnected(failure)
            Result.failure(failure)
        } finally {
            pending.remove(waiter)
        }
    }

    private fun readLoop(source: Socket) {
        try {
            val decoder = ResponseFrameDecoder()
            readFrames(source, decoder, ByteArray(READ_BUFFER_BYTES))
        } catch (failure: ScaffoldingProtocolException) {
            handleDisconnect(failure)
        } catch (failure: IOException) {
            if (!closed) {
                handleDisconnect(failure)
            }
        }
    }

    private fun readFrames(
        source: Socket,
        decoder: ResponseFrameDecoder,
        buffer: ByteArray,
    ) {
        val input = source.getInputStream()
        while (!closed) {
            val read = input.read(buffer)
            if (read < 0) {
                handleDisconnect(null)
                return
            }
            if (read == 0) {
                continue
            }
            for (response in decoder.feed(buffer.copyOf(read))) {
                if (!pending.completeNext(response)) {
                    onError(
                        IllegalStateException("收到没有请求等待的响应：状态 ${response.status}"),
                    )
                }
            }
        }
    }

    private suspend fun heartbeatLoop() {
        while (!closed) {
            delay(heartbeatIntervalMillis)
            if (!closed) {
                val result = request(ScaffoldingProtocol.PLAYER_PING, encodePlayerPing(player))
                val response = result.getOrNull()
                if (response?.status != ScaffoldingProtocol.STATUS_OK) {
                    handleDisconnect(result.exceptionOrNull() ?: IOException("player_ping 心跳失败"))
                    return
                }
            }
        }
    }

    private fun handleDisconnect(cause: Throwable?) {
        markDisconnected(cause ?: IOException("Scaffolding 连接已关闭"))
        scope.cancel()
        if (disconnectNotified.compareAndSet(false, true)) {
            onDisconnected(cause)
        }
    }

    private fun markDisconnected(cause: Throwable) {
        if (closed) {
            return
        }
        closed = true
        socket?.let { current -> closeSocketQuietly(current, onError) }
        socket = null
        pending.failAll(cause)
    }
}

private fun closeSocketQuietly(
    socket: Socket,
    onError: (Throwable) -> Unit,
) {
    try {
        socket.close()
    } catch (failure: IOException) {
        onError(failure)
    }
}

private const val READ_BUFFER_BYTES = 4096
private const val MAX_CONNECT_TIMEOUT_MILLIS = 10_000L
