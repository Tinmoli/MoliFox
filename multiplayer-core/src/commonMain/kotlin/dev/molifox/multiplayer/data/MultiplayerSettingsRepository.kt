// 联机设置的读取与保存接口；实现放在平台层，公共层只声明行为。
package dev.molifox.multiplayer.data

import dev.molifox.multiplayer.common.MultiplayerSettings

interface MultiplayerSettingsRepository {
    suspend fun load(): MultiplayerSettings

    suspend fun save(settings: MultiplayerSettings)
}
