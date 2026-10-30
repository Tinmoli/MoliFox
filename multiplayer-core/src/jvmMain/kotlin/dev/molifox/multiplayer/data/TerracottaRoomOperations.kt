// 房间命令的前置检查与执行：确保核心在运行，再让核心扫描或加入房间。
package dev.molifox.multiplayer.data

import dev.molifox.multiplayer.common.MultiplayerPhase
import dev.molifox.multiplayer.common.MultiplayerPolicy
import dev.molifox.multiplayer.common.MultiplayerSettings
import dev.molifox.multiplayer.common.RoomRole
import dev.molifox.multiplayer.platform.TerracottaControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class TerracottaRoomOperations(
    private val installer: TerracottaInstaller,
    private val runner: TerracottaCoreRunner,
    private val room: MultiplayerRoomState,
    private val settings: () -> MultiplayerSettings,
    private val acceptedTerms: () -> String,
    private val playerName: () -> String,
    private val nodeSource: () -> List<String>,
) {
    suspend fun host() {
        val api = requireReady()
        room.clearRoom(MultiplayerPhase.HostScanning, RoomRole.Host)
        api.startScanning(playerDisplayName(), nodeSource())
    }

    suspend fun join(roomCode: String) {
        val code = roomCode.trim()
        require(code.isNotEmpty()) { "请填写房主提供的邀请码" }
        val api = requireReady()
        room.clearRoom(MultiplayerPhase.GuestConnecting, RoomRole.Guest)
        api.startGuesting(code, playerDisplayName(), nodeSource())
    }

    suspend fun leave() {
        runner.idle()
        room.clearRoom(MultiplayerPhase.Waiting, null)
    }

    /** 启动核心并等待控制接口可用；已启动时直接复用。 */
    suspend fun ensureCore() {
        val status = withContext(Dispatchers.IO) { installer.inspect() }
        check(status.installed) { "联机核心尚未安装，请先下载联机组件" }
        if (room.flow.value.phase != MultiplayerPhase.Downloading) {
            room.update {
                it.copy(phase = MultiplayerPhase.Launching, error = null, fatality = null)
            }
        }
        withContext(Dispatchers.IO) { runner.ensureRunning(installer.executable()) }
        val refreshed = withContext(Dispatchers.IO) { installer.inspect() }
        room.update { it.copy(phase = MultiplayerPhase.Waiting, core = refreshed, progress = null) }
    }

    private suspend fun requireReady(): TerracottaControl {
        check(settings().enabled) { "联机功能已关闭，请先在联机设置中开启" }
        check(acceptedTerms() == MultiplayerPolicy.TERMS_REVISION) { "请先阅读并确认联机说明" }
        check(room.flow.value.phase in IDLE_PHASES) { "请先退出当前房间" }
        val status = withContext(Dispatchers.IO) { installer.inspect() }
        room.update { it.copy(core = status) }
        check(status.installed) { "联机核心尚未安装，请先下载联机组件" }
        runner.api()?.let { return it }
        ensureCore()
        return checkNotNull(runner.api()) { "联机核心未就绪，请稍后重试" }
    }

    private fun playerDisplayName(): String =
        settings()
            .nickname
            .trim()
            .ifBlank { playerName() }
            .trim()
            .ifBlank { "匿名玩家" }

    private companion object {
        val IDLE_PHASES =
            setOf(
                MultiplayerPhase.Waiting,
                MultiplayerPhase.Failed,
                MultiplayerPhase.Idle,
            )
    }
}
