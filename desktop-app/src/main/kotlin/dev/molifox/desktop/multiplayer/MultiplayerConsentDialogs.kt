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
import dev.molifox.multiplayer.common.MultiplayerPolicy

@Composable
internal fun MultiplayerTermsDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    var checked by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text("开始使用 MoliFox 联机") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(MULTIPLAYER_TECHNOLOGY_NOTICE)
                Text(MULTIPLAYER_NETWORK_NOTICE, Modifier.padding(top = 12.dp))
                Text(
                    "你在房间中使用的显示名称、随机设备标识与客户端版本会发送给同房间的玩家，" +
                        "用于房间内展示与识别；EasyTier 中继节点参与网络发现与转发。" +
                        "邀请码包含网络密钥，等同于房间口令，请只分享给要一起玩的朋友。",
                    Modifier.padding(top = 12.dp),
                )
                Text(RUNTIME_CONSENT, Modifier.padding(top = 12.dp))
                Text(FIREWALL_CONSENT, Modifier.padding(top = 12.dp))
                Text(MULTIPLAYER_LEGAL_NOTICE, Modifier.padding(top = 12.dp))
                Row(
                    Modifier
                        .toggleable(checked, role = Role.Checkbox, onValueChange = { checked = it })
                        .semantics(mergeDescendants = true) { contentDescription = "我已阅读并了解以上说明" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Text("我已阅读并了解以上说明", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept, enabled = checked) { Text("同意并开始下载") }
        },
        dismissButton = { TextButton(onClick = onDecline) { Text("暂不使用") } },
    )
}

/** 下载与许可说明：只承诺启动器确实会做的事，不暗示安装驱动或提权。 */
private val RUNTIME_CONSENT =
    "点击“同意并开始下载”后会立即下载官方 EasyTier ${MultiplayerPolicy.CORE_VERSION} 独立进程" +
        "（约 31 MB，官方源失败会自动换镜像），下载完成后按 SHA-256 校验；" +
        "它以 LGPL-3.0 许可单独发布，由启动器作为独立进程运行。" +
        "启动器不安装 TUN 驱动，也不需要管理员权限。"

/** 防火墙与进程生命周期说明：启动器不替用户改系统安全设置。 */
private const val FIREWALL_CONSENT =
    "首次运行时 Windows 可能弹出防火墙提示；启动器不会自动修改防火墙设置，请按系统提示自行放行或拒绝。" +
        "关闭启动器会结束 EasyTier 进程，正在进行的联机也会中断。"
