// 管理陶瓦核心进程：启动、复用、轮询状态与退出处理，后端只负责业务流程。
package dev.molifox.multiplayer.data

import dev.molifox.multiplayer.common.CoreStatus
import dev.molifox.multiplayer.common.MultiplayerPhase
import dev.molifox.multiplayer.common.TerracottaFatality
import dev.molifox.multiplayer.platform.TerracottaControl
import dev.molifox.multiplayer.platform.TerracottaCore
import dev.molifox.multiplayer.platform.TerracottaSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** 核心进程与状态轮询；后端在房间操作前确保它已启动。 */
internal class TerracottaCoreRunner(
    private val scope: CoroutineScope,
    private val logger: (message: String, error: Boolean) -> Unit,
    private val state: MultiplayerRoomState,
    private val coreStatus: () -> CoreStatus,
    private val coreFactory: (Path) -> TerracottaCore,
) {
    private val launchMutex = Mutex()
    private var process: TerracottaCore? = null
    private var pollJob: Job? = null
    private var lastIndex = -1
    private var pollFailures = 0

    fun api(): TerracottaControl? = process?.takeIf(TerracottaCore::isAlive)?.api

    suspend fun ensureRunning(executable: Path) {
        launchMutex.withLock {
            val existing = process
            if (existing != null && existing.isAlive() && existing.api != null) return
            withContext(Dispatchers.IO) { existing?.stop() }
            val created = coreFactory(executable)
            val api = withContext(Dispatchers.IO) { created.start() }
            process = created
            lastIndex = -1
            pollFailures = 0
            startPolling(created, api)
        }
    }

    suspend fun idle() {
        runCatching { api()?.idle() }
            .onFailure { logger("请求联机核心退出房间失败：${it.message}", true) }
    }

    suspend fun stop() {
        pollJob?.cancel()
        pollJob = null
        val current = process
        process = null
        withContext(Dispatchers.IO) { current?.stop() }
    }

    private fun startPolling(
        core: TerracottaCore,
        api: TerracottaControl,
    ) {
        pollJob?.cancel()
        pollJob =
            scope.launch {
                while (isActive) {
                    if (!pollStep(core, api)) return@launch
                }
            }
    }

    /** 轮询一次；返回 false 表示停止轮询（核心退出或接口持续不可用）。 */
    private suspend fun pollStep(
        core: TerracottaCore,
        api: TerracottaControl,
    ): Boolean {
        delay(POLL_INTERVAL_MILLIS)
        var stop = false
        if (!core.isAlive()) {
            state.onCoreExit(core.recentOutput())
            stop = true
        } else {
            val snapshot = snapshotOrNull(api)
            if (snapshot == null) {
                stop = !canContinueAfterFailure()
            } else {
                consume(snapshot, core)
            }
        }
        return !stop
    }

    private fun consume(
        snapshot: TerracottaSnapshot,
        core: TerracottaCore,
    ) {
        pollFailures = 0
        if (snapshot.index <= lastIndex) return
        lastIndex = snapshot.index
        state.applySnapshot(snapshot, core.isAlive(), coreStatus)
    }

    private fun snapshotOrNull(api: TerracottaControl): TerracottaSnapshot? =
        runCatching { api.snapshot() }
            .onFailure { pollFailures++ }
            .getOrNull()

    private fun canContinueAfterFailure(): Boolean {
        if (pollFailures < MAX_POLL_FAILURES) return true
        state.update {
            it.copy(
                phase = MultiplayerPhase.Failed,
                error = "无法与联机核心通讯，请重试。",
                fatality = TerracottaFatality.CORE,
            )
        }
        return false
    }

    private companion object {
        private const val POLL_INTERVAL_MILLIS = 500L
        private const val MAX_POLL_FAILURES = 6
    }
}
