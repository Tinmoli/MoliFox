// 房主会话：Scaffolding 服务端 + EasyTier 房主节点 + 存档退出看门狗。
package dev.molifox.multiplayer.lobby

import dev.molifox.core.i18n.I18n
import dev.molifox.multiplayer.common.LanWorld
import dev.molifox.multiplayer.easytier.EasyTierNode
import dev.molifox.multiplayer.easytier.EasyTierNodeRequest
import dev.molifox.multiplayer.easytier.EasyTierTuning
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
    private val tuning: EasyTierTuning = EasyTierTuning(),
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
                ?: throw IllegalStateException(I18n.t("multiplayer.lobbyHostSession.1"))

    override suspend fun start(): Result<Unit> =
        runCatching {
            val created =
                serverFactory(
                    ScaffoldingPlayer(machineId, playerName, kind = ScaffoldingPlayerKind.HOST),
                )
            created.start().getOrElse { failure ->
                throw IOException(I18n.t("multiplayer.lobbyHostSession.2", failure.message), failure)
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
            throw IOException(I18n.t("multiplayer.easyTierRuntime.1", failure.message), failure)
        }
        logger(I18n.t("multiplayer.lobbyHostSession.3", scaffoldingPort, world.name, world.port), false)
    }

    private fun startWatchdog() {
        watchdog =
            scope.launch {
                while (isActive) {
                    delay(watchdogIntervalMillis)
                    if (!probe.isAvailable(world.port)) {
                        report(I18n.t("multiplayer.lobbyHostSession.4"))
                        return@launch
                    }
                }
            }
    }

    private fun onRuntimeExit(
        code: Int,
        output: String,
    ) {
        logger(I18n.t("multiplayerB.lobbyHostSession.runtimeExited", code, output), true)
        report(I18n.t("multiplayerB.lobbySession.runtimeExitedRoomClosed"))
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
                .onFailure { failure ->
                    logger(I18n.t("multiplayerB.lobbyHostSession.closeServerFailed", failure.message), true)
                }
        }
        runCatching { runtime.stop() }
            .onFailure { failure ->
                logger(I18n.t("multiplayerB.lobbyHostSession.stopRuntimeFailed", failure.message), true)
            }
    }

    private companion object {
        const val DEFAULT_WATCHDOG_INTERVAL_MILLIS = 15_000L
    }
}
