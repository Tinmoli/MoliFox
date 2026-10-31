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
import dev.molifox.desktop.MutedText
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
        Text("房间玩家 · ${players.size}", style = MaterialTheme.typography.titleMedium)
    }
    items(players, key = { it.machineId }) { player -> PlayerRow(player) }
}

@Composable
private fun FailureGroup(
    state: MultiplayerState,
    actions: MultiplayerLobbyActions,
) {
    MultiplayerGroup(Modifier.fillMaxWidth(), title = "失败原因") {
        Text(
            state.error ?: "出现了未知问题，请重试。",
            color = MaterialTheme.colorScheme.error,
        )
        MutedText(
            "常见原因：邀请码无效或房间已关闭、房主不在大厅、连不上 EasyTier 中继、虚拟网络超时。" +
                "修复后可在重新加入或创建房间。",
        )
        TextButton(onClick = actions.leave) { Text("返回大厅") }
    }
}

@Composable
private fun DisabledNotice() {
    MultiplayerGroup(Modifier.fillMaxWidth(), title = "联机已关闭") {
        Text("联机功能已关闭，请先在“联机设置”里开启。")
    }
}

@Composable
private fun NoticeGroup() {
    MultiplayerGroup(Modifier.fillMaxWidth(), title = "联机说明") {
        Text(MULTIPLAYER_TECHNOLOGY_NOTICE, style = MaterialTheme.typography.bodySmall)
        Text(MULTIPLAYER_NETWORK_NOTICE, style = MaterialTheme.typography.bodySmall)
        Text(MULTIPLAYER_BOUNDARY_NOTICE, style = MaterialTheme.typography.bodySmall)
        Text(MULTIPLAYER_LEGAL_NOTICE, style = MaterialTheme.typography.bodySmall)
    }
}
