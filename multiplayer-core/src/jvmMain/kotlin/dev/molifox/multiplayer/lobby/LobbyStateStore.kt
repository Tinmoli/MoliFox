// 联机可见状态的唯一写入口：所有阶段变更与用户可读错误都在这里落地。
package dev.molifox.multiplayer.lobby

import dev.molifox.multiplayer.common.MultiplayerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

internal class LobbyStateStore {
    private val lock = Any()
    private val mutable = MutableStateFlow(MultiplayerState())

    val state: StateFlow<MultiplayerState> = mutable.asStateFlow()

    val current: MultiplayerState
        get() = mutable.value

    /** 读改写必须串行，否则扫描线程的旧快照会覆盖房间阶段更新。 */
    fun update(transform: (MultiplayerState) -> MultiplayerState) {
        synchronized(lock) {
            mutable.value = transform(mutable.value)
        }
    }

    /** 记录可读错误并返回失败；技术细节由调用方写日志。 */
    fun fail(message: String): Result<Unit> {
        update { it.copy(error = message) }
        return Result.failure(IOException(message))
    }
}
