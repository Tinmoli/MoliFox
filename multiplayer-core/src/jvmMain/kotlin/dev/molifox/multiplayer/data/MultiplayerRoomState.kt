// 集中维护联机状态：房间生命周期、核心上报状态与异常提示都只在这里改动。
package dev.molifox.multiplayer.data

import dev.molifox.multiplayer.common.CoreStatus
import dev.molifox.multiplayer.common.MultiplayerPhase
import dev.molifox.multiplayer.common.MultiplayerState
import dev.molifox.multiplayer.common.PlayerKind
import dev.molifox.multiplayer.common.RoomPlayer
import dev.molifox.multiplayer.common.RoomRole
import dev.molifox.multiplayer.common.TerracottaDifficulty
import dev.molifox.multiplayer.common.TerracottaFatality
import dev.molifox.multiplayer.common.TerracottaJoinFailure
import dev.molifox.multiplayer.platform.RemoteProfile
import dev.molifox.multiplayer.platform.TerracottaSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 联机状态机；所有状态改动都经过这里，保证离开房间时字段不会残留。 */
internal class MultiplayerRoomState(
    private val logger: (message: String, error: Boolean) -> Unit,
) {
    private val mutableState = MutableStateFlow(MultiplayerState())
    val flow: StateFlow<MultiplayerState> = mutableState.asStateFlow()

    fun update(transform: (MultiplayerState) -> MultiplayerState) {
        mutableState.update(transform)
    }

    /** 回到大厅：清空房间、玩家与错误，保留核心状态。 */
    fun clearRoom(
        phase: MultiplayerPhase,
        role: RoomRole?,
    ) {
        update {
            it.copy(
                phase = phase,
                role = role,
                roomCode = null,
                joinUrl = null,
                players = emptyList(),
                difficulty = null,
                progress = null,
                error = null,
                failure = null,
                fatality = null,
            )
        }
    }

    /** 下载、启动或核心退出等会中断流程的失败，界面据此显示对应按钮。 */
    fun putFailure(
        failure: Throwable,
        phase: MultiplayerPhase,
    ) {
        if (failure is CancellationException) throw failure
        val fatality =
            when {
                failure is IllegalStateException && failure.message.orEmpty().contains("核心") ->
                    TerracottaFatality.CORE
                failure is java.io.IOException -> TerracottaFatality.NETWORK
                else -> TerracottaFatality.UNKNOWN
            }
        update {
            it.copy(
                phase = phase,
                progress = null,
                error = failure.message ?: "联机操作失败，请重试",
                fatality = fatality,
            )
        }
        logger("联机操作失败：${failure.message}", true)
    }

    /** 房间命令的前置条件失败只提示原因，不把页面留在失败态。 */
    fun reportFailure(failure: Throwable) {
        if (failure is CancellationException) throw failure
        update { it.copy(error = failure.message ?: "联机操作失败，请重试") }
        logger("联机操作失败：${failure.message}", true)
    }

    fun onCoreExit(output: String) {
        update {
            it.copy(
                phase = MultiplayerPhase.Failed,
                error = "联机核心已退出：$output",
                fatality = TerracottaFatality.CORE,
                progress = null,
            )
        }
        logger("联机核心进程已退出：$output", true)
    }

    fun applySnapshot(
        snapshot: TerracottaSnapshot,
        alive: Boolean,
        coreStatus: () -> CoreStatus,
    ) {
        when (snapshot.state) {
            "waiting" -> clearRoom(MultiplayerPhase.Waiting, null)
            "host-scanning" -> update { it.copy(phase = MultiplayerPhase.HostScanning) }
            "host-starting" -> update { it.copy(phase = MultiplayerPhase.HostStarting) }
            "host-ok" -> applyHostOk(snapshot)
            "guest-connecting" -> update { it.copy(phase = MultiplayerPhase.GuestConnecting) }
            "guest-starting" ->
                update {
                    it.copy(
                        phase = MultiplayerPhase.GuestStarting,
                        difficulty = snapshot.difficulty.toDifficulty(),
                    )
                }
            "guest-ok" -> applyGuestOk(snapshot)
            "exception" -> applyException(snapshot, coreStatus)
            else -> logger("未识别的联机核心状态：${snapshot.state}（核心是否仍在运行：$alive）", true)
        }
    }

    private fun applyHostOk(snapshot: TerracottaSnapshot) {
        update {
            it.copy(
                phase = MultiplayerPhase.HostReady,
                role = RoomRole.Host,
                roomCode = snapshot.room,
                players = snapshot.profiles.toPlayers(),
                error = null,
                failure = null,
            )
        }
    }

    private fun applyGuestOk(snapshot: TerracottaSnapshot) {
        update {
            it.copy(
                phase = MultiplayerPhase.GuestReady,
                role = RoomRole.Guest,
                joinUrl = snapshot.url,
                players = snapshot.profiles.toPlayers(),
                error = null,
                failure = null,
            )
        }
    }

    private fun applyException(
        snapshot: TerracottaSnapshot,
        coreStatus: () -> CoreStatus,
    ) {
        val failure = snapshot.exceptionType.toJoinFailure()
        val status = coreStatus()
        update {
            it.copy(
                phase = MultiplayerPhase.Failed,
                roomCode = null,
                joinUrl = null,
                players = emptyList(),
                failure = failure,
                error = failure.message(),
                fatality = null,
                core = status,
            )
        }
        logger("陶瓦核心上报异常：$failure", true)
    }
}

private fun List<RemoteProfile>.toPlayers(): List<RoomPlayer> =
    mapNotNull { profile -> profile.toPlayer() }

private fun RemoteProfile.toPlayer(): RoomPlayer? {
    val id = machineId ?: return null
    val kind =
        when (this.kind) {
            "HOST" -> PlayerKind.HOST
            "LOCAL" -> PlayerKind.LOCAL
            "GUEST" -> PlayerKind.GUEST
            else -> PlayerKind.GUEST
        }
    return RoomPlayer(id, name ?: "匿名玩家", kind)
}

private fun String?.toDifficulty(): TerracottaDifficulty =
    when (this) {
        "EASIEST" -> TerracottaDifficulty.EASIEST
        "SIMPLE" -> TerracottaDifficulty.SIMPLE
        "MEDIUM" -> TerracottaDifficulty.MEDIUM
        "TOUGH" -> TerracottaDifficulty.TOUGH
        else -> TerracottaDifficulty.UNKNOWN
    }

private fun Int?.toJoinFailure(): TerracottaJoinFailure =
    when (this) {
        EXCEPTION_PING_HOST_FAIL -> TerracottaJoinFailure.PING_HOST_FAIL
        EXCEPTION_PING_HOST_RST -> TerracottaJoinFailure.PING_HOST_RST
        EXCEPTION_GUEST_CORE_CRASH -> TerracottaJoinFailure.GUEST_ET_CRASH
        EXCEPTION_HOST_CORE_CRASH -> TerracottaJoinFailure.HOST_ET_CRASH
        EXCEPTION_PING_SERVER_RST -> TerracottaJoinFailure.PING_SERVER_RST
        EXCEPTION_INVALID_RESPONSE -> TerracottaJoinFailure.SCAFFOLDING_INVALID_RESPONSE
        else -> TerracottaJoinFailure.UNKNOWN
    }

private fun TerracottaJoinFailure.message(): String =
    when (this) {
        TerracottaJoinFailure.PING_HOST_FAIL -> "加入房间失败：房间已关闭或网络不稳定"
        TerracottaJoinFailure.PING_HOST_RST -> "房间连接断开：房间已关闭或网络不稳定"
        TerracottaJoinFailure.GUEST_ET_CRASH -> "加入房间失败：联机核心已崩溃，请重试或反馈日志"
        TerracottaJoinFailure.HOST_ET_CRASH -> "创建房间失败：联机核心已崩溃，请重试或反馈日志"
        TerracottaJoinFailure.PING_SERVER_RST -> "存档已退出，房间已关闭"
        TerracottaJoinFailure.SCAFFOLDING_INVALID_RESPONSE -> "协议错误：收到错误的响应数据，请反馈日志"
        TerracottaJoinFailure.UNKNOWN -> "联机中断，请重试"
    }

/** 陶瓦核心的异常类型编号，见其 `/state` 接口。 */
private const val EXCEPTION_PING_HOST_FAIL = 0
private const val EXCEPTION_PING_HOST_RST = 1
private const val EXCEPTION_GUEST_CORE_CRASH = 2
private const val EXCEPTION_HOST_CORE_CRASH = 3
private const val EXCEPTION_PING_SERVER_RST = 4
private const val EXCEPTION_INVALID_RESPONSE = 5
