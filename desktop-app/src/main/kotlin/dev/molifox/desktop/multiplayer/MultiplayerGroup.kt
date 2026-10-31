// 连续内容区域共用一个外框，各分组只通过细分隔线区分；联机说明文案集中在这里。
package dev.molifox.desktop.multiplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.molifox.desktop.hairline
import dev.molifox.multiplayer.common.MultiplayerPolicy

private const val GROUP_PADDING_DP = 16
private const val GROUP_SPACING_DP = 8

@Composable
internal fun MultiplayerGroup(
    modifier: Modifier = Modifier,
    padding: Int = GROUP_PADDING_DP,
    title: String? = null,
    detail: String = "",
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(padding.dp),
            verticalArrangement = Arrangement.spacedBy(GROUP_SPACING_DP.dp),
        ) {
            title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
        HorizontalDivider(color = hairline())
    }
}

/**
 * 技术说明只描述实际实现：EasyTier 独立进程 + Scaffolding 协议。
 *
 * 不写“自动创建房间”“自动启动游戏”之类尚未实现的行为，避免把妥协方案当功能承诺。
 */
internal const val MULTIPLAYER_TECHNOLOGY_NOTICE =
    "MoliFox 联机使用 EasyTier 虚拟网络与 Scaffolding 协议：每台电脑各运行一个官方 EasyTier 独立进程，" +
        "把彼此接入同一个虚拟局域网，再把房主的 Minecraft 局域网世界转给房间内的玩家。" +
        "EasyTier 是第三方开源软件（LGPL-3.0），首次使用时从官方渠道下载并按 SHA-256 校验。"

internal const val MULTIPLAYER_NETWORK_NOTICE =
    "联机双方都需要能连上内置自建节点；优先 P2P 直连，打洞失败自动走中继，中继不可用或不支持转发时才会连接失败。" +
        "邀请码包含网络密钥，等同于房间口令，请不要公开发布。房主必须保持游戏与启动器运行，" +
        "退出存档后房间会自动关闭。"

internal const val MULTIPLAYER_BOUNDARY_NOTICE =
    "联机不替换 Minecraft 登录验证，也不会同步模组；双方需要相同的游戏版本与必要模组。"

internal const val MULTIPLAYER_LEGAL_NOTICE =
    "在多人联机全过程中，你必须严格遵守所在国家与地区的全部法律法规。"

/** 体积取官方发布包四舍五入后的约值；界面只用于说明，下载与校验由后端负责。 */
internal val MULTIPLAYER_RUNTIME_DOWNLOAD_NOTICE =
    "首次使用会下载官方 EasyTier ${MultiplayerPolicy.CORE_VERSION}（约 31 MB，SHA-256 校验）。"
