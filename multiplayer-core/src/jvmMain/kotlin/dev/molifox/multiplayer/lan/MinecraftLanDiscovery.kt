// JVM 局域网世界发现：监听 Minecraft LAN 广播，对候选端口做 status ping 并发布去重结果。
package dev.molifox.multiplayer.lan

import dev.molifox.core.i18n.I18n
import dev.molifox.multiplayer.common.LanWorld
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * 监听 Minecraft LAN 广播（默认 UDP 4445），把候选端口 ping 成 [LanWorld]。
 *
 * 广播只负责提供端口，世界名与版本一律以回环 status ping 为准；
 * 同一端口只探测一次，ping 失败也不会重试。
 *
 * 组播加入是尽力而为：没有可用 IPv4 组播接口时只记录日志，回环单播注入仍可工作
 * （同一台机器上的 Minecraft 客户端就是向 127.0.0.1:4445 发广播的）。
 *
 * [start]/[stop] 可重复调用且幂等；[stop] 后不再发布新世界，[close] 后不可再 start。
 *
 * @param listenPort 监听端口，0 表示由系统分配（测试用）。
 * @param multicastAddress 要加入的 IPv4 组播地址。
 * @param pinger 候选端口探测实现，测试可注入假实现。
 * @param pingTimeoutMillis 单次 ping 超时。
 * @param reuseAddress 是否允许与其它监听同一端口的程序共存。
 * @param onError 后台错误回调；只记录技术原因，不向用户展示。
 */
class MinecraftLanDiscovery(
    private val listenPort: Int = DEFAULT_LAN_PORT,
    private val multicastAddress: String = DEFAULT_MULTICAST_ADDRESS,
    private val pinger: MinecraftStatusPinger = McStatusPing(),
    private val pingTimeoutMillis: Long = DEFAULT_PING_TIMEOUT_MILLIS,
    private val reuseAddress: Boolean = true,
    private val onError: (Throwable) -> Unit = { failure -> failure.printStackTrace() },
) : AutoCloseable {
    init {
        require(listenPort in 0..MAX_PORT) {
            I18n.t("multiplayer.minecraftLanDiscovery.1", MAX_PORT, listenPort)
        }
        require(pingTimeoutMillis > 0) { I18n.t("multiplayer.minecraftLanDiscovery.2") }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()
    private val mutableWorlds = MutableStateFlow<List<LanWorld>>(emptyList())
    private val probedPorts = HashSet<Int>()
    private var socket: DatagramSocket? = null
    private var sessionJob: Job? = null
    private var boundPortValue: Int? = null

    @Volatile
    private var running = false

    @Volatile
    private var closed = false

    /** 已成功 ping 的世界快照，按发现顺序排列；同一端口只出现一次。 */
    val worlds: StateFlow<List<LanWorld>> = mutableWorlds.asStateFlow()

    /** 实际监听端口；未启动时为 null（[listenPort] 为 0 时由系统分配）。 */
    val boundPort: Int?
        get() = synchronized(stateLock) { boundPortValue }

    /** 开始监听；重复调用返回已绑定端口，绑定失败时返回带可读原因的 [Result]。 */
    fun start(): Result<Int> =
        synchronized(stateLock) {
            when {
                closed -> Result.failure(IllegalStateException(I18n.t("multiplayer.minecraftLanDiscovery.3")))
                running -> Result.success(boundPortValue ?: listenPort)
                else -> bindAndLaunch()
            }
        }

    /** 停止监听与探测并释放 socket；保留最后一次发现结果，重复调用无副作用。 */
    fun stop() {
        releaseSession()
    }

    override fun close() {
        synchronized(stateLock) { closed = true }
        releaseSession()
        scope.cancel()
    }

    private fun bindAndLaunch(): Result<Int> {
        val listener = DatagramSocket(null)
        return try {
            listener.reuseAddress = reuseAddress
            listener.bind(InetSocketAddress(InetAddress.getByName(ANY_ADDRESS), listenPort))
            LanMulticast.join(listener, multicastAddress, listenPort, onError)
            socket = listener
            boundPortValue = listener.localPort
            probedPorts.clear()
            mutableWorlds.value = emptyList()
            val session = SupervisorJob(scope.coroutineContext[Job])
            sessionJob = session
            running = true
            scope.launch(session) { listenLoop(listener) }
            Result.success(listener.localPort)
        } catch (failure: IOException) {
            closeSocket(listener)
            Result.failure(bindFailure(failure))
        }
    }

    private fun bindFailure(failure: IOException): IOException =
        IOException(
            I18n.t("multiplayer.minecraftLanDiscovery.4", listenPort, failure.message),
            failure,
        )

    private fun listenLoop(listener: DatagramSocket) {
        val buffer = ByteArray(RECEIVE_BUFFER_BYTES)
        while (running) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                listener.receive(packet)
            } catch (failure: IOException) {
                if (running) {
                    onError(failure)
                }
                return
            }
            val message = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
            val parsed = parseLanPacket(message) ?: continue
            schedulePing(parsed.port)
        }
    }

    private fun schedulePing(port: Int) {
        val session =
            synchronized(stateLock) {
                if (!running || !probedPorts.add(port)) {
                    return
                }
                sessionJob
            } ?: return
        scope.launch(session) { publishPing(port, session) }
    }

    private fun publishPing(
        port: Int,
        session: Job,
    ) {
        val world =
            try {
                pinger.ping(LOOPBACK_ADDRESS, port, pingTimeoutMillis)
            } catch (expected: Exception) {
                // 注入的 ping 实现不应抛异常；仍然记录并忽略，避免一条坏端口拖垮发现。
                onError(expected)
                null
            } ?: return
        synchronized(stateLock) {
            if (!running || sessionJob !== session) {
                return
            }
            if (mutableWorlds.value.none { existing -> existing.port == world.port }) {
                mutableWorlds.value = mutableWorlds.value + world
            }
        }
    }

    private fun releaseSession() {
        val resources =
            synchronized(stateLock) {
                if (!running) {
                    return
                }
                running = false
                val snapshot = sessionJob to socket
                sessionJob = null
                socket = null
                boundPortValue = null
                snapshot
            }
        resources.first?.cancel()
        resources.second?.let(::closeSocket)
    }

    private fun closeSocket(datagramSocket: DatagramSocket) {
        try {
            datagramSocket.close()
        } catch (failure: IOException) {
            if (!closed) {
                onError(failure)
            }
        }
    }
}

private const val ANY_ADDRESS = "0.0.0.0"
private const val LOOPBACK_ADDRESS = "127.0.0.1"
private const val RECEIVE_BUFFER_BYTES = 2048
