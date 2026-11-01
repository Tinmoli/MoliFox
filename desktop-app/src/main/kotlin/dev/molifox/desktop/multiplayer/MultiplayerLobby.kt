// 展示联机大厅：按房间阶段切换房主/房客分组，玩家列表用 machineId 作为稳定 key。
package dev.molifox.desktop.multiplayer

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.molifox.desktop.LocalStrings
import dev.molifox.desktop.MutedText
import dev.molifox.desktop.tr
import dev.molifox.multiplayer.common.LobbyPhase
import dev.molifox.multiplayer.common.MultiplayerSettings
import dev.molifox.multiplayer.common.MultiplayerState
import dev.molifox.multiplayer.common.RoomPlayer

@Composable
internal fun MultiplayerLobby(
    state: MultiplayerState,
    settings: MultiplayerSettings,
    actions: MultiplayerLobbyActions,
    copy: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item("status") { RoomStatusPanel(state, settings, actions) }
        if (state.phase == LobbyPhase.Failed) {
            item("failure") { FailureGroup(state, actions) }
        }
        if (!settings.enabled) {
            item("disabled") { DisabledNotice() }
        }
        if (lobbyEntriesVisible(state)) {
            item("host") { HostRoomPanel(state, settings, actions) }
            item("guest") { GuestJoinPanel(state, settings, actions) }
        }
        if (state.phase == LobbyPhase.RoomOpen) {
            item("room") { RoomCodeCard(state, copy) }
        }
        if (state.phase == LobbyPhase.Connected) {
            item("address") { LocalAddressCard(state, copy) }
        }
        if (state.players.isNotEmpty()) playerItems(state.players)
        item("notice") { NoticeGroup() }
    }
}

/** 空闲、失败或正在发现世界时保留创建/加入入口；房间阶段只保留房间内操作。 */
private fun lobbyEntriesVisible(state: MultiplayerState): Boolean =
    state.phase in
        setOf(
            LobbyPhase.Idle,
            LobbyPhase.DiscoveringWorld,
            LobbyPhase.Failed,
        )

private fun LazyListScope.playerItems(players: List<RoomPlayer>) {
    item("players-title") {
        Text(tr("dialogs.mp.lobby.players", players.size), style = MaterialTheme.typography.titleMedium)
    }
    items(players, key = { it.machineId }) { player -> PlayerRow(player) }
}

@Composable
private fun FailureGroup(
    state: MultiplayerState,
    actions: MultiplayerLobbyActions,
) {
    val strings = LocalStrings.current
    MultiplayerGroup(Modifier.fillMaxWidth(), title = strings.t("dialogs.mp.lobby.failureTitle")) {
        Text(
            state.error ?: strings.t("dialogs.mp.lobby.unknownError"),
            color = MaterialTheme.colorScheme.error,
        )
        MutedText(
            strings.t("dialogs.mp.lobby.failureHint"),
        )
        TextButton(onClick = actions.leave) { Text(strings.t("dialogs.mp.lobby.backToLobby")) }
    }
}

@Composable
private fun DisabledNotice() {
    val strings = LocalStrings.current
    MultiplayerGroup(Modifier.fillMaxWidth(), title = strings.t("dialogs.mp.lobby.disabledTitle")) {
        Text(strings.t("dialogs.mp.lobby.disabledHint"))
    }
}

@Composable
private fun NoticeGroup() {
    val strings = LocalStrings.current
    MultiplayerGroup(Modifier.fillMaxWidth(), title = strings.t("dialogs.mp.lobby.policyTitle")) {
        Text(multiplayerTechnologyNotice(), style = MaterialTheme.typography.bodySmall)
        Text(multiplayerNetworkNotice(), style = MaterialTheme.typography.bodySmall)
        Text(multiplayerBoundaryNotice(), style = MaterialTheme.typography.bodySmall)
        Text(multiplayerLegalNotice(), style = MaterialTheme.typography.bodySmall)
    }
}
