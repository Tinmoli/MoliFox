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
import dev.molifox.desktop.tr
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
internal fun multiplayerTechnologyNotice(): String = tr("dialogs.mp.policy.technology")

internal fun multiplayerNetworkNotice(): String = tr("dialogs.mp.policy.network")

internal fun multiplayerBoundaryNotice(): String = tr("dialogs.mp.policy.integrity")

internal fun multiplayerLegalNotice(): String = tr("dialogs.mp.policy.legal")

/** 体积取官方发布包四舍五入后的约值；界面只用于说明，下载与校验由后端负责。 */
internal fun multiplayerRuntimeDownloadNotice(): String = tr("dialogs.mp.policy.core", MultiplayerPolicy.CORE_VERSION)
