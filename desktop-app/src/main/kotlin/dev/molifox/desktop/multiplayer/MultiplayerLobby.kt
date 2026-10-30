// 展示联机大厅：核心准备、创建/加入房间、邀请码与玩家列表，所有操作交给后端。
package dev.molifox.desktop.multiplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.molifox.desktop.CompactTextField
import dev.molifox.desktop.PrimaryButton
import dev.molifox.multiplayer.common.MultiplayerPhase
import dev.molifox.multiplayer.common.MultiplayerSettings
import dev.molifox.multiplayer.common.MultiplayerState
import dev.molifox.multiplayer.common.PlayerKind
import dev.molifox.multiplayer.common.RoomPlayer
import dev.molifox.multiplayer.common.TerracottaDifficulty
import dev.molifox.multiplayer.common.TerracottaFatality

@Composable
internal fun MultiplayerLobby(
    state: MultiplayerState,
    settings: MultiplayerSettings,
    execute: (suspend () -> Result<Unit>) -> Unit,
    host: suspend () -> Result<Unit>,
    join: suspend (String) -> Result<Unit>,
    leave: suspend () -> Unit,
    requestDownload: () -> Unit,
    copy: (String) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        item("status") { RoomStatusPanel(state, execute, leave, copy) }
        state.error?.let { message ->
            item("error") { Text(message, color = MaterialTheme.colorScheme.error) }
        }
        if (!settings.enabled) {
            item("disabled") { Text("联机功能已关闭，请先在左侧联机设置中开启。") }
        } else if (state.core?.installed == false && !state.busy) {
            item("core") {
                MultiplayerGroup(Modifier.fillMaxWidth(), title = "联机核心尚未准备") {
                    Text("联机核心只需安装一次，约 8 MiB；确认后才会开始下载。")
                    TextButton(onClick = requestDownload) { Text("准备联机核心") }
                }
            }
        }
        if (state.phase == MultiplayerPhase.Waiting || state.phase == MultiplayerPhase.Idle) {
            item("host") { HostRoomPanel(state, settings, execute, host) }
            item("join") { JoinRoomPanel(state, settings, execute, join) }
        }
        if (state.players.isNotEmpty()) {
            item("players-title") {
                Text("房间玩家 · ${state.players.size}", style = MaterialTheme.typography.titleMedium)
            }
            items(state.players, key = { it.machineId }) { player -> PlayerRow(player) }
        }
        item("notice") {
            MultiplayerGroup(title = "联机说明") {
                Text(MULTIPLAYER_TECHNOLOGY_NOTICE, style = MaterialTheme.typography.bodySmall)
                Text(MULTIPLAYER_NETWORK_NOTICE, style = MaterialTheme.typography.bodySmall)
                Text(MULTIPLAYER_LEGAL_NOTICE, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun HostRoomPanel(
    state: MultiplayerState,
    settings: MultiplayerSettings,
    execute: (suspend () -> Result<Unit>) -> Unit,
    host: suspend () -> Result<Unit>,
) {
    val ready = settings.enabled && state.core?.installed == true && !state.busy
    MultiplayerGroup(
        Modifier.fillMaxWidth(),
        title = "我想当房主",
        detail = "启动游戏并进入存档，按 Esc 选择“对局域网开放”，再创建房间。",
    ) {
        PrimaryButton("创建房间", enabled = ready) { execute { host() } }
    }
}

@Composable
private fun JoinRoomPanel(
    state: MultiplayerState,
    settings: MultiplayerSettings,
    execute: (suspend () -> Result<Unit>) -> Unit,
    join: suspend (String) -> Result<Unit>,
) {
    var code by remember { mutableStateOf("") }
    val ready = settings.enabled && state.core?.installed == true && !state.busy
    MultiplayerGroup(
        Modifier.fillMaxWidth(),
        title = "我想当房客",
        detail = "输入房主提供的邀请码加入房间。",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CompactTextField(
                code,
                { code = it },
                Modifier.fillMaxWidth(),
                label = "邀请码",
                placeholder = "U/XXXX-XXXX-XXXX-XXXX",
            )
            PrimaryButton("加入房间", enabled = ready && code.isNotBlank()) { execute { join(code) } }
        }
    }
}

@Composable
private fun RoomStatusPanel(
    state: MultiplayerState,
    execute: (suspend () -> Result<Unit>) -> Unit,
    leave: suspend () -> Unit,
    copy: (String) -> Unit,
) {
    MultiplayerGroup(Modifier.fillMaxWidth(), title = phaseText(state.phase)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.busy) {
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
            phaseHint(state.phase)?.let { hint ->
                Text(hint, style = MaterialTheme.typography.bodySmall)
            }
            state.difficulty?.takeIf { it != TerracottaDifficulty.UNKNOWN }?.let { difficulty ->
                Text(difficultyText(difficulty), style = MaterialTheme.typography.bodySmall)
            }
            if (state.phase == MultiplayerPhase.HostReady) HostReadyDetails(state, copy)
            if (state.phase == MultiplayerPhase.GuestReady) GuestReadyDetails(state)
            state.fatality?.takeIf { state.phase == MultiplayerPhase.Failed }?.let { fatality ->
                Text(fatalityText(fatality), style = MaterialTheme.typography.bodySmall)
            }
            if (state.phase != MultiplayerPhase.Waiting && state.phase != MultiplayerPhase.Idle) {
                TextButton(onClick = { execute { leave().let { Result.success(Unit) } } }) {
                    Text(
                        when (state.phase) {
                            MultiplayerPhase.HostReady -> "关闭房间"
                            MultiplayerPhase.GuestReady -> "退出房间"
                            else -> "取消"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HostReadyDetails(
    state: MultiplayerState,
    copy: (String) -> Unit,
) {
    state.roomCode?.let { code ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(code, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { copy(code) }) { Text("复制邀请码") }
        }
        Text("把邀请码发给朋友，在 MoliFox / HMCL / PCL CE 的联机功能中选择房客模式输入。")
    }
}

@Composable
private fun GuestReadyDetails(state: MultiplayerState) {
    Text("请启动游戏，进入多人游戏，双击房主的存档进入。")
    state.joinUrl?.let { address ->
        Text("备用联机地址：$address", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlayerRow(player: RoomPlayer) {
    MultiplayerGroup(Modifier.fillMaxWidth(), padding = 12) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(player.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when (player.kind) {
                        PlayerKind.HOST -> "房主"
                        PlayerKind.LOCAL -> "你"
                        PlayerKind.GUEST -> "房客"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun phaseText(phase: MultiplayerPhase): String =
    when (phase) {
        MultiplayerPhase.Idle -> "联机大厅"
        MultiplayerPhase.Downloading -> "正在下载联机核心"
        MultiplayerPhase.Launching -> "正在启动联机核心"
        MultiplayerPhase.Waiting -> "联机核心已就绪"
        MultiplayerPhase.HostScanning -> "正在扫描局域网世界"
        MultiplayerPhase.HostStarting -> "正在创建房间"
        MultiplayerPhase.HostReady -> "已启动房间"
        MultiplayerPhase.GuestConnecting -> "正在核对邀请码"
        MultiplayerPhase.GuestStarting -> "正在加入房间"
        MultiplayerPhase.GuestReady -> "已加入房间"
        MultiplayerPhase.Failed -> "联机未能完成"
    }

private fun phaseHint(phase: MultiplayerPhase): String? =
    when (phase) {
        MultiplayerPhase.Idle -> "创建或加入房间前会先准备联机核心；首次使用需要下载。"
        MultiplayerPhase.HostScanning ->
            "请启动游戏并进入存档，按 Esc 选择“对局域网开放”；扫描到世界后房间会自动创建。"
        MultiplayerPhase.GuestReady -> "保持启动器运行；退出房间或关闭启动器会断开连接。"
        MultiplayerPhase.HostReady -> "保持游戏与启动器运行；退出存档后房间会自动关闭。"
        else -> null
    }

private fun difficultyText(difficulty: TerracottaDifficulty): String =
    when (difficulty) {
        TerracottaDifficulty.EASIEST -> "当前网络状态极好，连接应该很快建立。"
        TerracottaDifficulty.SIMPLE -> "当前网络状态较好，建立连接需要一点时间。"
        TerracottaDifficulty.MEDIUM -> "当前网络状态中等，已启用备用线路，连接可能稍慢。"
        TerracottaDifficulty.TOUGH -> "当前网络状态较差，已启用备用线路，连接可能失败。"
        TerracottaDifficulty.UNKNOWN -> ""
    }

private fun fatalityText(fatality: TerracottaFatality): String =
    when (fatality) {
        TerracottaFatality.NETWORK -> "网络不可用，请检查网络后重试。"
        TerracottaFatality.INSTALL -> "联机核心安装失败，请检查磁盘空间后重试。"
        TerracottaFatality.CORE -> "联机核心异常退出，请重试。"
        TerracottaFatality.UNKNOWN -> "发生了未知错误，请重试。"
    }
