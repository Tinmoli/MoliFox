// JVM 局域网广播注入：定时把本地转发端口伪装成 Minecraft LAN 世界发给本机客户端。
package dev.molifox.multiplayer.lan

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket

/**
 * 向本机 Minecraft 客户端广播一个“局域网世界”，让它出现在多人游戏列表里。
 *
 * 每 [intervalMillis] 毫秒发送一次 `[MOTD]<description>[/MOTD][AD]<localPort>[/AD]`：
 * 先无条件发到 127.0.0.1:[targetPort]（Minecraft 就是在这里收注入广播），再尽力向
 * 每个本机 IPv4 接口的组播地址发送；组播失败只记录日志，不影响回环发送。
 *
 * [start]/[stop] 幂等；[stop] 返回后不会再发出新报文，[close] 后不可再 start。
 *
 * @param description 显示在多人游戏列表里的世界名。
 * @param localPort 对 Minecraft 客户端可见的本地转发端口。
 * @param targetPort Minecraft 客户端监听的 LAN 广播端口，测试时指向临时端口。
 * @param intervalMillis 重复广播间隔。
 * @param multicastEnabled 是否额外做组播发送；离线测试应关闭。
 * @param onError 后台发送错误回调，只用于技术日志。
 */
class MinecraftLanBroadcast(
    private val description: String,
    private val localPort: Int,
    private val targetPort: Int = DEFAULT_LAN_PORT,
    private val intervalMillis: Long = DEFAULT_BROADCAST_INTERVAL_MILLIS,
    private val multicastEnabled: Boolean = true,
    private val onError: (Throwable) -> Unit = { failure -> failure.printStackTrace() },
) : AutoCloseable {
    init {
        require(localPort in MIN_PORT..MAX_PORT) {
            "localPort 必须在 $MIN_PORT..$MAX_PORT 之间，收到 $localPort"
        }
        require(targetPort in MIN_PORT..MAX_PORT) {
            "targetPort 必须在 $MIN_PORT..$MAX_PORT 之间，收到 $targetPort"
        }
        require(intervalMillis > 0) { "intervalMillis 必须为正数" }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()
    private var unicastSocket: DatagramSocket? = null
    private var multicastSocket: MulticastSocket? = null
    private var multicastTarget: InetSocketAddress? = null
    private var loopbackTarget: InetSocketAddress? = null
    private var sendJob: Job? = null

    @Volatile
    private var running = false

    @Volatile
    private var closed = false

    /** 开始定时广播；重复调用无副作用，socket 创建失败时返回带原因的 [Result]。 */
    fun start(): Result<Unit> =
        synchronized(stateLock) {
            when {
                closed -> Result.failure(IllegalStateException("LAN 广播已关闭"))
                running -> Result.success(Unit)
                else -> openAndLaunch()
            }
        }

    /** 停止广播并释放发送 socket；重复调用无副作用。 */
    fun stop() {
        val stopped =
            synchronized(stateLock) {
                if (!running) {
                    return
                }
                running = false
                val snapshot = Triple(sendJob, unicastSocket, multicastSocket)
                sendJob = null
                unicastSocket = null
                multicastSocket = null
                multicastTarget = null
                loopbackTarget = null
                snapshot
            }
        stopped.first?.cancel()
        stopped.second?.close()
        stopped.third?.close()
    }

    override fun close() {
        synchronized(stateLock) { closed = true }
        stop()
        scope.cancel()
    }

    private fun openAndLaunch(): Result<Unit> =
        try {
            val openedUnicast = DatagramSocket()
            val openedMulticast = openMulticastSocket()
            unicastSocket = openedUnicast
            multicastSocket = openedMulticast
            loopbackTarget = InetSocketAddress(InetAddress.getByName(LOOPBACK_ADDRESS), targetPort)
            running = true
            sendJob = scope.launch { sendLoop() }
            Result.success(Unit)
        } catch (failure: IOException) {
            running = false
            closeSockets()
            Result.failure(IOException("LAN 广播发送 socket 创建失败: ${failure.message}", failure))
        }

    private fun openMulticastSocket(): MulticastSocket? {
        if (!multicastEnabled) {
            return null
        }
        return try {
            val group = InetAddress.getByName(DEFAULT_MULTICAST_ADDRESS)
            multicastTarget = InetSocketAddress(group, targetPort)
            MulticastSocket(0).apply { timeToLive = MULTICAST_TIME_TO_LIVE }
        } catch (failure: IOException) {
            onError(IOException("组播广播不可用，仅向回环发送 LAN 广播: ${failure.message}", failure))
            null
        }
    }

    private suspend fun sendLoop() {
        val payload = buildPacket()
        while (true) {
            // 发送放在锁内，保证 stop() 返回后不会再有报文离开本机。
            synchronized(stateLock) {
                if (!running) {
                    return
                }
                sendLoopback(payload)
                sendMulticast(payload)
            }
            delay(intervalMillis)
        }
    }

    private fun sendLoopback(payload: ByteArray) {
        val openedSocket = unicastSocket ?: return
        val target = loopbackTarget ?: return
        try {
            openedSocket.send(DatagramPacket(payload, payload.size, target))
        } catch (failure: IOException) {
            onError(failure)
        }
    }

    private fun sendMulticast(payload: ByteArray) {
        val openedSocket = multicastSocket ?: return
        val target = multicastTarget ?: return
        LanMulticast.send(openedSocket, target, payload, onError)
    }

    private fun buildPacket(): ByteArray =
        (MOTD_START + description + MOTD_END + AD_START + localPort + AD_END).encodeToByteArray()

    private fun closeSockets() {
        unicastSocket?.close()
        multicastSocket?.close()
        unicastSocket = null
        multicastSocket = null
        multicastTarget = null
        loopbackTarget = null
    }
}

private const val LOOPBACK_ADDRESS = "127.0.0.1"
private const val MULTICAST_TIME_TO_LIVE = 1

/** 广播间隔：Minecraft 客户端约每 1.5 秒会清理一次未刷新的 LAN 世界。 */
internal const val DEFAULT_BROADCAST_INTERVAL_MILLIS = 1_500L
