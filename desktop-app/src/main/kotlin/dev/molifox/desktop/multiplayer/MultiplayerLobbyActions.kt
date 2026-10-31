// 联机大厅用到的后端操作集合：界面只通过这些入口改状态，拆分子页面时不必层层传后端。
package dev.molifox.desktop.multiplayer

import dev.molifox.multiplayer.domain.MultiplayerBackend

internal typealias ExecuteMultiplayer = (suspend () -> Result<Unit>) -> Unit

/**
 * 运行时下载与本地导入共用一组入口：两者互斥，且都需要把“上次失败/正在安装”转成按钮文案。
 */
internal class RuntimeInstallActions(
    val download: () -> Unit,
    val importArchive: () -> Unit,
    /** 本次页面生命周期内下载已失败过：按钮改成“重试下载”，不会自动重试。 */
    val downloadFailed: Boolean,
    /** 正在选择或安装本地 zip：按钮显示“正在安装…”，并防止重复点击。 */
    val importing: Boolean,
)

/** 大厅操作统一经过 [execute]：失败时由页面把可读原因展示在底部，技术细节留在后端日志。 */
internal class MultiplayerLobbyActions(
    val host: (Int?) -> Unit,
    val join: (String) -> Unit,
    val leave: () -> Unit,
    val refreshWorlds: () -> Unit,
    val runtime: RuntimeInstallActions,
)

internal fun multiplayerLobbyActions(
    backend: MultiplayerBackend,
    execute: ExecuteMultiplayer,
    runtime: RuntimeInstallActions,
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
        runtime = runtime,
    )
