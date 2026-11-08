// 服务端玩家登记表：维护连接与玩家对应关系、lastSeen 超时清理，并发布不可变玩家列表。
package dev.molifox.multiplayer.scaffolding

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Socket

internal class ScaffoldingPlayerRegistry(
    hostProfile: ScaffoldingPlayer?,
    private val playerTimeoutMillis: Long,
    private val clock: () -> Long,
) {
    private val stateLock = Any()
    private val guests = LinkedHashMap<String, GuestEntry>()
    private val connectionGuests = HashMap<Socket, String>()
    private val host = hostProfile?.copy(kind = ScaffoldingPlayerKind.HOST)
    private val mutablePlayers = MutableStateFlow(hostPlayers())

    val players: StateFlow<List<ScaffoldingPlayer>> = mutablePlayers.asStateFlow()

    fun register(
        socket: Socket,
        player: ScaffoldingPlayer,
    ) {
        val guest = player.copy(kind = ScaffoldingPlayerKind.GUEST)
        synchronized(stateLock) {
            connectionGuests[socket] = guest.machineId
            guests[guest.machineId] = GuestEntry(guest, clock())
            publishLocked()
        }
    }

    fun release(socket: Socket) {
        synchronized(stateLock) {
            val machineId = connectionGuests.remove(socket)
            val stillConnected = machineId != null && machineId in connectionGuests.values
            if (machineId != null && !stillConnected) {
                guests.remove(machineId)
                publishLocked()
            }
        }
    }

    fun sweep(now: Long) {
        synchronized(stateLock) {
            val expired =
                guests.values.removeAll { entry ->
                    now - entry.lastSeenMillis >= playerTimeoutMillis
                }
            if (expired) {
                publishLocked()
            }
        }
    }

    private fun hostPlayers(): List<ScaffoldingPlayer> = host?.let { listOf(it) }.orEmpty()

    private fun publishLocked() {
        mutablePlayers.value =
            buildList {
                host?.let { player -> add(player) }
                guests.values.forEach { entry -> add(entry.player) }
            }
    }

    private data class GuestEntry(
        val player: ScaffoldingPlayer,
        val lastSeenMillis: Long,
    )
}
