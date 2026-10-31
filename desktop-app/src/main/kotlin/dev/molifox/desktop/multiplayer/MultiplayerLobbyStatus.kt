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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.molifox.desktop.MutedText
import dev.molifox.desktop.SecondaryButton
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
    val runtime = state.runtime
    if (runtime?.installed == true) {
        MutedText("EasyTier ${runtime.version} 已就绪（${runtime.platform}）。")
        return
    }
    if (!settings.enabled) return
    Text(MULTIPLAYER_RUNTIME_DOWNLOAD_NOTICE, style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = actions.runtime.download, enabled = !state.busy) {
        Text(if (actions.runtime.downloadFailed) "重试下载" else "下载官方 EasyTier")
    }
    // 网络慢或下载一直卡住时的兜底：让用户自己下载 zip 再导入，导入仍会校验大小与 SHA-256。
    SecondaryButton(
        onClick = actions.runtime.importArchive,
        enabled = !state.busy && !actions.runtime.importing,
    ) {
        Text(if (actions.runtime.importing) "正在安装…" else "从本地 zip 安装…")
    }
    MutedText(RUNTIME_ARCHIVE_HINT)
    if (actions.runtime.downloadFailed) {
        MutedText("上次下载没有完成，点“重试下载”再试一次；也可以从本地 zip 安装。")
    }
    if (actions.runtime.cancelVisible && state.phase == LobbyPhase.DownloadingRuntime) {
        // 下载慢或卡住时主动停止：取消不会留下未完成的安装包，也不会自动重新下载。
        TextButton(
            onClick = actions.runtime.cancelDownload,
            enabled = !actions.runtime.cancelling,
            colors =
                ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
        ) {
            Text(if (actions.runtime.cancelling) "正在取消…" else "取消下载")
        }
        MutedText("取消后不会保留未完成的安装包，可稍后重试或从本地 zip 安装。")
    }
}

/** 导入说明随 EasyTier 版本变化，所以用拼接而不是常量字符串。 */
private val RUNTIME_ARCHIVE_HINT =
    "下载一直卡住时，可以用浏览器或下载工具下载官方 EasyTier " +
        "${MultiplayerPolicy.CORE_VERSION} 的 zip，再从这里导入；导入会校验大小与 SHA-256。"

/** 只有房间相关阶段才显示退出入口；创建/加入过程中不允许打断，避免后端状态竞争。 */
@Composable
internal fun RoomLeaveAction(
    state: MultiplayerState,
    actions: MultiplayerLobbyActions,
) {
    val label =
        when (state.phase) {
            LobbyPhase.RoomOpen -> "关闭房间"
            LobbyPhase.Connected -> "退出房间"
            else -> null
        }
    label?.let { TextButton(onClick = actions.leave) { Text(it) } }
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
        PlayerKind.HOST -> "房主"
        PlayerKind.LOCAL -> "你"
        PlayerKind.GUEST -> "房客"
    }

internal fun phaseTitle(phase: LobbyPhase): String =
    when (phase) {
        LobbyPhase.Idle -> "联机大厅"
        LobbyPhase.DownloadingRuntime -> "正在下载官方 EasyTier"
        LobbyPhase.StartingRuntime -> "正在启动虚拟网络"
        LobbyPhase.DiscoveringWorld -> "正在扫描局域网世界"
        LobbyPhase.CreatingRoom -> "正在创建房间"
        LobbyPhase.RoomOpen -> "房间已开启"
        LobbyPhase.JoiningRoom -> "正在加入房间"
        LobbyPhase.Connected -> "已加入房间"
        LobbyPhase.Failed -> "联机未能完成"
    }

internal fun phaseHint(phase: LobbyPhase): String? =
    when (phase) {
        LobbyPhase.Idle -> "创建或加入房间前会先检查 EasyTier 运行时；首次使用会自动下载。"
        LobbyPhase.DownloadingRuntime ->
            "正在从官方或镜像下载 EasyTier ${MultiplayerPolicy.CORE_VERSION}（约 31 MB），" +
                "完成后自动校验并继续。"
        LobbyPhase.StartingRuntime ->
            "正在启动 EasyTier 独立进程；首次运行 Windows 可能弹出防火墙提示。"
        LobbyPhase.DiscoveringWorld -> "正在监听并探测本机 Minecraft 局域网世界，请稍候。"
        LobbyPhase.CreatingRoom -> "正在通过 Scaffolding 协议建立房间，需要能连上 EasyTier 中继。"
        LobbyPhase.RoomOpen -> "保持游戏与启动器运行；退出存档后房间会自动关闭。"
        LobbyPhase.JoiningRoom ->
            "加入依次进行：启动虚拟网络 → 等待房主上线 → 建立端口转发；" +
                "中继较慢时可能需要一到两分钟。"
        LobbyPhase.Connected -> "保持启动器运行；退出房间或关闭启动器会断开连接。"
        LobbyPhase.Failed -> null
    }
