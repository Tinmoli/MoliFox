// 房主/房客会话的对外契约与可注入工厂；后端只依赖这里，测试可替换为假会话。
package dev.molifox.multiplayer.lobby

import dev.molifox.multiplayer.common.LanWorld
import dev.molifox.multiplayer.easytier.EasyTierNode
import dev.molifox.multiplayer.easytier.EasyTierTuning
import dev.molifox.multiplayer.lan.MinecraftLanDiscovery
import dev.molifox.multiplayer.protocol.ScaffoldingCode
import dev.molifox.multiplayer.scaffolding.ScaffoldingPlayer
import kotlinx.coroutines.flow.StateFlow

/** 已开启的房主房间；后端用它展示玩家并在关闭时释放资源。 */
interface LobbyHostSessionApi : AutoCloseable {
    /** Scaffolding 服务端实际监听端口（房主 hostname 的末尾部分）。 */
    val scaffoldingPort: Int

    val players: StateFlow<List<ScaffoldingPlayer>>

    /** 建立 Scaffolding 服务端并启动 EasyTier；失败时调用方负责 [close]。 */
    suspend fun start(): Result<Unit>
}

/** 已加入的房客连接；[localAddress] 是给玩家手动添加的本地入口。 */
interface LobbyGuestSessionApi : AutoCloseable {
    val players: StateFlow<List<ScaffoldingPlayer>>

    /** 形如 `127.0.0.1:52341`；在 [start] 成功后才有意义。 */
    val localAddress: String

    /** 房主显示名；用于本机局域网广播的描述。 */
    val hostName: String

    /** 启动节点、等待房主、建立转发并开始广播；失败时调用方负责 [close]。 */
    suspend fun start(): Result<Unit>
}

/** 创建房主会话所需的全部上下文。 */
data class LobbyHostContext(
    val code: ScaffoldingCode,
    val world: LanWorld,
    val machineId: String,
    val playerName: String,
    val runtime: EasyTierNode,
    val extraRelays: List<String>,
    val tuning: EasyTierTuning = EasyTierTuning(),
    val logger: (message: String, error: Boolean) -> Unit,
    val probe: MinecraftWorldProbe,
    /** 房间因存档退出、进程退出等原因被关闭时回调一次可读原因。 */
    val onClosed: (String) -> Unit,
)

/** 创建房客会话所需的全部上下文。 */
data class LobbyGuestContext(
    val code: ScaffoldingCode,
    val machineId: String,
    val playerName: String,
    val runtime: EasyTierNode,
    val extraRelays: List<String>,
    val tuning: EasyTierTuning = EasyTierTuning(),
    val logger: (message: String, error: Boolean) -> Unit,
    val onClosed: (String) -> Unit,
)

fun interface LobbyHostSessionFactory {
    fun create(context: LobbyHostContext): LobbyHostSessionApi
}

fun interface LobbyGuestSessionFactory {
    fun create(context: LobbyGuestContext): LobbyGuestSessionApi
}

/** 生产默认实现：真实 EasyTier + Scaffolding + 回环转发。 */
object DefaultLobbyHostSessionFactory : LobbyHostSessionFactory {
    override fun create(context: LobbyHostContext): LobbyHostSessionApi =
        LobbyHostSession(
            code = context.code,
            world = context.world,
            machineId = context.machineId,
            playerName = context.playerName,
            runtime = context.runtime,
            logger = context.logger,
            onClosed = context.onClosed,
            extraRelays = context.extraRelays,
            tuning = context.tuning,
            probe = context.probe,
        )
}

object DefaultLobbyGuestSessionFactory : LobbyGuestSessionFactory {
    override fun create(context: LobbyGuestContext): LobbyGuestSessionApi =
        LobbyGuestSession(
            code = context.code,
            machineId = context.machineId,
            playerName = context.playerName,
            runtime = context.runtime,
            logger = context.logger,
            onClosed = context.onClosed,
            extraRelays = context.extraRelays,
            tuning = context.tuning,
        )
}

/** 局域网世界发现的抽象：后端只关心启动、结果流与关闭，测试不需要真绑定 4445。 */
interface LobbyWorldDiscovery : AutoCloseable {
    val worlds: StateFlow<List<LanWorld>>

    /** 开始监听；失败返回可读原因。 */
    fun start(): Result<Unit>
}

/** 局域网世界发现工厂；测试可注入假实现。 */
fun interface LanDiscoveryFactory {
    fun create(onError: (Throwable) -> Unit): LobbyWorldDiscovery

    companion object {
        /** 生产默认：监听 4445，加入 224.0.2.60 组播。 */
        val DEFAULT = LanDiscoveryFactory { onError -> MinecraftLanDiscoveryAdapter(onError) }
    }
}

/** 把真实 [MinecraftLanDiscovery] 适配成后端依赖的接口。 */
internal class MinecraftLanDiscoveryAdapter(
    private val onError: (Throwable) -> Unit,
) : LobbyWorldDiscovery {
    private val delegate = MinecraftLanDiscovery(onError = onError)

    override val worlds: StateFlow<List<LanWorld>> = delegate.worlds

    override fun start(): Result<Unit> = delegate.start().map { }

    override fun close() {
        delegate.close()
    }
}
