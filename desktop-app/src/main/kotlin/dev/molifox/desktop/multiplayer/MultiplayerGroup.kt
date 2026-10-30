// 连续内容区域共用一个外框，各分组只通过细分隔线区分。
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

internal const val MULTIPLAYER_TECHNOLOGY_NOTICE =
    "MoliFox 联机使用陶瓦（Terracotta）联机核心：由核心扫描局域网世界、在玩家之间建立 P2P 连接，" +
        "直连不可用时经公共中继节点转发。联机核心是第三方开源软件，首次使用时从官方发布渠道下载并校验。"
internal const val MULTIPLAYER_NETWORK_NOTICE =
    "联机体验取决于所有参与者的网络情况。邀请码包含入网凭据，请只分享给一起游玩的朋友；" +
        "房间需要房主保持存档打开，房主退出存档后房间会自动关闭。"
internal const val MULTIPLAYER_LEGAL_NOTICE =
    "在多人联机全过程中，你必须严格遵守所在国家与地区的全部法律法规。"
