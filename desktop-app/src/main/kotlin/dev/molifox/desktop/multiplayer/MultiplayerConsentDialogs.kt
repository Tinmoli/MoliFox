// 展示联机说明与联机核心下载确认；未点击确认时不记录同意，也不启动下载。
package dev.molifox.desktop.multiplayer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import dev.molifox.multiplayer.common.CoreStatus

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
                Text(MULTIPLAYER_LEGAL_NOTICE, Modifier.padding(top = 12.dp))
                Text(
                    "你在房间中使用的显示名称、随机设备标识与客户端版本会发送给同房间的玩家，用于房间内展示与识别；" +
                        "公共中继节点参与网络发现或转发。邀请码包含入网凭据，请只分享给要一起玩的朋友。",
                    Modifier.padding(top = 12.dp),
                )
                Text(
                    "首次使用需要另外确认下载联机核心（约 8 MiB），下载完成后按官方清单校验 SHA-512；" +
                        "联机核心采用 AGPL-3.0 许可，随启动器一起分发的是其官方发布包。" +
                        "关闭房间或退出启动器会停止本次联机进程。",
                    Modifier.padding(top = 12.dp),
                )
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
        confirmButton = { TextButton(onClick = onAccept, enabled = checked) { Text("同意并继续") } },
        dismissButton = { TextButton(onClick = onDecline) { Text("暂不使用") } },
    )
}

@Composable
internal fun CoreDownloadDialog(
    status: CoreStatus,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("下载联机核心") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text("首次使用需要下载陶瓦联机核心；确认后才会开始下载，失败会换源重试并保留进度提示。")
                Text(
                    "版本：${status.version}\n平台：${status.platform}\n下载大小：${"%.1f".format(
                        status.downloadBytes / MEBIBYTE,
                    )} MiB",
                    Modifier.padding(top = 12.dp),
                )
                Text(
                    "保存位置：${status.directory}",
                    Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("取消不会下载或启动联机核心。", Modifier.padding(top = 12.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDownload) { Text("下载联机核心") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
    )
}

private const val MEBIBYTE = 1024.0 * 1024.0
