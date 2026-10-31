// 展示房主与房客的操作教程，步骤与 EasyTier + Scaffolding 的实际流程一致。
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
import dev.molifox.multiplayer.common.MultiplayerPolicy

@Composable
internal fun MultiplayerGuide(host: Boolean) {
    val steps = if (host) HOST_STEPS else GUEST_STEPS
    MultiplayerGroup(Modifier.fillMaxWidth(), title = if (host) "房主教程" else "房客教程") {
        Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            steps.forEachIndexed { index, step ->
                Text("${index + 1}. $step", style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                if (host) HOST_BOUNDARY else GUEST_BOUNDARY,
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private val EASYTIER_VERSION = MultiplayerPolicy.CORE_VERSION

private val HOST_STEPS =
    listOf(
        "进入联机大厅阅读联机说明；首次使用会下载官方 EasyTier $EASYTIER_VERSION 并按 SHA-256 校验。",
        "启动 Minecraft 并进入要分享的存档，按 Esc → 对局域网开放，确定后回到启动器。",
        "在联机大厅点“刷新世界”，选中刚开放的世界，再点“创建房间”。",
        "房间开启后复制邀请码发给朋友；邀请码包含网络密钥，不要公开发布。",
        "保持游戏与启动器运行。退出存档后房间会自动关闭，房客也会断开。",
    )

private val GUEST_STEPS =
    listOf(
        "确认游戏版本与必要模组和房主一致，并准备好自己的 Minecraft 客户端。",
        "在联机大厅粘贴房主发来的 21 字符邀请码；空格会被去掉并自动转成大写。",
        "点“加入房间”，等待启动虚拟网络、等到房主上线、建立端口转发；中继较慢时可能需要一到两分钟。",
        "显示“已加入房间”后，打开游戏 → 多人游戏 → 刷新局域网世界，也可以直接添加界面上的本地地址。",
        "退出房间或关闭启动器会断开连接；失败时确认房主房间仍在、邀请码完整、双方能连上中继。",
    )

private const val HOST_BOUNDARY =
    "启动器会在创建或加入房间前自动获取 EasyTier 活跃公共节点；都不可用时可在联机设置里填写自定义中继。" +
        "MoliFox 不会自动启动游戏，也不提供账号好友邀请；联机不替换 Minecraft 登录验证，也不会同步模组。"

private const val GUEST_BOUNDARY =
    "首次运行 EasyTier 时 Windows 可能弹出防火墙提示，启动器不会自动修改防火墙设置。" +
        "MoliFox 不会自动启动游戏，也不提供账号好友邀请；联机不替换 Minecraft 登录验证，也不会同步模组。"
