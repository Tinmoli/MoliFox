// 房主分组：选择本机已开放的局域网世界并创建房间，房间开启后展示邀请码。
package dev.molifox.desktop.multiplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import dev.molifox.desktop.MoliTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.molifox.desktop.LocalStrings
import dev.molifox.desktop.MutedText
import dev.molifox.desktop.PrimaryButton
import dev.molifox.desktop.tr
import dev.molifox.multiplayer.common.LanWorld
import dev.molifox.multiplayer.common.MultiplayerSettings
import dev.molifox.multiplayer.common.MultiplayerState

@Composable
internal fun HostRoomPanel(
    state: MultiplayerState,
    settings: MultiplayerSettings,
    actions: MultiplayerLobbyActions,
) {
    val strings = LocalStrings.current
    // 世界列表可能整体刷新，按列表本身重建选择：默认选第一个，避免记住已消失的端口。
    var selectedPort by remember(state.worlds) { mutableStateOf(state.worlds.firstOrNull()?.port) }
    val ready = settings.enabled && state.runtime?.installed == true && !state.busy
    MultiplayerGroup(
        Modifier.fillMaxWidth(),
        title = strings.t("dialogs.mp.host.title"),
        detail = strings.t("dialogs.mp.host.detail"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WorldsHeader(state, actions)
            if (state.worlds.isEmpty()) {
                MutedText(strings.t("dialogs.mp.host.noWorlds"))
            } else {
                state.worlds.forEach { world ->
                    WorldRow(world, world.port == selectedPort) { selectedPort = world.port }
                }
            }
            PrimaryButton(strings.t("dialogs.mp.host.create"), enabled = ready && selectedPort != null) {
                actions.host(selectedPort)
            }
            MutedText(strings.t("dialogs.mp.host.createHint"))
        }
    }
}

@Composable
private fun WorldsHeader(
    state: MultiplayerState,
    actions: MultiplayerLobbyActions,
) {
    val strings = LocalStrings.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (state.worlds.isEmpty()) {
                strings.t("dialogs.mp.host.noneFound")
            } else {
                strings.t("dialogs.mp.host.worldCount", state.worlds.size)
            },
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = actions.refreshWorlds, enabled = !state.busy) {
            Text(strings.t("dialogs.mp.host.refresh"))
        }
    }
}

@Composable
private fun WorldRow(
    world: LanWorld,
    selected: Boolean,
    select: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .selectable(selected, role = Role.RadioButton, onClick = select)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(world.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            MutedText(worldDescription(world))
        }
    }
}

private fun worldDescription(world: LanWorld): String =
    if (world.version.isBlank()) {
        tr("dialogs.mp.host.port", world.port)
    } else {
        tr("dialogs.mp.host.versionPort", world.version, world.port)
    }

/** 房间开启后只展示邀请码与复制入口；玩家列表由大厅统一渲染。 */
@Composable
internal fun RoomCodeCard(
    state: MultiplayerState,
    copy: (String) -> Unit,
) {
    val strings = LocalStrings.current
    MultiplayerGroup(
        Modifier.fillMaxWidth(),
        title = strings.t("dialogs.mp.host.shareTitle"),
        detail = strings.t("dialogs.mp.host.shareDetail"),
    ) {
        state.roomCode?.let { code ->
            CopyableValue(strings.t("dialogs.mp.host.code"), code, strings.t("dialogs.mp.host.copyCode"), copy)
        }
        MutedText(strings.t("dialogs.mp.host.codeNote"))
    }
}
