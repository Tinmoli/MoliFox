package dev.molifox.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 账户操作图标按钮：皮肤概览 / 刷新账户资料 / 删除账户共用的一套图标按钮。
 *
 * 开始页「游戏账户」卡片与账户列表页的行尾操作都调用它，保证两处的图标、中文提示与悬停反馈一致；
 * 触区由 [size] 决定（开始页 32dp、账户列表沿用 [Metrics.iconButton] 30dp）。
 * 按钮自身是 [SmallIconButton]（Material `IconButton`），按下事件在子节点就被消费，
 * 因此放在整块可点的卡片里也不会把点击冒泡成「打开账户列表」。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AccountIconAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    enabled: Boolean = true,
    size: Dp = Metrics.iconButton,
) {
    TooltipArea(tooltip = { AccountActionTooltip(label) }, delayMillis = 400) {
        SmallIconButton(icon, label, onClick, tint, enabled, size)
    }
}

/** 图标按钮的悬停提示：白底细边小气泡，与「最近游玩」的地址气泡同一套样式。 */
@Composable
fun AccountActionTooltip(text: String) {
    Surface(
        shape = RoundedCornerShape(Metrics.innerRadius),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, hairline()),
        shadowElevation = 2.dp,
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}