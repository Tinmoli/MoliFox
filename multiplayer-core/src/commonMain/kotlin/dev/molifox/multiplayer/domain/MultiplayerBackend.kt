// 提供界面唯一联机接口，调用方无需了解 EasyTier 进程、Scaffolding 协议或平台网络细节。
package dev.molifox.multiplayer.domain

import dev.molifox.multiplayer.common.CoreStatus
import dev.molifox.multiplayer.common.MultiplayerSettings
import dev.molifox.multiplayer.common.MultiplayerState
import kotlinx.coroutines.flow.StateFlow

interface MultiplayerBackend {
    val state: StateFlow<MultiplayerState>
    val settings: StateFlow<MultiplayerSettings>

    /** 读取联机设置，检查运行时并开始发现本机局域网世界。 */
    suspend fun initialize(): Result<Unit>

    /** 检查本地 EasyTier 运行时是否已安装且完整。 */
    suspend fun inspectRuntime(): Result<CoreStatus>

    /** 下载并安装 EasyTier 运行时；进度通过 [state] 的 `progress` 上报。 */
    suspend fun prepareRuntime(): Result<Unit>

    /** 重新扫描本机 Minecraft 局域网世界；结果写入 [state] 的 `worlds`。 */
    suspend fun refreshWorlds(): Result<Unit>

    /** 用本机某世界创建房间；[worldPort] 为 null 时要求当前只有一个世界。 */
    suspend fun host(worldPort: Int? = null): Result<Unit>

    /** 加入房间；[roomCode] 为房主提供的 21 字符邀请码。 */
    suspend fun join(roomCode: String): Result<Unit>

    /** 关闭房间或退出房间，回到可发现世界的空闲阶段。 */
    suspend fun leave()

    /** 清空房间信息并停止运行时，用于恢复默认状态。 */
    suspend fun reset()

    suspend fun saveSettings(settings: MultiplayerSettings): Result<Unit>

    /** 关闭 EasyTier、Scaffolding 与本地转发，释放全部资源。 */
    suspend fun close()
}
