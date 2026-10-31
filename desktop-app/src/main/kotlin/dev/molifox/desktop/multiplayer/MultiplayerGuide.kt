// 展示房主与房客的“人话”教程：只讲操作顺序和常见问题，不堆协议术语。
package dev.molifox.desktop.multiplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.molifox.desktop.MutedText

@Composable
internal fun MultiplayerGuide(host: Boolean) {
    val steps = if (host) HOST_STEPS else GUEST_STEPS
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        MultiplayerGroup(Modifier.fillMaxWidth(), title = if (host) "房主教程" else "房客教程") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                steps.forEachIndexed { index, step ->
                    Text("${index + 1}. $step", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        MultiplayerGroup(Modifier.fillMaxWidth(), title = "常见问题") {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                FAQ_ITEMS.forEach { (question, answer) ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(question, style = MaterialTheme.typography.bodyMedium)
                        MutedText(answer)
                    }
                }
            }
        }
        MultiplayerGroup(Modifier.fillMaxWidth(), title = "这些情况要知道") {
            Text(BOUNDARY_NOTICE, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private val HOST_STEPS =
    listOf(
        "在游戏里进入要分享的存档，按 Esc → 对局域网开放，记下游戏显示的端口号。",
        "回到启动器的联机页，点“刷新世界”，选中你刚开放的那个世界。",
        "点“创建房间”，把生成的邀请码复制发给朋友。",
        "保持游戏和启动器都开着，朋友才能进来。",
        "退出存档或关闭启动器，房间就关了，朋友会断开。",
    )

private val GUEST_STEPS =
    listOf(
        "问房主要 21 位邀请码，形如 U/XXXX-XXXX-XXXX-XXXX。",
        "在联机页粘贴邀请码并点“加入房间”；启动器会建一条私人通道，有时要等 1-2 分钟。",
        "打开游戏 → 多人游戏 → 刷新，出现朋友的世界就进去；没出现就手动添加界面给出的本地地址。",
        "保持启动器运行，联机期间别关。",
        "退出房间或关闭启动器就断开。",
    )

/** 常见问题：覆盖最容易卡住用户的几件事，答案只描述启动器确实会做的事。 */
private val FAQ_ITEMS =
    listOf(
        "邀请码无效或房间不存在？" to
            "确认邀请码完整（21 位）且房主的房间还开着；房主退出存档后房间会自动关闭。",
        "一直连不上？" to
            "换一个网络（例如手机热点）再试；仍然不行就在联机设置里填自定义中继。",
        "弹出防火墙提示怎么办？" to
            "点“允许访问”即可。启动器不会替你修改防火墙设置。",
        "要装一样的模组吗？" to
            "要。启动器不同步模组，双方的游戏版本和模组需要一致。",
        "启动器会自动打开游戏或邀请账号好友吗？" to
            "不会。游戏要自己启动，邀请码也要自己发给朋友。",
    )

private const val BOUNDARY_NOTICE =
    "官方节点状态接口已停用，启动器会自动改用社区节点列表；都连不上时可在联机设置里填写自定义中继。" +
        "联机不替换 Minecraft 登录验证，也不会同步模组；房主退出存档会关房，双方都要保持启动器运行。"
