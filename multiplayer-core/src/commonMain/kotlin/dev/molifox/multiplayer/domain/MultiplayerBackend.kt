// 提供界面唯一联机接口，调用方无需了解陶瓦核心、HTTP 控制接口或平台进程。
package dev.molifox.multiplayer.domain

import dev.molifox.multiplayer.common.CoreStatus
import dev.molifox.multiplayer.common.MultiplayerSettings
import dev.molifox.multiplayer.common.MultiplayerState
import kotlinx.coroutines.flow.StateFlow

interface MultiplayerBackend {
    val state: StateFlow<MultiplayerState>
    val settings: StateFlow<MultiplayerSettings>

    /** 读取联机设置并恢复上次的核心状态。 */
    suspend fun initialize(): Result<Unit>

    /** 检查本地联机核心是否已安装且完整。 */
    suspend fun inspectCore(): Result<CoreStatus>

    /** 下载并安装联机核心；进度通过 [state] 的 `progress` 上报。 */
    suspend fun prepareCore(): Result<Unit>

    /** 创建房间：开始扫描局域网世界，找到世界后由核心建立房间。 */
    suspend fun host(): Result<Unit>

    /** 加入房间；[roomCode] 为房主提供的邀请码。 */
    suspend fun join(roomCode: String): Result<Unit>

    /** 关闭房间或退出房间，回到就绪状态。 */
    suspend fun leave()

    /** 让核心回到等待状态并清空房间信息。 */
    suspend fun reset()

    suspend fun saveSettings(settings: MultiplayerSettings): Result<Unit>

    /** 关闭核心进程并释放资源。 */
    suspend fun close()
}
