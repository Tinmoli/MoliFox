// 应用持有联机后端；进入联机页时才创建，关闭控制器前释放联机核心进程。
package dev.molifox.desktop.platform.multiplayer

import dev.molifox.core.LauncherController
import dev.molifox.desktop.tr
import dev.molifox.multiplayer.domain.MultiplayerBackend
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class DesktopMultiplayerOwner(
    private val createBackend: () -> MultiplayerBackend,
) {
    constructor(controller: LauncherController) : this(desktopMultiplayerFactory(controller))

    private val lock = Any()
    private val closeMutex = Mutex()
    private var ownedBackend: MultiplayerBackend? = null
    private var closed = false

    /** 联机核心启动、房间进行中或正在加入时，控制器不应直接退出。 */
    val active: Boolean
        get() =
            synchronized(lock) {
                val state = ownedBackend?.state?.value ?: return@synchronized false
                state.busy || state.connected
            }

    val backend: MultiplayerBackend
        get() =
            synchronized(lock) {
                check(!closed) { tr("deskI.multiplayer.closed") }
                ownedBackend ?: createBackend().also { ownedBackend = it }
            }

    suspend fun close() =
        withContext(NonCancellable) {
            closeMutex.withLock {
                val backend =
                    synchronized(lock) {
                        closed = true
                        ownedBackend
                    }
                // 关闭失败仍保留所有权，最终清理可重试；并发调用等待同一次释放完成。
                backend?.close()
                synchronized(lock) { ownedBackend = null }
            }
        }
}
