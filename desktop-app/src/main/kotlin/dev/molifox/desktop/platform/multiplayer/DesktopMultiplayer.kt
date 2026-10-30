// 组装桌面联机依赖：配置放在启动器数据目录，运行数据放在 runtimes/ 下。
package dev.molifox.desktop.platform.multiplayer

import dev.molifox.core.LauncherController
import dev.molifox.multiplayer.data.FileMultiplayerSettings
import dev.molifox.multiplayer.data.JvmTerracottaBackend
import dev.molifox.multiplayer.data.TerracottaInstaller
import dev.molifox.multiplayer.domain.MultiplayerBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

fun createDesktopMultiplayer(controller: LauncherController): MultiplayerBackend {
    val dataRoot = controller.store.dataDirectory
    val log = DesktopMultiplayerLog(controller.scope, controller)
    val report: (String, Boolean) -> Unit = { message, error -> log.report(message, error) }
    return JvmTerracottaBackend(
        settingsRepository = FileMultiplayerSettings(dataRoot.resolve("config/multiplayer.json")),
        logger = report,
        playerName = { currentNickname(controller) },
        installer = TerracottaInstaller(dataRoot.resolve("runtimes"), report),
    )
}

/** 异步写联机日志，避免在核心轮询线程里同步落盘。 */
private class DesktopMultiplayerLog(
    scope: CoroutineScope,
    private val controller: LauncherController,
) {
    private data class Entry(
        val message: String,
        val error: Boolean,
    )

    private val entries = Channel<Entry>(LOG_CAPACITY)

    init {
        scope.launch(Dispatchers.IO) {
            for (entry in entries) {
                controller.store.log("[联机] ${entry.message}", error = entry.error)
            }
        }
    }

    fun report(
        message: String,
        error: Boolean,
    ) {
        entries.trySend(Entry(message.take(MAX_MESSAGE), error))
    }

    companion object {
        private const val LOG_CAPACITY = 128
        private const val MAX_MESSAGE = 8_000
    }
}

/** 房间内显示名：优先使用联机设置，空串时退回当前账户名。 */
private fun currentNickname(controller: LauncherController): String {
    val data = controller.store.data
    return data.accounts
        .find { it.id == data.activeAccountId }
        ?.name
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: "匿名玩家"
}
