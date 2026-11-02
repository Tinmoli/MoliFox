// 展示联机说明；勾选后点击“同意并开始下载”会保存同意并立即开始下载 EasyTier 运行时。
package dev.molifox.desktop.multiplayer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.molifox.desktop.LocalStrings
import dev.molifox.desktop.tr
import dev.molifox.multiplayer.common.MultiplayerPolicy

@Composable
internal fun MultiplayerTermsDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val strings = LocalStrings.current
    var checked by remember { mutableStateOf(false) }
    AlertDialog(
        properties =
            androidx.compose.ui.window
                .DialogProperties(dismissOnClickOutside = false),
        onDismissRequest = onDecline,
        title = { Text(strings.t("dialogs.mp.consent.startTitle")) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(multiplayerTechnologyNotice())
                Text(multiplayerNetworkNotice(), Modifier.padding(top = 12.dp))
                Text(strings.t("dialogs.mp.consent.intro"), Modifier.padding(top = 12.dp))
                Text(runtimeConsent(), Modifier.padding(top = 12.dp))
                Text(firewallConsent(), Modifier.padding(top = 12.dp))
                Text(multiplayerLegalNotice(), Modifier.padding(top = 12.dp))
                Row(
                    Modifier
                        .toggleable(checked, role = Role.Checkbox, onValueChange = { checked = it })
                        .semantics(mergeDescendants = true) {
                            contentDescription = strings.t("dialogs.mp.consent.ack")
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Text(strings.t("dialogs.mp.consent.ack"), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept, enabled = checked) { Text(strings.t("dialogs.mp.consent.accept")) }
        },
        dismissButton = { TextButton(onClick = onDecline) { Text(strings.t("dialogs.mp.consent.decline")) } },
    )
}

/** 下载与许可说明：只承诺启动器确实会做的事，不暗示安装驱动或提权。 */
private fun runtimeConsent(): String = tr("dialogs.mp.consent.download", MultiplayerPolicy.CORE_VERSION)

/** 防火墙与进程生命周期说明：启动器不替用户改系统安全设置。 */
private fun firewallConsent(): String = tr("dialogs.mp.consent.firewall")
