// 验证联机后端按需创建、跨页面复用及关闭时只释放一次，不访问文件和网络。
package dev.molifox.desktop.platform.multiplayer

import dev.molifox.multiplayer.common.CoreStatus
import dev.molifox.multiplayer.common.LobbyPhase
import dev.molifox.multiplayer.common.MultiplayerSettings
import dev.molifox.multiplayer.common.MultiplayerState
import dev.molifox.multiplayer.domain.MultiplayerBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class DesktopMultiplayerOwnerTest {
    @Test
    fun closingUnusedOwnerDoesNotInitializeBackend() =
        runBlocking {
            var creations = 0
            val owner =
                DesktopMultiplayerOwner {
                    creations++
                    TrackingBackend()
                }
            owner.close()
            assertEquals(0, creations)
            assertFailsWith<IllegalStateException> { owner.backend }
        }

    @Test
    fun pageReentryKeepsBackendAndCloseReleasesItOnce() =
        runBlocking {
            val backend = TrackingBackend()
            var creations = 0
            val owner =
                DesktopMultiplayerOwner {
                    creations++
                    backend
                }
            assertSame(backend, owner.backend)
            assertSame(backend, owner.backend)
            assertEquals(1, creations)
            owner.close()
            owner.close()
            assertEquals(1, backend.closes)
            assertFailsWith<IllegalStateException> { owner.backend }
        }

    @Test
    fun activeReflectsBusyOrConnectedPhases() =
        runBlocking {
            val backend = TrackingBackend()
            val owner = DesktopMultiplayerOwner { backend }
            assertSame(backend, owner.backend)
            assertEquals(false, owner.active)
            backend.state.value =
                backend.state.value.copy(phase = LobbyPhase.RoomOpen)
            assertEquals(true, owner.active)
            backend.state.value =
                backend.state.value.copy(phase = LobbyPhase.Idle)
            assertEquals(false, owner.active)
        }
}

private class TrackingBackend : MultiplayerBackend {
    override val state = MutableStateFlow(MultiplayerState())
    override val settings = MutableStateFlow(MultiplayerSettings())
    var closes = 0

    override suspend fun initialize() = Result.success(Unit)

    override suspend fun inspectRuntime(): Result<CoreStatus> =
        Result.failure(IllegalStateException("unused"))

    override suspend fun prepareRuntime() = Result.success(Unit)

    override suspend fun refreshWorlds() = Result.success(Unit)

    override suspend fun host(worldPort: Int?) = Result.success(Unit)

    override suspend fun join(roomCode: String) = Result.success(Unit)

    override suspend fun leave() = Unit

    override suspend fun reset() = Unit

    override suspend fun saveSettings(settings: MultiplayerSettings) = Result.success(Unit)

    override suspend fun close() {
        closes++
    }
}
