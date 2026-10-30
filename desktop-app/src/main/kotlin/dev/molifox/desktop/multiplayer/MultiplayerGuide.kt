// 展示房主与房客的操作教程，内容与陶瓦联机的实际流程一致。
package dev.molifox.desktop.multiplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun MultiplayerGuide(host: Boolean) {
    val steps =
        if (host) {
            listOf(
                "进入联机大厅，阅读联机说明；首次使用按提示下载并校验联机核心。",
                "启动 Minecraft 并进入要分享的存档，按 Esc 选择“对局域网开放”并创建局域网世界。",
                "回到启动器点击“创建房间”，联机核心会扫描局域网世界并自动建立房间。",
                "房间建立后复制邀请码发给朋友；邀请码包含入网凭据，不要公开发布。",
                "保持游戏与启动器运行。退出存档后联机核心会发现世界已关闭，房间随之自动关闭。",
            )
        } else {
            listOf(
                "确认双方使用相同的游戏版本与必要模组，并准备好自己的 Minecraft 客户端。",
                "进入联机大厅，首次使用按提示下载联机核心，然后粘贴房主发来的邀请码。",
                "点击“加入房间”，等待显示已加入房间；网络较差时会先尝试备用线路。",
                "启动 Minecraft，进入多人游戏，双击房主的存档进入世界。",
                "加入失败时让房主确认房间仍在、存档没有退出，并检查双方网络。",
            )
        }
    MultiplayerGroup(Modifier.fillMaxWidth(), title = if (host) "房主教程" else "房客教程") {
        Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            steps.forEachIndexed { index, step ->
                Text("${index + 1}. $step", style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "陶瓦联机与 HMCL / PCL CE 的联机功能使用同一套邀请码，可互相加入房间；" +
                    "联机不替换 Minecraft 登录验证，也不会自动同步模组。",
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
