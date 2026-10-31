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
import androidx.compose.material3.TextButton
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
import dev.molifox.desktop.MutedText
import dev.molifox.desktop.PrimaryButton
import dev.molifox.multiplayer.common.LanWorld
import dev.molifox.multiplayer.common.MultiplayerSettings
import dev.molifox.multiplayer.common.MultiplayerState

@Composable
internal fun HostRoomPanel(
    state: MultiplayerState,
    settings: MultiplayerSettings,
    actions: MultiplayerLobbyActions,
) {
    // 世界列表可能整体刷新，按列表本身重建选择：默认选第一个，避免记住已消失的端口。
    var selectedPort by remember(state.worlds) { mutableStateOf(state.worlds.firstOrNull()?.port) }
    val ready = settings.enabled && state.runtime?.installed == true && !state.busy
    MultiplayerGroup(
        Modifier.fillMaxWidth(),
        title = "我想当房主",
        detail = "先在 Minecraft 里进入存档，按 Esc → 对局域网开放，再回到这里刷新。",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WorldsHeader(state, actions)
            if (state.worlds.isEmpty()) {
                MutedText("还没有发现局域网世界；开放后点“刷新世界”。")
            } else {
                state.worlds.forEach { world ->
                    WorldRow(world, world.port == selectedPort) { selectedPort = world.port }
                }
            }
            PrimaryButton("创建房间", enabled = ready && selectedPort != null) {
                actions.host(selectedPort)
            }
            MutedText("房间需要房主保持游戏与启动器运行，并要求能连上内置中继完成打洞。")
        }
    }
}

@Composable
private fun WorldsHeader(
    state: MultiplayerState,
    actions: MultiplayerLobbyActions,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (state.worlds.isEmpty()) {
                "未发现局域网世界"
            } else {
                "发现 ${state.worlds.size} 个局域网世界"
            },
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = actions.refreshWorlds, enabled = !state.busy) { Text("刷新世界") }
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
        "端口 ${world.port}"
    } else {
        "${world.version} · 端口 ${world.port}"
    }

/** 房间开启后只展示邀请码与复制入口；玩家列表由大厅统一渲染。 */
@Composable
internal fun RoomCodeCard(
    state: MultiplayerState,
    copy: (String) -> Unit,
) {
    MultiplayerGroup(
        Modifier.fillMaxWidth(),
        title = "分享给朋友",
        detail = "让房客在联机大厅输入邀请码；邀请码包含网络密钥，等同于房间口令。",
    ) {
        state.roomCode?.let { code ->
            CopyableValue("邀请码", code, "复制邀请码", copy)
        }
        MutedText("邀请码仅适用于 MoliFox 启动器：网络名已改为 MoliFox 前缀，PCL CE / HMCL 等其它启动器无法加入；建议双方使用相同版本。")
    }
}
