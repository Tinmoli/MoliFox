// EasyTier 端口转发对会话的抽象：生产用 CLI，测试用回环中继，避免测试依赖真实虚拟网络。
package dev.molifox.multiplayer.lobby

import dev.molifox.multiplayer.easytier.EasyTierCli
import dev.molifox.multiplayer.easytier.EasyTierForwardProtocol

fun interface VirtualPortForwarder {
    /**
     * 在 127.0.0.1:[localPort] 建立到 [targetHost]:[targetPort] 的转发。
     *
     * 转发由 EasyTier 进程持有；实现返回后监听应立即可用。
     */
    fun addPortForward(
        protocol: EasyTierForwardProtocol,
        localPort: Int,
        targetHost: String,
        targetPort: Int,
    ): Result<Unit>
}

/** 生产实现：调用 `easytier-cli port-forward`. */
class EasyTierCliPortForwarder(
    private val cli: EasyTierCli,
) : VirtualPortForwarder {
    override fun addPortForward(
        protocol: EasyTierForwardProtocol,
        localPort: Int,
        targetHost: String,
        targetPort: Int,
    ): Result<Unit> = cli.addPortForward(protocol, localPort, targetHost, targetPort)
}
