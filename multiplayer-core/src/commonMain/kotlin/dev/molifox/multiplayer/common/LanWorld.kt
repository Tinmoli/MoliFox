// 本机 Minecraft 局域网世界：只描述发现结果，不引用平台 socket 或界面类型。
package dev.molifox.multiplayer.common

/** 房主在游戏里“对局域网开放”后被启动器发现的单机世界。 */
data class LanWorld(
    /** 世界（MOTD）名称，取自 Minecraft status ping，用于界面展示。 */
    val name: String,
    /** 游戏版本名；ping 失败时为空串。 */
    val version: String,
    /** 游戏在本机监听的局域网端口。 */
    val port: Int,
)
