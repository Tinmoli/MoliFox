// EasyTier 节点的抽象：会话只依赖这一层，测试可注入假节点而不启动真实进程。
package dev.molifox.multiplayer.easytier

/**
 * 一个 EasyTier 虚拟网络节点。
 *
 * 实现负责进程、RPC 端口与 CLI 生命周期；房主/房客会话只通过 [start] 拿到 CLI 门面，
 * 并在 [stop] 时结束整个节点。
 */
interface EasyTierNode : AutoCloseable {
    /**
     * 启动节点并等待 CLI 可用。
     *
     * @param onUnexpectedExit 非主动停止的进程退出回调；参数为退出码与最近日志。
     */
    suspend fun start(
        request: EasyTierNodeRequest,
        onUnexpectedExit: (Int, String) -> Unit,
    ): Result<EasyTierCli>

    /** 主动停止；必须可重复调用。 */
    fun stop()
}
