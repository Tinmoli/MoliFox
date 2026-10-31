// 房主会话：Scaffolding 服务端 + EasyTier 房主节点 + 存档退出看门狗。
package dev.molifox.multiplayer.lobby

import dev.molifox.multiplayer.common.LanWorld
import dev.molifox.multiplayer.easytier.EasyTierNode
import dev.molifox.multiplayer.easytier.EasyTierNodeRequest
import dev.molifox.multiplayer.protocol.ScaffoldingCode
import dev.molifox.multiplayer.scaffolding.ScaffoldingPlayer
import dev.molifox.multiplayer.scaffolding.ScaffoldingPlayerKind
import dev.molifox.multiplayer.scaffolding.ScaffoldingServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * 房主侧联机流程：
 * 1. 在随机端口启动 Scaffolding 服务端，并登记 Minecraft 世界端口；
 * 2. 用 Scaffolding 端口生成固定的房主 hostname，启动 EasyTier 房主节点；
 * 3. 每 [watchdogIntervalMillis] 探测本机 Minecraft 端口，存档退出时关闭房间。
 *
 * 关闭顺序固定为：看门狗 → Scaffolding 服务端 → EasyTier。先停服务端可避免
 * 房客在虚拟网络消失前收到误导性的“连接成功”。
 */
class LobbyHostSession(
    private val code: ScaffoldingCode,
    private val world: LanWorld,
    private val machineId: String,
    private val playerName: String,
    private val runtime: EasyTierNode,
    private val logger: (message: String, error: Boolean) -> Unit,
    private val onClosed: (String) -> Unit,
    private val extraRelays: List<String> = emptyList(),
    private val probe: MinecraftWorldProbe = TcpMinecraftWorldProbe(),
    private val serverFactory: (ScaffoldingPlayer) -> ScaffoldingServer = { host ->
        ScaffoldingServer(hostProfile = host)
    },
    private val watchdogIntervalMillis: Long = DEFAULT_WATCHDOG_INTERVAL_MILLIS,
) : LobbyHostSessionApi {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val mutablePlayers = MutableStateFlow<List<ScaffoldingPlayer>>(emptyList())
    private var server: ScaffoldingServer? = null
    private var watchdog: Job? = null
    private var closed = false
    private var reported = false

    override val players: StateFlow<List<ScaffoldingPlayer>> = mutablePlayers.asStateFlow()

    override val scaffoldingPort: Int
        get() =
            synchronized(lock) { server?.port }
                ?: throw IllegalStateException("房间尚未创建")

    override suspend fun start(): Result<Unit> =
        runCatching {
            val created =
                serverFactory(
                    ScaffoldingPlayer(machineId, playerName, kind = ScaffoldingPlayerKind.HOST),
                )
            created.start().getOrElse { failure ->
                throw IOException("无法建立 Scaffolding 服务端：${failure.message}", failure)
            }
            created.setMinecraftPort(world.port)
            synchronized(lock) { server = created }
            scope.launch { created.players.collect { mutablePlayers.value = it } }
            startRuntime(created.port)
            startWatchdog()
        }

    private suspend fun startRuntime(scaffoldingPort: Int) {
        val request =
            EasyTierNodeRequest.host(
                code,
                machineId,
                scaffoldingPort,
                world.port,
                extraRelays,
            )
        runtime.start(request, ::onRuntimeExit).getOrElse { failure ->
            throw IOException("无法启动 EasyTier：${failure.message}", failure)
        }
        logger("房间已开启：Scaffolding 端口 $scaffoldingPort，世界 ${world.name}(${world.port})", false)
    }

    private fun startWatchdog() {
        watchdog =
            scope.launch {
                while (isActive) {
                    delay(watchdogIntervalMillis)
                    if (!probe.isAvailable(world.port)) {
                        report("存档已退出，房间已关闭。")
                        return@launch
                    }
                }
            }
    }

    private fun onRuntimeExit(
        code: Int,
        output: String,
    ) {
        logger("EasyTier 房主进程退出（退出码 $code）：$output", true)
        report("EasyTier 进程已退出，房间已关闭。")
    }

    private fun report(message: String) {
        val shouldReport =
            synchronized(lock) {
                if (closed || reported) {
                    false
                } else {
                    reported = true
                    true
                }
            }
        if (shouldReport) onClosed(message)
    }

    override fun close() {
        val current =
            synchronized(lock) {
                if (closed) return
                closed = true
                watchdog.also { watchdog = null } to server.also { server = null }
            }
        current.first?.cancel()
        scope.cancel()
        current.second?.let { active ->
            runCatching { active.close() }
                .onFailure { logger("关闭 Scaffolding 服务端失败：${it.message}", true) }
        }
        runCatching { runtime.stop() }
            .onFailure { logger("停止 EasyTier 房主进程失败：${it.message}", true) }
    }

    private companion object {
        const val DEFAULT_WATCHDOG_INTERVAL_MILLIS = 15_000L
    }
}
