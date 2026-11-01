// 后端用到的纯函数：显示名回退、世界选择与列表合并，便于单测且不增加类方法数。
package dev.molifox.multiplayer.lobby

import dev.molifox.core.i18n.I18n
import dev.molifox.multiplayer.common.CoreStatus
import dev.molifox.multiplayer.common.LanWorld
import dev.molifox.multiplayer.common.LobbyPhase
import dev.molifox.multiplayer.common.MultiplayerSettings
import dev.molifox.multiplayer.easytier.EasyTierTuning
import kotlinx.coroutines.CancellationException

/** 房间内显示名：优先联机昵称，其次账户名，最后匿名。 */
internal fun displayName(
    settings: MultiplayerSettings,
    accountName: String,
): String =
    settings.nickname
        .ifBlank { accountName }
        .trim()
        .ifEmpty { ANONYMOUS_PLAYER }

/** 把界面设置里的附加开关映射成 EasyTier 参数；未知字段保持默认关闭。 */
internal fun MultiplayerSettings.toEasyTierTuning(): EasyTierTuning =
    EasyTierTuning(
        latencyFirst = latencyFirst,
        disableIpv6 = disableIpv6,
        disableSymHolePunching = disableSymHolePunching,
    )

/** 按端口选中世界；[worldPort] 为 null 时要求当前只有一个世界。 */
internal fun resolveWorld(
    worlds: List<LanWorld>,
    worldPort: Int?,
): LanWorld? = worldPort?.let { port -> worlds.firstOrNull { it.port == port } } ?: worlds.singleOrNull()

/** 世界无法选中时的用户可读原因。 */
internal fun worldSelectionMessage(worldPort: Int?): String =
    if (worldPort == null) {
        I18n.t("multiplayer.lobbyBackendSupport.1")
    } else {
        I18n.t("multiplayer.lobbyBackendSupport.2")
    }

/** 以新发现的世界为准合并，同一端口只保留最新一次探测结果。 */
internal fun mergeWorlds(
    current: List<LanWorld>,
    incoming: List<LanWorld>,
): List<LanWorld> {
    val ports = incoming.mapTo(HashSet()) { it.port }
    return (current.filterNot { it.port in ports } + incoming).sortedBy { it.port }
}

private val ANONYMOUS_PLAYER: String
    get() = I18n.t("multiplayer.lobbyBackendSupport.3")

/**
 * 下载/本地导入共用的运行时安装流程：阶段、进度、取消与错误都只在这里处理。
 *
 * [failurePrefix] 用于区分“在线下载失败”和“本地安装包失败”的可读提示。
 */
internal suspend fun performRuntimeInstall(
    store: LobbyStateStore,
    scanner: LobbyWorldScanner,
    discoveryEnabled: Boolean,
    failurePrefix: String,
    install: suspend ((Float?) -> Unit) -> CoreStatus,
): Result<Unit> {
    store.update { it.copy(phase = LobbyPhase.DownloadingRuntime, progress = null, error = null) }
    val outcome =
        runCatching {
            install { progress -> store.update { it.copy(progress = progress) } }
        }
    outcome.exceptionOrNull()?.let { failure ->
        if (failure is CancellationException) {
            // 用户点了“取消下载”：回到空闲，不保留错误提示。
            store.update { it.copy(phase = LobbyPhase.Idle, progress = null, error = null) }
            throw failure
        }
    }
    return outcome.fold(
        onSuccess = { status ->
            store.update { it.copy(phase = LobbyPhase.Idle, progress = null, runtime = status) }
            scanner.sync(discoveryEnabled)
            Result.success(Unit)
        },
        onFailure = { failure ->
            store.update {
                it.copy(
                    phase = LobbyPhase.Idle,
                    progress = null,
                    error = "$failurePrefix：${failure.message}",
                )
            }
            Result.failure(failure)
        },
    )
}
