// 为联机页面点击验收提供内存后端，不访问外部服务、进程或玩家数据。
package dev.molifox.desktop.multiplayer

import dev.molifox.multiplayer.common.CoreStatus
import dev.molifox.multiplayer.common.MultiplayerPhase
import dev.molifox.multiplayer.common.MultiplayerSettings
import dev.molifox.multiplayer.common.MultiplayerState
import dev.molifox.multiplayer.common.PlayerKind
import dev.molifox.multiplayer.common.RoomPlayer
import dev.molifox.multiplayer.common.RoomRole
import dev.molifox.multiplayer.domain.MultiplayerBackend
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

internal const val FIXTURE_ROOM_CODE = "U/YNZE-U61D-2206-HXRG"

internal class MultiplayerUiFixture(
    private val root: Path,
) {
    val downloads = AtomicInteger()
    private var installed = false
    private val mutableState = MutableStateFlow(MultiplayerState(core = coreStatus(false)))
    private val mutableSettings = MutableStateFlow(MultiplayerSettings())

    val backend: MultiplayerBackend =
        object : MultiplayerBackend {
            override val state: StateFlow<MultiplayerState> = mutableState.asStateFlow()
            override val settings: StateFlow<MultiplayerSettings> = mutableSettings.asStateFlow()

            override suspend fun initialize(): Result<Unit> = Result.success(Unit)

            override suspend fun inspectCore(): Result<CoreStatus> {
                val status = coreStatus(installed)
                mutableState.update { it.copy(core = status) }
                return Result.success(status)
            }

            override suspend fun prepareCore(): Result<Unit> {
                downloads.incrementAndGet()
                mutableState.update {
                    it.copy(phase = MultiplayerPhase.Downloading, progress = 0.5f, error = null)
                }
                delay(150)
                installed = true
                mutableState.update {
                    it.copy(
                        phase = MultiplayerPhase.Waiting,
                        progress = null,
                        core = coreStatus(true),
                    )
                }
                return Result.success(Unit)
            }

            override suspend fun host(): Result<Unit> {
                mutableState.update {
                    it.copy(phase = MultiplayerPhase.HostScanning, role = RoomRole.Host)
                }
                delay(80)
                mutableState.update { it.copy(phase = MultiplayerPhase.HostStarting) }
                delay(80)
                mutableState.update {
                    it.copy(
                        phase = MultiplayerPhase.HostReady,
                        roomCode = FIXTURE_ROOM_CODE,
                        players = listOf(RoomPlayer("host", "茉狐房主", PlayerKind.HOST)),
                    )
                }
                return Result.success(Unit)
            }

            override suspend fun join(roomCode: String): Result<Unit> {
                require(roomCode.startsWith("U/")) { "邀请码格式不正确" }
                mutableState.update {
                    it.copy(phase = MultiplayerPhase.GuestConnecting, role = RoomRole.Guest)
                }
                delay(80)
                mutableState.update { it.copy(phase = MultiplayerPhase.GuestStarting) }
                delay(80)
                mutableState.update {
                    it.copy(
                        phase = MultiplayerPhase.GuestReady,
                        joinUrl = "127.0.0.1:25565",
                        players = listOf(RoomPlayer("guest", "联机房客", PlayerKind.GUEST)),
                    )
                }
                return Result.success(Unit)
            }

            override suspend fun leave() {
                mutableState.update {
                    it.copy(
                        phase = MultiplayerPhase.Waiting,
                        role = null,
                        roomCode = null,
                        joinUrl = null,
                        players = emptyList(),
                    )
                }
            }

            override suspend fun reset() = leave()

            override suspend fun saveSettings(settings: MultiplayerSettings): Result<Unit> {
                mutableSettings.value = settings
                return Result.success(Unit)
            }

            override suspend fun close() = Unit
        }

    private fun coreStatus(present: Boolean): CoreStatus =
        CoreStatus(
            installed = present,
            version = "0.4.2",
            platform = "windows-x86_64",
            downloadBytes = 8_135_914,
            directory = root.resolve("runtimes/terracotta/0.4.2/windows-x86_64").toString(),
        )
}
