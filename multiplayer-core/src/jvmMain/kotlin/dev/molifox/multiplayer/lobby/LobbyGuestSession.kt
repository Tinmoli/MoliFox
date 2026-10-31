// 房客会话：等待房主 → 转发 Scaffolding → 连接房间 → 转发 Minecraft → 广播本地世界。
package dev.molifox.multiplayer.lobby

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
    private val motdFactory: (String) -> String = { host -> "$host 的世界" },
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
            throw IOException("无法启动 EasyTier：${failure.message}", failure)
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
                        "虚拟网络里出现多个房主节点，为避免连错已停止加入。",
                    )
                }
                hosts.firstOrNull()?.let { return it }
            } else {
                lastFailure = peers.exceptionOrNull()?.message
            }
            delay(pollIntervalMillis)
        }
        throw IOException(
            lastFailure ?: "等待房主超时，请确认邀请码、双方网络与中继是否可用。",
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
        logger("房主已发现，正在建立连接（优先 P2P，失败自动走中继，最多等 3 分钟）…", false)
        while (true) {
            // 重试失败的客户端会触发 onDisconnected；只有真正连上时才上报。
            var established = false
            val created =
                ScaffoldingClient(
                    host = LOOPBACK,
                    port = scaffoldingLocalPort,
                    player =
                        ScaffoldingPlayer(
                            machineId,
                            playerName,
                            kind = ScaffoldingPlayerKind.GUEST,
                        ),
                    onDisconnected = { failure -> if (established) onClientDisconnected(failure) },
                    onError = { failure ->
                        if (established) logger("Scaffolding 连接错误：${failure.message}", true)
                    },
                )
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
            "无法连接房主房间：${p2pWaitTimeoutMillis / MILLIS_PER_MINUTE} 分钟内 P2P 与中继转发都失败。" +
                "请确认自建节点开启转发（relay-network-whitelist=\"MoliFox*\"），或让双方在同一局域网后重试。",
            lastFailure,
        )
    }

    private suspend fun resolveMinecraftPort(client: ScaffoldingClient): Int =
        client.serverPort.value
            ?: client.refreshServerPort().getOrElse { failure ->
                throw IOException(
                    "无法读取房主的 Minecraft 端口：${failure.message}",
                    failure,
                )
            }
            ?: throw IOException(
                "房主还没有准备好 Minecraft 端口，请让房主确认存档已对局域网开放。",
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
        logger("已加入房间：本地入口 $localAddress，房主 $hostName", false)
    }

    /** 玩家列表来自房主广播；先拉一次再定期刷新，失败只写技术日志。 */
    private fun startPlayerRefresh(client: ScaffoldingClient) {
        scope.launch {
            while (isActive) {
                client.refreshPlayers().onFailure { failure ->
                    logger("刷新房间玩家列表失败：${failure.message}", true)
                }
                delay(PLAYER_REFRESH_INTERVAL_MILLIS)
            }
        }
    }

    private fun onRuntimeExit(
        code: Int,
        output: String,
    ) {
        logger("EasyTier 房客进程退出（退出码 $code）：$output", true)
        report("EasyTier 进程已退出，房间已关闭。")
    }

    private fun onClientDisconnected(failure: Throwable?) {
        report(
            if (failure == null) "房主已关闭房间。" else "与房主的连接已断开：${failure.message}",
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
                .onFailure { logger("关闭房客连接资源失败：${it.message}", true) }
        }
        runCatching { runtime.stop() }
            .onFailure { logger("停止 EasyTier 房客进程失败：${it.message}", true) }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val FALLBACK_HOST_NAME = "房主"
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
