// 房客的端口编排：EasyTier 转发、本机转发与局域网广播，统一关闭顺序。
package dev.molifox.multiplayer.lobby

import dev.molifox.core.i18n.I18n
import dev.molifox.multiplayer.easytier.EasyTierForwardProtocol
import dev.molifox.multiplayer.lan.MinecraftLanBroadcast
import dev.molifox.multiplayer.net.LocalPortForward
import java.io.IOException

/**
 * 把房客需要的三段转发集中起来：
 * - EasyTier 把本机端口映射到房主虚拟 IP；
 * - 本机 TCP 转发让 Minecraft 从固定地址接入；
 * - 局域网广播把世界注入本机多人游戏列表。
 *
 * [close] 顺序为广播 → 本机转发；EasyTier 转发随节点停止一起消失。
 */
internal class GuestPortTransport(
    private val forwarder: VirtualPortForwarder,
    private val localForwardFactory: (Int) -> LocalPortForward,
    private val broadcastFactory: (String, Int) -> MinecraftLanBroadcast,
    private val logger: (message: String, error: Boolean) -> Unit,
) : AutoCloseable {
    private var localForward: LocalPortForward? = null
    private var broadcast: MinecraftLanBroadcast? = null

    /** 建立 TCP 转发；失败时抛出可读异常，由会话统一回收。 */
    fun forwardTcp(
        localPort: Int,
        targetHost: String,
        targetPort: Int,
    ) {
        forwarder
            .addPortForward(EasyTierForwardProtocol.TCP, localPort, targetHost, targetPort)
            .getOrElse { failure ->
                throw IOException(I18n.t("multiplayer.guestPortTransport.1", failure.message), failure)
            }
    }

    /** UDP 只影响部分模组，失败时退回纯 TCP，不阻断联机。 */
    fun forwardUdpBestEffort(
        localPort: Int,
        targetHost: String,
        targetPort: Int,
    ) {
        forwarder
            .addPortForward(EasyTierForwardProtocol.UDP, localPort, targetHost, targetPort)
            .onFailure { failure ->
                logger(I18n.t("multiplayerB.guestPortTransport.udpForwardFailed", failure.message), true)
            }
    }

    /** 建立本机端口转发并返回可展示的端口。 */
    fun startLocalForward(minecraftLocalPort: Int): Int {
        val created = localForwardFactory(minecraftLocalPort)
        created.start().getOrElse { failure ->
            created.close()
            throw IOException(I18n.t("multiplayer.guestPortTransport.2", failure.message), failure)
        }
        localForward = created
        return created.port
    }

    /** 开始向本机 Minecraft 广播世界。 */
    fun startBroadcast(
        description: String,
        localPort: Int,
    ) {
        val created = broadcastFactory(description, localPort)
        created.start().getOrElse { failure ->
            created.close()
            throw IOException(I18n.t("multiplayer.guestPortTransport.3", failure.message), failure)
        }
        broadcast = created
    }

    override fun close() {
        broadcast?.let { active ->
            runCatching { active.stop() }
                .onFailure { failure ->
                    logger(I18n.t("multiplayerB.guestPortTransport.stopBroadcastFailed", failure.message), true)
                }
        }
        broadcast = null
        localForward?.let { active ->
            runCatching { active.close() }
                .onFailure { failure ->
                    logger(I18n.t("multiplayerB.guestPortTransport.closeLocalForwardFailed", failure.message), true)
                }
        }
        localForward = null
    }
}
