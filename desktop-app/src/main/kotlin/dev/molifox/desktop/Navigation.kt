// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责桌面导航状态、顶部导航和通用交互效果。

package dev.molifox.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.onClick
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.LAUNCHER_VERSION
import dev.molifox.core.LauncherState

/** 顶部导航条目，列表顺序就是显示顺序：开始、下载、联机、设置。 「实例」已从导航移除（页面与 [Page] 枚举保留，仍可从开始页与实例详情进入）。 */
val TopNavPages: List<Page> = listOf(Page.Home, Page.Discover, Page.Multiplayer, Page.Settings)

/**
 * 旧版本存下的顶部标签名 → 可显示的导航页。 名字可能存英文枚举名或中文标题；「实例」已不在导航里，日志、账户列表等子页同样不在，
 * 因此这些旧值和任何无法识别的值都回落到开始页，枚举增删不会让旧配置崩溃。
 */
fun topNavPageOf(stored: String?): Page {
    val key = stored?.trim().orEmpty()
    if (key.isEmpty()) return Page.Home
    return TopNavPages.firstOrNull { it.name.equals(key, true) || it.title == key } ?: Page.Home
}

class DesktopNavigationState(
    initialTab: String? = null,
) {
    var page by androidx.compose.runtime.mutableStateOf(topNavPageOf(initialTab))
    var editing by androidx.compose.runtime.mutableStateOf<String?>(null)
    var downloadKind by androidx.compose.runtime.mutableStateOf("minecraft")

    // 「账户列表」是开始页的子页：记住进入前的页面，返回时回到来源，而不是固定回开始页。
    private var accountReturnTo by androidx.compose.runtime.mutableStateOf(Page.Home)

    // 实例详情同属子页：开始页「版本设置」进详情后返回开始，从实例列表进来则返回列表。
    private var detailReturnTo by androidx.compose.runtime.mutableStateOf(Page.Instances)

    /** 顶部导航的高亮归属：只有导航里的页高亮自己。 账户列表、实例与日志都是「开始」这一支的子页，打开时仍高亮开始。 */
    val activeTab: Page
        get() = if (page in TopNavPages) page else Page.Home

    fun navigate(
        destination: Page,
        resources: Boolean = false,
    ) {
        editing = null
        if (destination == Page.Discover && resources) downloadKind = "mod"
        page = destination
    }

    fun install() {
        editing = null
        downloadKind = "minecraft"
        page = Page.Discover
    }

    /** 打开账户列表：记下来源页；已经在账户列表时不覆盖来源。 */
    fun openAccounts() {
        editing = null
        if (page != Page.Accounts) accountReturnTo = page
        page = Page.Accounts
    }

    /** 返回来源页；来源本身异常地指向账户列表时兜底回开始页。 */
    fun closeAccounts() {
        page = if (accountReturnTo == Page.Accounts) Page.Home else accountReturnTo
    }

    /** 打开实例详情：记下打开时可见的那一页。 开始页的「版本设置」传 Page.Home，实例列表里的「设置」传 Page.Instances。 */
    fun openInstance(
        id: String,
        from: Page,
    ) {
        editing = id
        detailReturnTo = if (from == Page.Home) Page.Home else Page.Instances
        page = Page.Instances
    }

    /** 关闭实例详情：回到打开它的那一页；从开始页进来就直接回开始，不再先落到实例列表。 */
    fun closeInstance() {
        editing = null
        page = detailReturnTo
    }
}

/** 选中/悬停/文字颜色共用的过渡曲线；关闭动画时直接跳到终值。 */
@Composable
private fun <T> stateSpec(): AnimationSpec<T> =
    if (LocalReducedMotion.current) snap<T>() else tween<T>(durationMillis = Motion.FAST)

/**
 * 标题栏：52dp 高，左侧品牌，中部四项导航，右侧窗口三键，底部 1dp 分隔线。 导航仍在顶部（顶部导航布局），只做视觉收紧；「设置」右侧的测试提示占用剩余空间， 窗口宽度不足 1100dp
 * 时整条隐藏，不挤压导航和窗口按钮。
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
        if (state.data.settings.titlebarTransparent) {
            colors.surface.copy(alpha = 0f)
        } else {
            colors.surface
        }
    Column(Modifier.fillMaxWidth().background(headerColor)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // 窄窗口下提示会让位：整条隐藏，避免文字挤进窗口三键。
            val showBetaNotice = maxWidth >= 1100.dp
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(Metrics.titlebarHeight)
                    .padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 品牌 + 导航：这一整片是窗口拖拽区的可视部分，双击切换最大化。
                Row(
                    Modifier
                        .fillMaxHeight()
                        .then(
                            if (onTitlebarDoubleClick != null) {
                                Modifier.titlebarDoubleClick(onTitlebarDoubleClick)
                            } else {
                                Modifier
                            },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 左上角品牌位：用户设置过自定义图标就显示自定义图片，否则用内置狐狸图。
                    BrandMark(Modifier.size(22.dp))
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
                        // 顶部导航固定四项（开始 / 下载 / 联机 / 设置），顺序由 TopNavPages 决定；
                        // 实例不在顶部导航里，日志与个性化从设置页进入。
                        TopNavPages.forEach { item ->
                            TopNavItem(
                                page = item,
                                selected = navigation.activeTab == item,
                                badge = null,
                                onClick = { navigation.navigate(item) },
                            )
                        }
                    }
                }
                // 品牌与导航宽度固定，剩余空间全给提示；窗口三键始终贴右，位置不随提示变化。
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .then(
                            if (onTitlebarDoubleClick != null) {
                                Modifier.titlebarDoubleClick(onTitlebarDoubleClick)
                            } else {
                                Modifier
                            },
                        ).padding(end = 12.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    if (showBetaNotice) {
                        Text(
                            BETA_NOTICE,
                            modifier = Modifier.fillMaxWidth(),
                            color = colors.onSurfaceVariant,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.End,
                        )
                    }
                }
                controls()
            }
        }
        HorizontalDivider(color = hairline())
    }
}

/** 顶部测试阶段提示；窗口过窄时整条不渲染。 */
private const val BETA_NOTICE = "当前启动器为测试阶段，BUG 较多，请及时反馈"

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
        Modifier
            .height(Metrics.navItemHeight)
            .focusRing(focused, cornerRadius = Metrics.cardRadius)
            .clip(shape)
            .background(background)
            .selectable(
                selected,
                interaction,
                LocalIndication.current,
                role = Role.Tab,
                onClick = onClick,
            ).padding(horizontal = 12.dp),
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

/** 标题栏双击：只观察按下事件、不消费，因此不会吞掉 WindowDraggableArea 的拖动。 400ms 内同一位置两次按下视为双击。 */
private fun Modifier.titlebarDoubleClick(onDoubleClick: () -> Unit): Modifier =
    pointerInput(onDoubleClick) {
        awaitPointerEventScope {
            var lastPressTime = 0L
            var lastPressPosition = Offset.Zero
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change =
                    if (event.type == PointerEventType.Press) {
                        event.changes.firstOrNull()
                    } else {
                        null
                    }
                if (change != null) {
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
        Modifier
            .fillMaxWidth()
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
            ).padding(horizontal = 10.dp),
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

/** 紧凑侧栏的缩进子条目（游戏文件夹/管理子菜单等）。 */
@Composable
fun RailSubEntry(
    title: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .hoverHighlight(
                RoundedCornerShape(Metrics.cardRadius),
                alpha = .05f,
            ).clip(RoundedCornerShape(Metrics.cardRadius))
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
