package dev.molifox.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.*

class DesktopNavigationState {
    var page by androidx.compose.runtime.mutableStateOf(Page.Home)
    var editing by androidx.compose.runtime.mutableStateOf<String?>(null)
    var downloadKind by androidx.compose.runtime.mutableStateOf("minecraft")

    fun navigate(destination: Page, resources: Boolean = false) {
        editing = null
        if (destination == Page.Discover && resources) downloadKind = "mod"
        page = destination
    }

    fun install() {
        editing = null
        downloadKind = "minecraft"
        page = Page.Discover
    }
}

/** 选中/悬停/文字颜色共用的过渡曲线；关闭动画时直接跳到终值。 */
@Composable
private fun <T> stateSpec(): AnimationSpec<T> =
    if (LocalReducedMotion.current) snap<T>() else tween<T>(durationMillis = Motion.fast)

/**
 * 标题栏：52dp 高，左侧品牌，中部四项导航，右侧窗口三键，底部 1dp 分隔线。
 * 导航仍在顶部（用户要求保留原位置），只做视觉收紧。
 */
@Composable
fun AppHeader(
    navigation: DesktopNavigationState,
    state: LauncherState,
    onTitlebarDoubleClick: (() -> Unit)? = null,
    controls: @Composable RowScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    // 标题栏半透明：让背景/壁纸透过标题栏，无需开启玻璃效果；关闭时使用实色。
    val headerColor =
        if (state.data.settings.titlebarTransparent) colors.surface.copy(alpha = 0f)
        else colors.surface
    val runningJobs =
        state.jobs.count { it.status in listOf(JobStatus.Queued, JobStatus.Running) }
    Column(Modifier.fillMaxWidth().background(headerColor)) {
        Row(
            Modifier.fillMaxWidth()
                .height(Metrics.titlebarHeight)
                .padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 品牌 + 导航：这一整片是窗口拖拽区的可视部分，双击切换最大化。
            Row(
                Modifier.weight(1f)
                    .fillMaxHeight()
                    .then(
                        if (onTitlebarDoubleClick != null)
                            Modifier.titlebarDoubleClick(onTitlebarDoubleClick)
                        else Modifier
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FoxMark(Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        "MoliFox",
                        fontSize = 14.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                    )
                    Text(
                        "v" + LAUNCHER_VERSION,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        color = colors.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(18.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 主导航固定四个板块；日志与个性化从设置页进入。
                    listOf(Page.Home, Page.Instances, Page.Discover, Page.Settings).forEach {
                        item ->
                        TopNavItem(
                            page = item,
                            selected = navigation.page == item,
                            badge = if (item == Page.Settings && runningJobs > 0) "$runningJobs" else null,
                            onClick = { navigation.navigate(item) },
                        )
                    }
                }
            }
            controls()
        }
        HorizontalDivider(color = hairline())
    }
}

/** 顶部导航项：32dp 高、圆角 6、图标 17、文字 13，选中与悬停都用 120ms 颜色过渡。 */
@Composable
private fun TopNavItem(
    page: Page,
    selected: Boolean,
    badge: String?,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Metrics.cardRadius)
    val spec = stateSpec<Color>()
    val background by
        animateColorAsState(
            targetValue =
                when {
                    selected -> colors.primaryContainer
                    hovered -> colors.primary.copy(alpha = .05f)
                    else -> Color.Transparent
                },
            animationSpec = spec,
            label = "navBackground",
        )
    val content by
        animateColorAsState(
            targetValue = if (selected) colors.primary else colors.onSurface,
            animationSpec = spec,
            label = "navContent",
        )
    Row(
        Modifier.height(Metrics.navItemHeight)
            .focusRing(focused, cornerRadius = Metrics.cardRadius)
            .clip(shape)
            .background(background)
            .selectable(
                selected,
                interaction,
                LocalIndication.current,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(page.icon, null, Modifier.size(17.dp), content)
        Spacer(Modifier.width(6.dp))
        Text(
            page.title,
            color = content,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
        if (badge != null) {
            Spacer(Modifier.width(4.dp))
            Text(badge, color = content, fontSize = 11.sp)
        }
    }
}

/**
 * 标题栏双击：只观察按下事件、不消费，因此不会吞掉 WindowDraggableArea 的拖动。
 * 400ms 内同一位置两次按下视为双击。
 */
private fun Modifier.titlebarDoubleClick(onDoubleClick: () -> Unit): Modifier =
    pointerInput(onDoubleClick) {
        awaitPointerEventScope {
            var lastPressTime = 0L
            var lastPressPosition = Offset.Zero
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Press) continue
                val change = event.changes.firstOrNull() ?: continue
                val now = change.uptimeMillis
                val position = change.position
                val doubleClick =
                    now - lastPressTime <= 400L &&
                        (position - lastPressPosition).getDistance() <= 8f
                if (doubleClick) {
                    lastPressTime = 0L
                    onDoubleClick()
                } else {
                    lastPressTime = now
                    lastPressPosition = position
                }
            }
        }
    }

/** 页面内二级导航项：34dp 高、圆角 6、图标 16、文字 13。 */
@Composable
fun NavigationEntry(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    wide: Boolean = true,
    onClick: () -> Unit,
    badge: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val spec = stateSpec<Color>()
    val color by
        animateColorAsState(
            targetValue = if (selected) colors.primary else colors.onSurfaceVariant,
            animationSpec = spec,
            label = "entryContent",
        )
    val background by
        animateColorAsState(
            targetValue =
                when {
                    selected -> colors.primaryContainer
                    hovered -> colors.primary.copy(alpha = .05f)
                    else -> Color.Transparent
                },
            animationSpec = spec,
            label = "entryBackground",
        )
    Row(
        Modifier.fillMaxWidth()
            .height(Metrics.controlHeight)
            .focusRing(focused, cornerRadius = Metrics.cardRadius)
            .clip(RoundedCornerShape(Metrics.cardRadius))
            .background(background)
            .selectable(
                selected,
                interaction,
                LocalIndication.current,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (wide) Arrangement.Start else Arrangement.Center,
    ) {
        Icon(icon, if (wide) null else title, Modifier.size(16.dp), color)
        if (wide) {
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                Modifier.weight(1f),
                color = color,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
            )
            if (badge != null) Text(badge, color = color, fontSize = 11.sp)
        }
    }
}

/**
 * 紧凑侧栏条目：实例管理与下载页的条目多、窗口高度有限，
 * 用固定小高度保证整栏在 720 高度窗口内完整可见。
 */
@Composable
fun RailEntry(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val spec = stateSpec<Color>()
    val color by
        animateColorAsState(
            targetValue = if (selected) colors.primary else colors.onSurfaceVariant,
            animationSpec = spec,
            label = "railContent",
        )
    val background by
        animateColorAsState(
            targetValue =
                when {
                    selected -> colors.primaryContainer
                    hovered -> colors.primary.copy(alpha = .05f)
                    else -> Color.Transparent
                },
            animationSpec = spec,
            label = "railBackground",
        )
    Row(
        Modifier.fillMaxWidth()
            .height(Metrics.controlHeight)
            .focusRing(focused, cornerRadius = Metrics.cardRadius)
            .clip(RoundedCornerShape(Metrics.cardRadius))
            .background(background)
            .selectable(
                selected,
                interaction,
                LocalIndication.current,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(16.dp), color)
        Spacer(Modifier.width(8.dp))
        Text(title, Modifier.weight(1f), color = color, fontSize = 13.sp, maxLines = 1)
    }
}

/** 紧凑侧栏的缩进子条目（游戏文件夹/管理子菜单等）。 */
@Composable
fun RailSubEntry(title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .height(26.dp)
            .hoverHighlight(
                RoundedCornerShape(Metrics.cardRadius),
                alpha = .05f,
            )
            .clip(RoundedCornerShape(Metrics.cardRadius))
            .selectable(false, role = Role.Tab, onClick = onClick)
            .padding(start = 25.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}