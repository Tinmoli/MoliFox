// JVM Scaffolding 服务端：监听回环地址、解码请求帧并并发服务多条连接。
package dev.molifox.multiplayer.scaffolding

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
class ScaffoldingServer(
    private val requestedPort: Int = 0,
    private val playerTimeoutMillis: Long = DEFAULT_PLAYER_TIMEOUT_MILLIS,
    private val cleanupIntervalMillis: Long = DEFAULT_CLEANUP_INTERVAL_MILLIS,
    hostProfile: ScaffoldingPlayer? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onError: (Throwable) -> Unit = { failure -> failure.printStackTrace() },
) : AutoCloseable {
    init {
        require(requestedPort in 0..MAX_PORT) {
            "端口必须在 0..$MAX_PORT 之间，收到 $requestedPort"
        }
        require(playerTimeoutMillis > 0) { "playerTimeoutMillis 必须为正数" }
        require(cleanupIntervalMillis > 0) { "cleanupIntervalMillis 必须为正数" }
    }

    private val mutableMinecraftPort = MutableStateFlow<Int?>(null)
    private val registry = ScaffoldingPlayerRegistry(hostProfile, playerTimeoutMillis, clock)
    private val requestHandler = ScaffoldingRequestHandler(registry) { mutableMinecraftPort.value }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serverSocket = ServerSocket()
    private val stateLock = Any()
    private val openSockets = HashSet<Socket>()

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
                ?: throw IllegalStateException("ScaffoldingServer 尚未启动")

    /** 开始监听并返回实际端口；重复调用或关闭后调用会返回失败。 */
    fun start(): Result<Int> =
        synchronized(stateLock) {
            when {
                closed -> Result.failure(IllegalStateException("ScaffoldingServer 已关闭"))
                boundPort > 0 -> Result.failure(IllegalStateException("ScaffoldingServer 已启动"))
                else -> bindAndLaunch()
            }
        }

    /** 登记或清除房主 MC 端口；端口必须是 1..65535，0 会被拒绝。 */
    fun setMinecraftPort(port: Int?) {
        if (port != null) {
            require(port in MIN_PORT..MAX_PORT) {
                "MC 端口必须在 $MIN_PORT..$MAX_PORT 之间，收到 $port"
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
                val snapshot = openSockets.toList()
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
            val accepted =
                synchronized(stateLock) {
                    if (closed) {
                        false
                    } else {
                        openSockets.add(socket)
                        true
                    }
                }
            if (!accepted) {
                closeQuietly(socket)
            } else {
                scope.launch { serveConnection(socket) }
            }
        }
    }

    private fun serveConnection(socket: Socket) {
        try {
            readRequests(socket)
        } catch (failure: ScaffoldingProtocolException) {
            if (!closed) {
                onError(failure)
            }
        } catch (failure: IOException) {
            if (!closed) {
                onError(failure)
            }
        } finally {
            registry.release(socket)
            synchronized(stateLock) { openSockets.remove(socket) }
            closeQuietly(socket)
        }
    }

    private fun readRequests(socket: Socket) {
        val decoder = RequestFrameDecoder()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        val input = socket.getInputStream()
        while (!closed) {
            val read = input.read(buffer)
            if (read < 0) {
                return
            }
            if (read == 0) {
                continue
            }
            decoder.feed(buffer.copyOf(read)).forEach { request ->
                writeResponse(socket, requestHandler.handle(socket, request))
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

private const val LOOPBACK_ADDRESS = "127.0.0.1"
private const val BACKLOG = 16
private const val READ_BUFFER_BYTES = 4096
private const val MIN_PORT = 1
private const val MAX_PORT = 65_535

internal const val DEFAULT_PLAYER_TIMEOUT_MILLIS = 10_000L
internal const val DEFAULT_CLEANUP_INTERVAL_MILLIS = 1_000L
