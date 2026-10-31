// 房客分组：粘贴邀请码加入房间，成功后展示本地可加入地址与进入游戏的步骤。
package dev.molifox.desktop.multiplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.molifox.desktop.CompactTextField
import dev.molifox.desktop.MutedText
import dev.molifox.desktop.PrimaryButton
import dev.molifox.multiplayer.common.ConnectionRoute
import dev.molifox.multiplayer.common.MultiplayerSettings
import dev.molifox.multiplayer.common.MultiplayerState
import dev.molifox.multiplayer.protocol.RoomCode

@Composable
internal fun GuestJoinPanel(
    state: MultiplayerState,
    settings: MultiplayerSettings,
    actions: MultiplayerLobbyActions,
) {
    var code by remember { mutableStateOf("") }
    val parsed = remember(code) { RoomCode.parse(code) }
    val ready = settings.enabled && state.runtime?.installed == true && !state.busy
    MultiplayerGroup(
        Modifier.fillMaxWidth(),
        title = "我想当房客",
        detail = "粘贴房主发来的 21 字符邀请码，空格会被去掉并自动转成大写。",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CompactTextField(
                code,
                { input -> code = normalizeInviteCode(input) },
                Modifier.fillMaxWidth(),
                label = "邀请码",
                placeholder = "U/XXXX-XXXX-XXXX-XXXX",
            )
            // 只在长度够 21 字符却仍解析失败时提示，避免刚输入一两个字符就报错。
            if (code.length >= RoomCode.CODE_LENGTH && parsed == null) {
                Text(
                    "邀请码不完整或格式不正确；标准格式为 U/ 加 16 位数字字母。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            PrimaryButton("加入房间", enabled = ready && parsed != null) {
                actions.join(code)
            }
        }
    }
}

/** 已加入房间后只需一个本地地址：广播不到时可手动添加，IPv4 地址由后端给出。 */
@Composable
internal fun LocalAddressCard(
    state: MultiplayerState,
    copy: (String) -> Unit,
) {
    MultiplayerGroup(
        Modifier.fillMaxWidth(),
        title = "进入房间",
        detail = "打开游戏 → 多人游戏 → 刷新局域网世界；也可以直接添加下面的地址。",
    ) {
        state.localAddress?.let { address ->
            CopyableValue("本地地址", address, "复制地址", copy)
        }
        state.connectionRoute?.let { route ->
            MutedText("连接方式：" + connectionRouteLabel(route))
        }
        MutedText("首次运行 EasyTier 时 Windows 可能弹出防火墙提示，启动器不会自动修改防火墙。")
    }
}

/** 粘贴文本常带空格、换行或小写字母；统一规范后再显示，也直接作为加入参数提交。 */
private fun normalizeInviteCode(text: String): String =
    text.filterNot { it.isWhitespace() }.uppercase()

/** 打洞/中继/未知三种路径的用户文案；未知时不让用户误以为走了中继。 */
internal fun connectionRouteLabel(route: ConnectionRoute): String =
    when (route) {
        ConnectionRoute.Direct -> "P2P 打洞直连"
        ConnectionRoute.Relay -> "经自建中继转发"
        ConnectionRoute.Unknown -> "暂未识别（可稍后重进房间查看）"
    }
