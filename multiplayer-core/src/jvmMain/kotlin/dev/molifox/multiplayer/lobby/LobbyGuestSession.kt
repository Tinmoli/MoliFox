// 房客会话：等待房主 → 转发 Scaffolding → 连接房间 → 转发 Minecraft → 广播本地世界。
package dev.molifox.multiplayer.lobby

import dev.molifox.core.i18n.I18n
import dev.molifox.multiplayer.common.ConnectionRoute
import dev.molifox.multiplayer.easytier.EasyTierCli
import dev.molifox.multiplayer.easytier.EasyTierHost
import dev.molifox.multiplayer.easytier.EasyTierNode
import dev.molifox.multiplayer.easytier.EasyTierNodeRequest
import dev.molifox.multiplayer.easytier.EasyTierPeers
import dev.molifox.multiplayer.easytier.EasyTierTuning
import dev.molifox.multiplayer.easytier.FreeLoopbackPortAllocator
import dev.molifox.multiplayer.easytier.LoopbackPortAllocator
import dev.molifox.multiplayer.lan.MinecraftLanBroadcast
import dev.molifox.multiplayer.net.LocalPortForward
import dev.molifox.multiplayer.protocol.ScaffoldingCode
import dev.molifox.multiplayer.scaffolding.ScaffoldingClient
import dev.molifox.multiplayer.scaffolding.ScaffoldingPlayer
import dev.molifox.multiplayer.scaffolding.ScaffoldingPlayerKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * 房客侧联机流程（与 PCL CE 的 Scaffolding 房客一致）：
 * 1. 启动 EasyTier 房客节点（DHCP 获取虚拟 IP）；
 * 2. 等待 hostname 为 `MoliFox-server-<端口>` 的房主节点；
 * 3. 把本机随机端口通过 EasyTier 转发到房主的 Scaffolding 端口，再连接房间；
 * 4. 读取房主真实的 Minecraft 端口并同样转发，最后用本地 TCP 转发 + 局域网
 *    广播让 Minecraft 客户端的多人游戏列表直接出现这个世界。
 *
 * 关闭顺序：广播 → 本机转发 → Scaffolding 连接 → EasyTier；
 * 先停广播避免残留世界。
 */
class LobbyGuestSession(
    private val code: ScaffoldingCode,
    private val machineId: String,
    private val playerName: String,
    private val runtime: EasyTierNode,
    private val logger: (message: String, error: Boolean) -> Unit,
    private val onClosed: (String) -> Unit,
    private val extraRelays: List<String> = emptyList(),
    private val tuning: EasyTierTuning = EasyTierTuning(),
    private val portAllocator: LoopbackPortAllocator = FreeLoopbackPortAllocator,
    private val hostWaitTimeoutMillis: Long = DEFAULT_HOST_WAIT_TIMEOUT_MILLIS,
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
    private val p2pWaitTimeoutMillis: Long = DEFAULT_P2P_WAIT_TIMEOUT_MILLIS,
    private val p2pRetryIntervalMillis: Long = DEFAULT_P2P_RETRY_INTERVAL_MILLIS,
    private val forwarderFactory: (EasyTierCli) -> VirtualPortForwarder =
        ::EasyTierCliPortForwarder,
    private val localForwardFactory: (Int) -> LocalPortForward = { target ->
        LocalPortForward(target)
    },
    private val broadcastFactory: (String, Int) -> MinecraftLanBroadcast = { description, port ->
        MinecraftLanBroadcast(description, port)
    },
    private val motdFactory: (String) -> String = { host -> I18n.t("multiplayer.lobbyGuestSession.1", host) },
) : LobbyGuestSessionApi {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val mutablePlayers = MutableStateFlow<List<ScaffoldingPlayer>>(emptyList())
    private var transport: GuestPortTransport? = null
    private var client: ScaffoldingClient? = null
    private var closed = false
    private var reported = false
    private var route = ConnectionRoute.Unknown

    override val players: StateFlow<List<ScaffoldingPlayer>> = mutablePlayers.asStateFlow()

    override val connectionRoute: ConnectionRoute
        get() = route

    override var localAddress: String = ""
        private set

    override var hostName: String = ""
        private set

    override suspend fun start(): Result<Unit> =
        runCatching {
            val cli = startRuntime()
            val ports =
                GuestPortTransport(
                    forwarderFactory(cli),
                    localForwardFactory,
                    broadcastFactory,
                    logger,
                ).also { transport = it }
            val host = awaitHost(cli)
            val scaffoldingLocalPort = portAllocator.allocate()
            ports.forwardTcp(scaffoldingLocalPort, host.peer.ipv4, host.scaffoldingPort)
            ports.forwardUdpBestEffort(scaffoldingLocalPort, host.peer.ipv4, host.scaffoldingPort)
            val created = connectRoom(scaffoldingLocalPort)
            var routeAttempts = 0
            while (routeAttempts < ROUTE_ATTEMPTS) {
                val hostPeer =
                    cli.peers().getOrNull()?.firstOrNull { peer ->
                        peer.hostname == host.peer.hostname
                    }
                route = routeFromCost(hostPeer?.cost)
                routeAttempts++
                if (route == ConnectionRoute.Direct || routeAttempts >= ROUTE_ATTEMPTS) break
                delay(ROUTE_RETRY_MILLIS)
            }
            startPlayerRefresh(created)
            val minecraftPort = resolveMinecraftPort(created)
            val minecraftLocalPort = portAllocator.allocate()
            ports.forwardTcp(minecraftLocalPort, host.peer.ipv4, minecraftPort)
            ports.forwardUdpBestEffort(minecraftLocalPort, host.peer.ipv4, minecraftPort)
            publishLocalWorld(created, ports.startLocalForward(minecraftLocalPort))
        }

    private suspend fun startRuntime(): EasyTierCli {
        val request =
            EasyTierNodeRequest.guest(
                code,
                machineId,
                extraRelays = extraRelays,
                tuning = tuning,
            )
        return runtime.start(request, ::onRuntimeExit).getOrElse { failure ->
            throw IOException(I18n.t("multiplayer.easyTierRuntime.1", failure.message), failure)
        }
    }

    /** 轮询 peer 列表，直到唯一房主出现；超时给出可操作提示。 */
    private suspend fun awaitHost(cli: EasyTierCli): EasyTierHost {
        val deadline = System.nanoTime() + hostWaitTimeoutMillis * NANOS_PER_MILLI
        var lastFailure: String? = null
        while (System.nanoTime() < deadline) {
            val peers = cli.peers()
            if (peers.isSuccess) {
                val hosts = EasyTierPeers.hosts(peers.getOrThrow())
                if (hosts.size > 1) {
                    throw IOException(
                        I18n.t("multiplayer.easyTierPeer.2"),
                    )
                }
                hosts.firstOrNull()?.let { return it }
            } else {
                lastFailure = peers.exceptionOrNull()?.message
            }
            delay(pollIntervalMillis)
        }
        throw IOException(
            lastFailure ?: I18n.t("multiplayer.lobbyGuestSession.2"),
        )
    }

    /**
     * 连接房主；发现房主不等于数据面已经可用。
     *
     * 首次连不上时继续重试 [p2pWaitTimeoutMillis] 毫秒，给 EasyTier 打洞留时间；
     * 打洞失败后 EasyTier 会自动尝试中继转发，因此超时说明“P2P 与中继都没成功”。
     */
    private suspend fun connectRoom(scaffoldingLocalPort: Int): ScaffoldingClient {
        val deadline = System.nanoTime() + p2pWaitTimeoutMillis * NANOS_PER_MILLI
        var lastFailure: Throwable? = null
        logger(I18n.t("multiplayerB.lobbyGuestSession.hostFound"), false)
        while (true) {
            // 重试失败的客户端会触发 onDisconnected；只有真正连上时才上报。
            var established = false
            val created = newScaffoldingClient(scaffoldingLocalPort, { established }, ::onClientDisconnected)
            val result = created.connect()
            if (result.isSuccess) {
                established = true
                synchronized(lock) { client = created }
                return created
            }
            created.close()
            lastFailure = result.exceptionOrNull()
            if (System.nanoTime() >= deadline) break
            delay(p2pRetryIntervalMillis)
        }
        throw IOException(
            I18n.t(
                "multiplayerB.lobbyGuestSession.connectRoomFailed",
                p2pWaitTimeoutMillis / MILLIS_PER_MINUTE,
            ) + I18n.t("multiplayer.lobbyGuestSession.3"),
            lastFailure,
        )
    }

    /** 组装客人侧脚手架客户端；建立前断线/报错不上报，避免重试刷屏。 */
    private fun newScaffoldingClient(
        scaffoldingLocalPort: Int,
        isEstablished: () -> Boolean,
        onDisconnected: (Throwable?) -> Unit,
    ): ScaffoldingClient =
        ScaffoldingClient(
            host = LOOPBACK,
            port = scaffoldingLocalPort,
            player =
                ScaffoldingPlayer(
                    machineId,
                    playerName,
                    kind = ScaffoldingPlayerKind.GUEST,
                ),
            onDisconnected = { failure -> if (isEstablished()) onDisconnected(failure) },
            onError = { failure ->
                if (isEstablished()) {
                    logger(I18n.t("multiplayerB.lobbyGuestSession.scaffoldingError", failure.message), true)
                }
            },
        )

    /** 广播世界并推送玩家列表；[localPort] 既是本机转发端口也是展示给玩家的端口。 */
    private fun publishLocalWorld(
        client: ScaffoldingClient,
        localPort: Int,
    ) {
        hostName =
            client.players.value
                .firstOrNull { it.kind == ScaffoldingPlayerKind.HOST }
                ?.name
                .orEmpty()
        transport?.startBroadcast(motdFactory(hostName.ifBlank { FALLBACK_HOST_NAME }), localPort)
        localAddress = "$LOOPBACK:$localPort"
        scope.launch { client.players.collect { mutablePlayers.value = it } }
        logger(
            I18n.t("multiplayerB.lobbyGuestSession.joined", localAddress, hostName),
            false,
        )
    }

    /** 玩家列表来自房主广播；先拉一次再定期刷新，失败只写技术日志。 */
    private fun startPlayerRefresh(client: ScaffoldingClient) {
        scope.launch {
            while (isActive) {
                client.refreshPlayers().onFailure { failure ->
                    logger(I18n.t("multiplayerB.lobbyGuestSession.refreshPlayersFailed", failure.message), true)
                }
                delay(PLAYER_REFRESH_INTERVAL_MILLIS)
            }
        }
    }

    private fun onRuntimeExit(
        code: Int,
        output: String,
    ) {
        logger(I18n.t("multiplayerB.lobbyGuestSession.runtimeExited", code, output), true)
        report(I18n.t("multiplayerB.lobbySession.runtimeExitedRoomClosed"))
    }

    private fun onClientDisconnected(failure: Throwable?) {
        report(
            if (failure == null) {
                I18n.t("multiplayer.lobbyGuestSession.6")
            } else {
                I18n.t("multiplayer.lobbyGuestSession.7", failure.message)
            },
        )
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
                listOf(transport, client).also {
                    transport = null
                    client = null
                }
            }
        scope.cancel()
        current.forEach { closeable ->
            runCatching { closeable?.close() }
                .onFailure { failure ->
                    logger(I18n.t("multiplayerB.lobbyGuestSession.closeResourcesFailed", failure.message), true)
                }
        }
        runCatching { runtime.stop() }
            .onFailure { failure ->
                logger(I18n.t("multiplayerB.lobbyGuestSession.stopRuntimeFailed", failure.message), true)
            }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        val FALLBACK_HOST_NAME: String
            get() = I18n.t("multiplayer.lobbyGuestSession.8")
        const val NANOS_PER_MILLI = 1_000_000L
        const val DEFAULT_HOST_WAIT_TIMEOUT_MILLIS = 60_000L
        const val DEFAULT_POLL_INTERVAL_MILLIS = 1_000L
        const val DEFAULT_P2P_WAIT_TIMEOUT_MILLIS = 180_000L
        const val MILLIS_PER_MINUTE = 60_000L
        const val DEFAULT_P2P_RETRY_INTERVAL_MILLIS = 1_500L
        const val MILLIS_PER_SECOND = 1_000L
        const val PLAYER_REFRESH_INTERVAL_MILLIS = 10_000L
        const val ROUTE_ATTEMPTS = 4
        const val ROUTE_RETRY_MILLIS = 500L
    }
}

/** 把 EasyTier 的 cost 转成用户能理解的数据路径。 */
private fun routeFromCost(cost: String?): ConnectionRoute =
    when {
        cost == null -> ConnectionRoute.Unknown
        cost.contains("relay", ignoreCase = true) -> ConnectionRoute.Relay
        cost.equals("p2p", ignoreCase = true) || cost.equals("direct", ignoreCase = true) ->
            ConnectionRoute.Direct
        else -> ConnectionRoute.Unknown
    }

/** 从脚手架客户端解析 MC 转发端口：先读已广播值，必要时刷新一次。 */
private suspend fun resolveMinecraftPort(client: ScaffoldingClient): Int =
    client.serverPort.value
        ?: client.refreshServerPort().getOrElse { failure ->
            throw IOException(
                I18n.t("multiplayer.lobbyGuestSession.4", failure.message),
                failure,
            )
        }
        ?: throw IOException(
            I18n.t("multiplayer.lobbyGuestSession.5"),
        )
