// Scaffolding 玩家快照到界面模型的映射：房主标记 HOST，本机房客标记 LOCAL。
package dev.molifox.multiplayer.lobby

import dev.molifox.multiplayer.common.PlayerKind
import dev.molifox.multiplayer.common.RoomPlayer
import dev.molifox.multiplayer.scaffolding.ScaffoldingPlayer
import dev.molifox.multiplayer.scaffolding.ScaffoldingPlayerKind

fun List<ScaffoldingPlayer>.toRoomPlayers(localMachineId: String): List<RoomPlayer> =
    map { player ->
        val kind =
            when {
                player.kind == ScaffoldingPlayerKind.HOST -> PlayerKind.HOST
                player.machineId == localMachineId -> PlayerKind.LOCAL
                else -> PlayerKind.GUEST
            }
        RoomPlayer(player.machineId, player.name, kind)
    }
