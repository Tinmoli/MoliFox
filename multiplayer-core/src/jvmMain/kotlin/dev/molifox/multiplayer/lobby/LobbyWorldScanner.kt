// 局域网世界扫描：一次只运行一个发现会话，并定期剔除已经关闭的世界。
package dev.molifox.multiplayer.lobby

import dev.molifox.core.i18n.I18n
import dev.molifox.multiplayer.common.LanWorld
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 世界发现的生命周期管理。
 *
 * 发现组件对同一端口只探测一次，因此另外开一个低频校验循环，把已关闭的世界从列表里
 * 剔除；[stop] 必须在建房/加入前调用，避免和房客的 4445 广播抢占端口。
 */
internal class LobbyWorldScanner(
    private val factory: LanDiscoveryFactory,
    private val probe: MinecraftWorldProbe,
    private val logger: (message: String, error: Boolean) -> Unit,
    private val scanCheckIntervalMillis: Long,
    private val worldsProvider: () -> List<LanWorld>,
    private val onDiscovered: (List<LanWorld>) -> Unit,
    private val onPruned: (List<LanWorld>, List<LanWorld>) -> Unit,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var discovery: LobbyWorldDiscovery? = null
    private var discoveryJob: Job? = null
    private var verifierJob: Job? = null

    /** 重启发现会话：先关闭旧的，再启动新的；失败返回原因（调用方决定是否提示用户）。 */
    fun start(): Result<Unit> {
        stop()
        val created =
            factory.create { failure ->
                logger(I18n.t("multiplayerB.lobbyWorldScanner.discoveryFailed", failure.message), true)
            }
        val started = created.start()
        started.onFailure { failure ->
            logger(I18n.t("multiplayerB.lobbyWorldScanner.listenFailed", failure.message), true)
        }
        discovery = created
        discoveryJob =
            scope.launch {
                created.worlds.collect { worlds ->
                    if (worlds.isNotEmpty()) onDiscovered(worlds)
                }
            }
        verifierJob =
            scope.launch {
                while (isActive) {
                    delay(scanCheckIntervalMillis)
                    val inspected = worldsProvider()
                    val alive = inspected.filter { probe.isAvailable(it.port) }
                    onPruned(inspected, alive)
                }
            }
        return started
    }

    /** 按联机开关同步扫描状态；已关闭的发现不会重新启动。 */
    fun sync(enabled: Boolean) {
        if (enabled) start() else stop()
    }

    /** 停止监听与校验；重复调用无副作用。 */
    fun stop() {
        discoveryJob?.cancel()
        discoveryJob = null
        verifierJob?.cancel()
        verifierJob = null
        discovery?.let { active ->
            runCatching { active.close() }
                .onFailure { failure ->
                    logger(I18n.t("multiplayerB.lobbyWorldScanner.stopDiscoveryFailed", failure.message), true)
                }
        }
        discovery = null
    }

    override fun close() {
        stop()
        scope.cancel()
    }
}
