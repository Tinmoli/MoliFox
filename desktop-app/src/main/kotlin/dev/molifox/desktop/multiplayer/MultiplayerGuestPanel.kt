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
import dev.molifox.desktop.LocalStrings
import dev.molifox.desktop.MutedText
import dev.molifox.desktop.PrimaryButton
import dev.molifox.desktop.tr
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
    val strings = LocalStrings.current
    var code by remember { mutableStateOf("") }
    val parsed = remember(code) { RoomCode.parse(code) }
    val ready = settings.enabled && state.runtime?.installed == true && !state.busy
    MultiplayerGroup(
        Modifier.fillMaxWidth(),
        title = strings.t("dialogs.mp.guest.title"),
        detail = strings.t("dialogs.mp.guest.detail"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CompactTextField(
                code,
                { input -> code = normalizeInviteCode(input) },
                Modifier.fillMaxWidth(),
                label = strings.t("dialogs.mp.host.code"),
                placeholder = "U/XXXX-XXXX-XXXX-XXXX",
            )
            // 只在长度够 21 字符却仍解析失败时提示，避免刚输入一两个字符就报错。
            if (code.length >= RoomCode.CODE_LENGTH && parsed == null) {
                Text(
                    strings.t("dialogs.mp.guest.invalidCode"),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            PrimaryButton(strings.t("dialogs.mp.guest.join"), enabled = ready && parsed != null) {
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
    val strings = LocalStrings.current
    MultiplayerGroup(
        Modifier.fillMaxWidth(),
        title = strings.t("dialogs.mp.guest.enterTitle"),
        detail = strings.t("dialogs.mp.guest.enterDetail"),
    ) {
        state.localAddress?.let { address ->
            CopyableValue(
                strings.t("dialogs.mp.guest.address"),
                address,
                strings.t("dialogs.mp.guest.copyAddress"),
                copy,
            )
        }
        state.connectionRoute?.let { route ->
            MutedText(strings.t("dialogs.mp.guest.routeLabel") + connectionRouteLabel(route))
        }
        MutedText(strings.t("dialogs.mp.guest.firewallNote"))
    }
}

/** 粘贴文本常带空格、换行或小写字母；统一规范后再显示，也直接作为加入参数提交。 */
private fun normalizeInviteCode(text: String): String = text.filterNot { it.isWhitespace() }.uppercase()

/** 打洞/中继/未知三种路径的用户文案；未知时不让用户误以为走了中继。 */
internal fun connectionRouteLabel(route: ConnectionRoute): String =
    when (route) {
        ConnectionRoute.Direct -> tr("dialogs.mp.guest.route.direct")
        ConnectionRoute.Relay -> tr("dialogs.mp.guest.route.relay")
        ConnectionRoute.Unknown -> tr("dialogs.mp.guest.route.unknown")
    }
