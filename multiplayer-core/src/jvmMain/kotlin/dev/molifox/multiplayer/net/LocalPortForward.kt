// 本机回环 TCP 转发：把 127.0.0.1 上的一个本地端口桥接到另一个本机端口。
package dev.molifox.multiplayer.net

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 127.0.0.1 上的单端口 TCP 转发：监听 [listenPort]（0 由系统分配），每条连接再连到
 * 127.0.0.1:[targetPort]，双向拷贝字节；任一端 EOF/异常即关闭双端，因此不会留下半开
 * 连接。支持多并发连接，[close] 后监听端口可立即重新 bind。
 *
 * 只做 TCP 的原因：房客侧 EasyTier 的端口转发已同时覆盖 TCP/UDP，而 Minecraft 客户端
 * 连接本机地址只使用 TCP；局域网列表里的世界由
 * [dev.molifox.multiplayer.lan.MinecraftLanBroadcast] 用 UDP 单独注入，所以这里不需要
 * 再做一份本机 UDP 转发。
 *
 * @param targetPort 实际提供服务的本机端口（EasyTier 转发出来的本地端口）。
 * @param listenPort 对 Minecraft 客户端暴露的端口，0 表示由系统分配。
 * @param connectTimeoutMillis 连接目标端口的超时。
 * @param onError 后台错误回调，只用于技术日志；单条连接失败不会停止转发。
 */
class LocalPortForward(
    private val targetPort: Int,
    private val listenPort: Int = 0,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val onError: (Throwable) -> Unit = { failure -> failure.printStackTrace() },
) : AutoCloseable {
    init {
        require(targetPort in MIN_PORT..MAX_PORT) {
            I18n.t("multiplayer.minecraftLanBroadcast.2", MIN_PORT, MAX_PORT, targetPort)
        }
        require(listenPort in 0..MAX_PORT) {
            I18n.t("multiplayer.minecraftLanDiscovery.1", MAX_PORT, listenPort)
        }
        require(connectTimeoutMillis > 0) { I18n.t("multiplayer.localPortForward.1") }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()
    private val activeSockets = HashSet<Socket>()
    private var serverSocket: ServerSocket? = null
    private var boundPortValue = 0

    @Volatile
    private var closed = false

    /** 实际监听端口；未启动或启动失败时抛出 [IllegalStateException]。 */
    val port: Int
        get() =
            synchronized(stateLock) {
                boundPortValue.takeIf { it > 0 }
                    ?: throw IllegalStateException(I18n.t("multiplayer.localPortForward.2"))
            }

    /** 开始监听并返回本地端口；重复调用返回同一端口，绑定失败时返回带原因的 [Result]。 */
    fun start(): Result<Int> =
        synchronized(stateLock) {
            when {
                closed -> Result.failure(IllegalStateException(I18n.t("multiplayer.localPortForward.3")))
                boundPortValue > 0 -> Result.success(boundPortValue)
                else -> bindAndLaunch()
            }
        }

    override fun close() {
        val sockets =
            synchronized(stateLock) {
                if (closed) {
                    return
                }
                closed = true
                val snapshot = activeSockets.toList()
                activeSockets.clear()
                snapshot
            }
        serverSocket?.let(::closeQuietly)
        serverSocket = null
        boundPortValue = 0
        sockets.forEach(::closeQuietly)
        scope.cancel()
    }

    private fun bindAndLaunch(): Result<Int> {
        val listener = ServerSocket()
        return try {
            listener.reuseAddress = true
            val address = InetSocketAddress(InetAddress.getByName(LOOPBACK_ADDRESS), listenPort)
            listener.bind(address, BACKLOG)
            serverSocket = listener
            boundPortValue = listener.localPort
            scope.launch { acceptLoop(listener) }
            Result.success(boundPortValue)
        } catch (failure: IOException) {
            closeQuietly(listener)
            Result.failure(
                IOException(
                    I18n.t("multiplayer.localPortForward.4", listenPort, failure.message),
                    failure,
                ),
            )
        }
    }

    private fun acceptLoop(listener: ServerSocket) {
        while (!closed) {
            val client =
                try {
                    listener.accept()
                } catch (failure: IOException) {
                    if (!closed) {
                        onError(failure)
                    }
                    return
                }
            if (!track(client)) {
                closeQuietly(client)
                continue
            }
            scope.launch { serve(client) }
        }
    }

    private suspend fun serve(client: Socket) {
        val target = Socket()
        try {
            target.connect(
                InetSocketAddress(InetAddress.getByName(LOOPBACK_ADDRESS), targetPort),
                connectTimeoutMillis,
            )
            if (!track(target)) {
                return
            }
            coroutineScope {
                val upstream = launch { pump(client, target) }
                val downstream = launch { pump(target, client) }
                joinAll(upstream, downstream)
            }
        } catch (failure: IOException) {
            if (!closed) {
                onError(failure)
            }
        } finally {
            untrack(client)
            untrack(target)
            closeQuietly(client)
            closeQuietly(target)
        }
    }

    private fun pump(
        source: Socket,
        sink: Socket,
    ) {
        try {
            val input = source.getInputStream()
            val output = sink.getOutputStream()
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (failure: IOException) {
            // 主动关闭本端会让对端读到 SocketException，这属于正常收尾，不再上报。
            if (!closed && !source.isClosed) {
                onError(failure)
            }
        } finally {
            // 任一方向结束都关闭双端，解除另一方向可能阻塞的 read。
            closeQuietly(source)
            closeQuietly(sink)
        }
    }

    private fun track(socket: Socket): Boolean =
        synchronized(stateLock) {
            if (closed) {
                false
            } else {
                activeSockets.add(socket)
                true
            }
        }

    private fun untrack(socket: Socket) {
        synchronized(stateLock) { activeSockets.remove(socket) }
    }

    private fun closeQuietly(closeable: Closeable) {
        try {
            closeable.close()
        } catch (failure: IOException) {
            if (!closed) {
                onError(failure)
            }
        }
    }
}

private const val LOOPBACK_ADDRESS = "127.0.0.1"
private const val MIN_PORT = 1
private const val MAX_PORT = 65_535
private const val BACKLOG = 16
private const val BUFFER_BYTES = 8192

internal const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 5_000
