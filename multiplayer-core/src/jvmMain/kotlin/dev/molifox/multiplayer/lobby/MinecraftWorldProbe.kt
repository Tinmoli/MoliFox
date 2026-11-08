// 探测本机 Minecraft 端口是否仍可连接：房主会话用它判断存档是否已退出。
package dev.molifox.multiplayer.lobby

import java.net.InetSocketAddress
import java.net.Socket

fun interface MinecraftWorldProbe {
    /** 端口可建立 TCP 连接时返回 true；不发送任何 Minecraft 协议数据。 */
    fun isAvailable(port: Int): Boolean
}

/** 回环 TCP 探测；超时与失败都按“世界已关闭”处理，避免看门狗误报在线。 */
class TcpMinecraftWorldProbe(
    private val timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
) : MinecraftWorldProbe {
    override fun isAvailable(port: Int): Boolean =
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(LOOPBACK_ADDRESS, port), timeoutMillis)
            }
        }.isSuccess

    private companion object {
        const val LOOPBACK_ADDRESS = "127.0.0.1"
        const val DEFAULT_TIMEOUT_MILLIS = 3_000
    }
}
