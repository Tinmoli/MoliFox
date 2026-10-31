// 联机大厅用到的后端操作集合：界面只通过这些入口改状态，拆分子页面时不必层层传后端。
package dev.molifox.desktop.multiplayer

import dev.molifox.multiplayer.domain.MultiplayerBackend

internal typealias ExecuteMultiplayer = (suspend () -> Result<Unit>) -> Unit

/** 大厅操作统一经过 [execute]：失败时由页面把可读原因展示在底部，技术细节留在后端日志。 */
internal class MultiplayerLobbyActions(
    val host: (Int?) -> Unit,
    val join: (String) -> Unit,
    val leave: () -> Unit,
    val refreshWorlds: () -> Unit,
    val prepareRuntime: () -> Unit,
)

internal fun multiplayerLobbyActions(
    backend: MultiplayerBackend,
    execute: ExecuteMultiplayer,
): MultiplayerLobbyActions =
    MultiplayerLobbyActions(
        host = { port -> execute { backend.host(port) } },
        join = { code -> execute { backend.join(code) } },
        leave = {
            execute {
                backend.leave()
                Result.success(Unit)
            }
        },
        refreshWorlds = { execute { backend.refreshWorlds() } },
        prepareRuntime = { execute { backend.prepareRuntime() } },
    )
