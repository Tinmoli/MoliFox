// 房间阶段编排：生成/校验邀请码，创建房主或房客会话，并统一回收房间资源。
package dev.molifox.multiplayer.lobby

import dev.molifox.multiplayer.common.LanWorld
import dev.molifox.multiplayer.common.LobbyPhase
import dev.molifox.multiplayer.common.LobbyRole
import dev.molifox.multiplayer.easytier.EasyTierInstaller
import dev.molifox.multiplayer.easytier.EasyTierNode
import dev.molifox.multiplayer.protocol.RoomCode
import dev.molifox.multiplayer.protocol.RoomCodeRandom
import dev.molifox.multiplayer.protocol.ScaffoldingCode
import dev.molifox.multiplayer.scaffolding.ScaffoldingPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * 一个实例同时只负责一个房间：房主或房客。
 *
 * 所有会改变房间的操作都由 [PclLobbyBackend] 在命令锁内调用；这里只关心会话
 * 生命周期与状态落地，不涉及世界发现。
 */
internal class LobbyRoomCoordinator(
    private val installer: EasyTierInstaller,
    private val runtimeFactory: (java.nio.file.Path, java.nio.file.Path) -> EasyTierNode,
    private val hostFactory: LobbyHostSessionFactory,
    private val guestFactory: LobbyGuestSessionFactory,
    private val logger: (message: String, error: Boolean) -> Unit,
    private val probe: MinecraftWorldProbe,
    private val scope: CoroutineScope,
    private val store: LobbyStateStore,
    private val machineId: () -> String,
    private val playerName: () -> String,
    /** 建房/加入前解析中继：自定义优先，其次实时活动节点。 */
    private val relays: suspend () -> List<String>,
    private val onSessionClosed: (String) -> Unit,
) : AutoCloseable {
    private val secureRandom = SecureRandom()
    private val roomCodeRandom =
        RoomCodeRandom { size ->
            ByteArray(size).also(secureRandom::nextBytes)
        }
    private var hostSession: LobbyHostSessionApi? = null
    private var guestSession: LobbyGuestSessionApi? = null
    private var runtime: EasyTierNode? = null
    private var playersJob: Job? = null

    /** 用本机发现的 [world] 建房；成功后状态为 RoomOpen 并展示邀请码。 */
    suspend fun openRoom(world: LanWorld): Result<Unit> {
        store.update {
            it.copy(
                phase = LobbyPhase.StartingRuntime,
                role = LobbyRole.Host,
                roomCode = null,
                localAddress = null,
                players = emptyList(),
                error = null,
            )
        }
        val code = RoomCode.generate(roomCodeRandom)
        val result = startHosting(code, world)
        if (result.isFailure) {
            teardown()
            store.update {
                it.copy(
                    phase = LobbyPhase.Idle,
                    role = null,
                    error = "创建房间失败：${result.exceptionOrNull()?.message}",
                )
            }
        }
        return result
    }

    /** 用邀请码加入房主房间；成功后状态为 Connected 并给出本地入口。 */
    suspend fun joinRoom(code: ScaffoldingCode): Result<Unit> {
        store.update {
            it.copy(
                phase = LobbyPhase.StartingRuntime,
                role = LobbyRole.Guest,
                roomCode = code.code,
                localAddress = null,
                players = emptyList(),
                error = null,
            )
        }
        val result = startJoining(code)
        if (result.isFailure) {
            teardown()
            store.update {
                it.copy(
                    phase = LobbyPhase.Idle,
                    role = null,
                    error = "加入房间失败：${result.exceptionOrNull()?.message}",
                )
            }
        }
        return result
    }

    private suspend fun startHosting(
        code: ScaffoldingCode,
        world: LanWorld,
    ): Result<Unit> =
        runCatching {
            val node = createRuntime()
            runtime = node
            val session =
                hostFactory.create(
                    LobbyHostContext(
                        code = code,
                        world = world,
                        machineId = machineId(),
                        playerName = playerName(),
                        runtime = node,
                        extraRelays = relays(),
                        logger = logger,
                        probe = probe,
                        onClosed = onSessionClosed,
                    ),
                )
            hostSession = session
            store.update { it.copy(phase = LobbyPhase.CreatingRoom) }
            session.start().getOrElse { failure -> throw failure }
            watchPlayers(session.players)
            store.update { it.copy(phase = LobbyPhase.RoomOpen, roomCode = code.code) }
        }

    private suspend fun startJoining(code: ScaffoldingCode): Result<Unit> =
        runCatching {
            val node = createRuntime()
            runtime = node
            val session =
                guestFactory.create(
                    LobbyGuestContext(
                        code = code,
                        machineId = machineId(),
                        playerName = playerName(),
                        runtime = node,
                        extraRelays = relays(),
                        logger = logger,
                        onClosed = onSessionClosed,
                    ),
                )
            guestSession = session
            store.update { it.copy(phase = LobbyPhase.JoiningRoom) }
            session.start().getOrElse { failure -> throw failure }
            watchPlayers(session.players)
            store.update {
                it.copy(phase = LobbyPhase.Connected, localAddress = session.localAddress)
            }
        }

    private fun watchPlayers(players: kotlinx.coroutines.flow.StateFlow<List<ScaffoldingPlayer>>) {
        playersJob?.cancel()
        playersJob =
            scope.launch {
                players.collect { list ->
                    store.update { it.copy(players = list.toRoomPlayers(machineId())) }
                }
            }
    }

    private fun createRuntime(): EasyTierNode =
        runtimeFactory(installer.executable(), installer.cliExecutable())

    /** 关闭顺序：玩家收集 → 会话（内部停 EasyTier） → 兜底停运行时。 */
    fun teardown() {
        playersJob?.cancel()
        playersJob = null
        hostSession?.let { session -> closeSession("房主", session) }
        hostSession = null
        guestSession?.let { session -> closeSession("房客", session) }
        guestSession = null
        runtime?.let { node ->
            runCatching { node.stop() }
                .onFailure { logger("停止 EasyTier 运行时失败：${it.message}", true) }
        }
        runtime = null
    }

    private fun closeSession(
        label: String,
        session: AutoCloseable,
    ) {
        runCatching { session.close() }
            .onFailure { logger("关闭$label 会话失败：${it.message}", true) }
    }

    override fun close() {
        teardown()
    }
}
