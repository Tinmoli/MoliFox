// JVM Scaffolding 服务端：监听回环地址、解码请求帧并并发服务多条连接。
package dev.molifox.multiplayer.scaffolding

import dev.molifox.core.i18n.I18n
import dev.molifox.multiplayer.protocol.RequestFrameDecoder
import dev.molifox.multiplayer.protocol.ScaffoldingFrame
import dev.molifox.multiplayer.protocol.ScaffoldingProtocolException
import dev.molifox.multiplayer.protocol.ScaffoldingResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Scaffolding 服务端：绑定 `127.0.0.1` 的 [requestedPort]（0 表示由系统分配），按
 * [ScaffoldingFrame] 收发请求/响应。每条连接由独立协程读取并顺序写回响应，
 * 因此同一连接上的帧不会交错。
 *
 * @param playerTimeoutMillis 玩家超过该时长未发送 `c:player_ping` 即被清理。
 * @param cleanupIntervalMillis 清理协程的扫描间隔。
 * @param hostProfile 房主资料；提供后会出现在玩家列表首位，`kind` 为 `HOST`。
 * @param clock 时间源，测试可注入以跳过真实等待。
 * @param onError 后台接受/读取错误回调；调用方记录即可，服务端继续服务其它连接。
 */
class ScaffoldingServer internal constructor(
    private val requestedPort: Int,
    private val playerTimeoutMillis: Long,
    private val cleanupIntervalMillis: Long,
    hostProfile: ScaffoldingPlayer?,
    private val clock: () -> Long,
    private val onError: (Throwable) -> Unit,
    private val connectionLimits: ScaffoldingConnectionLimits,
) : AutoCloseable {
    constructor(
        requestedPort: Int = 0,
        playerTimeoutMillis: Long = DEFAULT_PLAYER_TIMEOUT_MILLIS,
        cleanupIntervalMillis: Long = DEFAULT_CLEANUP_INTERVAL_MILLIS,
        hostProfile: ScaffoldingPlayer? = null,
        clock: () -> Long = System::currentTimeMillis,
        onError: (Throwable) -> Unit = { failure -> failure.printStackTrace() },
    ) : this(
        requestedPort,
        playerTimeoutMillis,
        cleanupIntervalMillis,
        hostProfile,
        clock,
        onError,
        ScaffoldingConnectionLimits(),
    )

    init {
        require(requestedPort in 0..MAX_PORT) {
            I18n.t("multiplayer.scaffoldingServer.1", MAX_PORT, requestedPort)
        }
        require(playerTimeoutMillis > 0) { I18n.t("multiplayer.scaffoldingServer.2") }
        require(cleanupIntervalMillis > 0) { I18n.t("multiplayer.scaffoldingServer.3") }
    }

    private val mutableMinecraftPort = MutableStateFlow<Int?>(null)
    private val registry = ScaffoldingPlayerRegistry(hostProfile, playerTimeoutMillis, clock)
    private val requestHandler = ScaffoldingRequestHandler(registry) { mutableMinecraftPort.value }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serverSocket = ServerSocket()
    private val stateLock = Any()
    private val openSockets = HashMap<Socket, ConnectionDeadline>()

    @Volatile
    private var closed = false

    @Volatile
    private var boundPort = 0

    /** 已登记的房主 MC 端口；null 表示尚未就绪，`c:server_port` 会返回状态 32。 */
    val minecraftPort: StateFlow<Int?> = mutableMinecraftPort.asStateFlow()

    /** 当前玩家列表：房主（若配置）在首位，随后是各连接上报的玩家。 */
    val players: StateFlow<List<ScaffoldingPlayer>> = registry.players

    /** 实际监听端口；未启动或启动失败时抛出 [IllegalStateException]。 */
    val port: Int
        get() =
            boundPort.takeIf { it > 0 }
                ?: throw IllegalStateException(I18n.t("multiplayer.scaffoldingServer.4"))

    /** 开始监听并返回实际端口；重复调用或关闭后调用会返回失败。 */
    fun start(): Result<Int> =
        synchronized(stateLock) {
            when {
                closed -> Result.failure(IllegalStateException(I18n.t("multiplayer.scaffoldingServer.5")))
                boundPort > 0 -> Result.failure(IllegalStateException(I18n.t("multiplayer.scaffoldingServer.6")))
                else -> bindAndLaunch()
            }
        }

    /** 登记或清除房主 MC 端口；端口必须是 1..65535，0 会被拒绝。 */
    fun setMinecraftPort(port: Int?) {
        if (port != null) {
            require(port in MIN_PORT..MAX_PORT) {
                I18n.t("multiplayer.scaffoldingServer.7", MIN_PORT, MAX_PORT, port)
            }
        }
        mutableMinecraftPort.value = port
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        val sockets =
            synchronized(stateLock) {
                val snapshot = openSockets.keys.toList()
                openSockets.clear()
                snapshot
            }
        closeQuietly(serverSocket)
        sockets.forEach(::closeQuietly)
        scope.cancel()
    }

    private fun bindAndLaunch(): Result<Int> =
        try {
            serverSocket.reuseAddress = true
            val address = InetSocketAddress(InetAddress.getByName(LOOPBACK_ADDRESS), requestedPort)
            serverSocket.bind(address, BACKLOG)
            boundPort = serverSocket.localPort
            scope.launch { acceptLoop() }
            scope.launch { cleanupLoop() }
            Result.success(boundPort)
        } catch (failure: IOException) {
            Result.failure(failure)
        }

    private fun acceptLoop() {
        while (!closed) {
            val socket =
                try {
                    serverSocket.accept()
                } catch (failure: IOException) {
                    if (!closed) {
                        onError(failure)
                    }
                    return
                }
            val deadline =
                synchronized(stateLock) {
                    if (closed || openSockets.size >= connectionLimits.connections) {
                        null
                    } else {
                        ConnectionDeadline(connectionLimits.firstFrameMillis).also { openSockets[socket] = it }
                    }
                }
            if (deadline == null) {
                closeQuietly(socket)
            } else {
                scope.launch { serveConnection(socket, deadline) }
            }
        }
    }

    private fun serveConnection(
        socket: Socket,
        deadline: ConnectionDeadline,
    ) {
        try {
            readRequests(socket, deadline)
        } catch (failure: ScaffoldingProtocolException) {
            if (!closed) {
                onError(failure)
            }
        } catch (failure: IOException) {
            if (!closed && failure !is SocketTimeoutException && deadline.remainingNanos() > 0) {
                onError(failure)
            }
        } finally {
            registry.release(socket)
            synchronized(stateLock) { openSockets.remove(socket) }
            closeQuietly(socket)
        }
    }

    private fun readRequests(
        socket: Socket,
        deadline: ConnectionDeadline,
    ) {
        val decoder = RequestFrameDecoder()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        val input = socket.getInputStream()
        while (!closed) {
            val remainingNanos = deadline.remainingNanos()
            if (remainingNanos <= 0) return
            socket.soTimeout = (remainingNanos / NANOS_PER_MILLI).coerceAtLeast(1).toInt()
            val read = input.read(buffer)
            if (read < 0) {
                return
            }
            if (read == 0) {
                continue
            }
            decoder.feed(buffer.copyOf(read)).forEach { request ->
                writeResponse(socket, requestHandler.handle(socket, request))
                deadline.extend(connectionLimits.frameMillis)
            }
        }
    }

    private fun writeResponse(
        socket: Socket,
        response: ScaffoldingResponse,
    ) {
        val output = socket.getOutputStream()
        output.write(ScaffoldingFrame.encodeResponse(response.status, response.body))
        output.flush()
    }

    private suspend fun cleanupLoop() {
        while (!closed) {
            delay(cleanupIntervalMillis)
            registry.sweep(clock())
            val expired =
                synchronized(stateLock) {
                    openSockets.filterValues { it.remainingNanos() <= 0 }.keys.toList()
                }
            expired.forEach(::closeQuietly)
        }
    }

    private fun closeQuietly(closeable: Closeable) {
        try {
            closeable.close()
        } catch (failure: IOException) {
            onError(failure)
        }
    }
}

/** 同一截止时间也由清理循环检查，读取或写回阻塞时都能关闭连接。 */
private class ConnectionDeadline(
    timeoutMillis: Int,
) {
    @Volatile private var deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI

    fun remainingNanos(): Long = deadline - System.nanoTime()

    fun extend(timeoutMillis: Int) {
        deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
    }
}

internal class ScaffoldingConnectionLimits(
    val connections: Int = DEFAULT_CONNECTION_LIMIT,
    val firstFrameMillis: Int = DEFAULT_FIRST_FRAME_TIMEOUT_MILLIS,
    val frameMillis: Int = DEFAULT_FRAME_TIMEOUT_MILLIS,
) {
    init {
        require(connections > 0 && firstFrameMillis > 0 && frameMillis > 0)
    }
}

private const val LOOPBACK_ADDRESS = "127.0.0.1"
private const val BACKLOG = 16
private const val DEFAULT_CONNECTION_LIMIT = 64
private const val DEFAULT_FIRST_FRAME_TIMEOUT_MILLIS = 15_000
private const val DEFAULT_FRAME_TIMEOUT_MILLIS = 30_000
private const val NANOS_PER_MILLI = 1_000_000L
private const val READ_BUFFER_BYTES = 4096
private const val MIN_PORT = 1
private const val MAX_PORT = 65_535

internal const val DEFAULT_PLAYER_TIMEOUT_MILLIS = 10_000L
internal const val DEFAULT_CLEANUP_INTERVAL_MILLIS = 1_000L
