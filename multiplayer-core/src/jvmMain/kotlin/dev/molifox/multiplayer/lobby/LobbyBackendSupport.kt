// 后端用到的纯函数：显示名回退、世界选择与列表合并，便于单测且不增加类方法数。
package dev.molifox.multiplayer.lobby

import dev.molifox.multiplayer.common.LanWorld
import dev.molifox.multiplayer.common.MultiplayerSettings

/** 房间内显示名：优先联机昵称，其次账户名，最后匿名。 */
internal fun displayName(
    settings: MultiplayerSettings,
    accountName: String,
): String =
    settings.nickname
        .ifBlank { accountName }
        .trim()
        .ifEmpty { ANONYMOUS_PLAYER }

/** 按端口选中世界；[worldPort] 为 null 时要求当前只有一个世界。 */
internal fun resolveWorld(
    worlds: List<LanWorld>,
    worldPort: Int?,
): LanWorld? =
    worldPort?.let { port -> worlds.firstOrNull { it.port == port } } ?: worlds.singleOrNull()

/** 世界无法选中时的用户可读原因。 */
internal fun worldSelectionMessage(worldPort: Int?): String =
    if (worldPort == null) {
        "还没有发现局域网世界：请先在 Minecraft 里进入存档，按 Esc → 对局域网开放，再刷新。"
    } else {
        "选中的世界已不可用，请刷新世界列表后重试。"
    }

/** 以新发现的世界为准合并，同一端口只保留最新一次探测结果。 */
internal fun mergeWorlds(
    current: List<LanWorld>,
    incoming: List<LanWorld>,
): List<LanWorld> {
    val ports = incoming.mapTo(HashSet()) { it.port }
    return (current.filterNot { it.port in ports } + incoming).sortedBy { it.port }
}

private const val ANONYMOUS_PLAYER = "匿名玩家"
