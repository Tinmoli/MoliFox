// 应用持有联机后端；进入联机页时才创建，关闭控制器前释放联机核心进程。
package dev.molifox.desktop.platform.multiplayer

import dev.molifox.core.LauncherController
import dev.molifox.multiplayer.domain.MultiplayerBackend

class DesktopMultiplayerOwner(
    private val createBackend: () -> MultiplayerBackend,
) {
    constructor(controller: LauncherController) : this({ createDesktopMultiplayer(controller) })

    private val lock = Any()
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
                check(!closed) { "联机功能已关闭" }
                ownedBackend ?: createBackend().also { ownedBackend = it }
            }

    suspend fun close() {
        val backend =
            synchronized(lock) {
                if (closed) return
                closed = true
                ownedBackend.also { ownedBackend = null }
            }
        backend?.close()
    }
}
