// 联机大厅状态面板的子块：阶段标题、进度、运行时状态、玩家行与关闭房间操作。
package dev.molifox.desktop.multiplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.molifox.desktop.LocalStrings
import dev.molifox.desktop.MoliTextButton
import dev.molifox.desktop.MutedText
import dev.molifox.desktop.SecondaryButton
import dev.molifox.desktop.tr
import dev.molifox.multiplayer.common.LobbyPhase
import dev.molifox.multiplayer.common.MultiplayerPolicy
import dev.molifox.multiplayer.common.MultiplayerSettings
import dev.molifox.multiplayer.common.MultiplayerState
import dev.molifox.multiplayer.common.PlayerKind
import dev.molifox.multiplayer.common.RoomPlayer

@Composable
internal fun RoomStatusPanel(
    state: MultiplayerState,
    settings: MultiplayerSettings,
    actions: MultiplayerLobbyActions,
) {
    MultiplayerGroup(Modifier.fillMaxWidth(), title = phaseTitle(state.phase)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RoomProgress(state)
            phaseHint(state.phase)?.let { hint ->
                MutedText(hint)
            }
            RoomRuntimeStatus(state, settings, actions)
            RoomLeaveAction(state, actions)
        }
    }
}

/** 忙碌的步骤显示进度条；下载时 [MultiplayerState.progress] 已知，其余步骤为不确定进度。 */
@Composable
internal fun RoomProgress(state: MultiplayerState) {
    if (!state.busy) return
    val progress = state.progress
    if (progress == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(
            progress = { progress },
            drawStopIndicator = {},
            modifier = Modifier.fillMaxWidth(),
        )
        Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
    }
}

/** 缺少运行时时给出体积与校验方式，并保留下载/本地导入两个入口；下载失败后改成“重试下载”。 */
@Composable
private fun RoomRuntimeStatus(
    state: MultiplayerState,
    settings: MultiplayerSettings,
    actions: MultiplayerLobbyActions,
) {
    val strings = LocalStrings.current
    val runtime = state.runtime
    if (runtime?.installed == true) {
        MutedText(strings.t("dialogs.mp.status.ready", runtime.version, runtime.platform))
        return
    }
    if (!settings.enabled) return
    Text(multiplayerRuntimeDownloadNotice(), style = MaterialTheme.typography.bodySmall)
    MoliTextButton(onClick = actions.runtime.download, enabled = !state.busy) {
        Text(
            if (actions.runtime.downloadFailed) {
                strings.t("dialogs.mp.status.retryDownload")
            } else {
                strings.t("dialogs.mp.status.download")
            },
        )
    }
    // 网络慢或下载一直卡住时的兜底：让用户自己下载 zip 再导入，导入仍会校验大小与 SHA-256。
    SecondaryButton(
        onClick = actions.runtime.importArchive,
        enabled = !state.busy && !actions.runtime.importing,
    ) {
        Text(
            if (actions.runtime.importing) {
                strings.t("dialogs.mp.status.installing")
            } else {
                strings.t("dialogs.mp.status.importZip")
            },
        )
    }
    MutedText(runtimeArchiveHint())
    if (actions.runtime.downloadFailed) {
        MutedText(strings.t("dialogs.mp.status.downloadIncomplete"))
    }
    if (actions.runtime.cancelVisible && state.phase == LobbyPhase.DownloadingRuntime) {
        // 下载慢或卡住时主动停止：取消不会留下未完成的安装包，也不会自动重新下载。
        MoliTextButton(
            onClick = actions.runtime.cancelDownload,
            enabled = !actions.runtime.cancelling,
            colors =
                ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
        ) {
            Text(
                if (actions.runtime.cancelling) {
                    strings.t("dialogs.mp.status.cancelling")
                } else {
                    strings.t("dialogs.mp.status.cancelDownload")
                },
            )
        }
        MutedText(strings.t("dialogs.mp.status.cancelHint"))
    }
}

/** 导入说明随 EasyTier 版本变化，所以按版本渲染而不是写成常量。 */
private fun runtimeArchiveHint(): String = tr("dialogs.mp.status.zipHint", MultiplayerPolicy.CORE_VERSION)

/** 只有房间相关阶段才显示退出入口；创建/加入过程中不允许打断，避免后端状态竞争。 */
@Composable
internal fun RoomLeaveAction(
    state: MultiplayerState,
    actions: MultiplayerLobbyActions,
) {
    val strings = LocalStrings.current
    val label =
        when (state.phase) {
            LobbyPhase.RoomOpen -> strings.t("dialogs.mp.status.closeRoom")
            LobbyPhase.Connected -> strings.t("dialogs.mp.status.leaveRoom")
            else -> null
        }
    label?.let { MoliTextButton(onClick = actions.leave) { Text(it) } }
}

@Composable
internal fun PlayerRow(player: RoomPlayer) {
    MultiplayerGroup(Modifier.fillMaxWidth(), padding = 12) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(player.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                MutedText(playerKindLabel(player.kind))
            }
        }
    }
}

internal fun playerKindLabel(kind: PlayerKind): String =
    when (kind) {
        PlayerKind.HOST -> tr("dialogs.mp.status.host")
        PlayerKind.LOCAL -> tr("dialogs.mp.status.you")
        PlayerKind.GUEST -> tr("dialogs.mp.status.guest")
    }

internal fun phaseTitle(phase: LobbyPhase): String =
    when (phase) {
        LobbyPhase.Idle -> tr("dialogs.mp.tab.lobby")
        LobbyPhase.DownloadingRuntime -> tr("dialogs.mp.status.downloading")
        LobbyPhase.StartingRuntime -> tr("dialogs.mp.status.starting")
        LobbyPhase.DiscoveringWorld -> tr("dialogs.mp.status.discovering")
        LobbyPhase.CreatingRoom -> tr("dialogs.mp.status.creating")
        LobbyPhase.RoomOpen -> tr("dialogs.mp.status.open")
        LobbyPhase.JoiningRoom -> tr("dialogs.mp.status.joining")
        LobbyPhase.Connected -> tr("dialogs.mp.status.connected")
        LobbyPhase.Failed -> tr("dialogs.mp.status.failed")
    }

internal fun phaseHint(phase: LobbyPhase): String? =
    when (phase) {
        LobbyPhase.Idle -> tr("dialogs.mp.status.idleHint")
        LobbyPhase.DownloadingRuntime ->
            tr("dialogs.mp.status.downloadingHint", MultiplayerPolicy.CORE_VERSION)
        LobbyPhase.StartingRuntime -> tr("dialogs.mp.status.startingHint")
        LobbyPhase.DiscoveringWorld -> tr("dialogs.mp.status.discoveringHint")
        LobbyPhase.CreatingRoom -> tr("dialogs.mp.status.creatingHint")
        LobbyPhase.RoomOpen -> tr("dialogs.mp.status.openHint")
        LobbyPhase.JoiningRoom -> tr("dialogs.mp.status.joiningHint")
        LobbyPhase.Connected -> tr("dialogs.mp.status.connectedHint")
        LobbyPhase.Failed -> null
    }
