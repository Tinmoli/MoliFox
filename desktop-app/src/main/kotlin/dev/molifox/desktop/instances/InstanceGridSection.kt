// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 实例网格视图：网格主体与固定高度卡片。

package dev.molifox.desktop.instances

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.GameInstance
import dev.molifox.desktop.InstanceArtwork
import dev.molifox.desktop.LocalStrings
import dev.molifox.desktop.Metrics
import dev.molifox.desktop.UiActions
import dev.molifox.desktop.hairline

/** 网格卡固定高度：图标行 + 单行名称 + 单行副标题 + 底部操作行，最长内容也不裁切（内容约 138dp，余量留给弹性间隔）。 */
private val InstanceCardHeight = 176.dp

/** 网格视图：卡片固定高 176dp、悬停 1dp 主题色边 + 轻微底色；操作行贴底，所有卡片按钮对齐。 */
@Composable
internal fun InstanceGridBody(
    versions: List<GameInstance>,
    actions: UiActions,
    selectedId: String?,
    busy: Boolean,
    onSelect: (GameInstance) -> Unit,
    onManage: (String) -> Unit,
    onLaunch: (GameInstance) -> Unit,
    onDelete: (GameInstance) -> Unit,
    onFavorite: (String) -> Unit,
    onRename: (GameInstance) -> Unit,
    onCopy: (GameInstance) -> Unit,
    onExport: (GameInstance) -> Unit,
) {
    LazyVerticalGrid(
        // 列宽下限 248dp：卡片内 220dp 才放得下「开始游戏 / 收藏 / 设置 / 删除」四个 12sp 文字按钮。
        GridCells.Adaptive(248.dp),
        Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 6.dp),
    ) {
        items(versions, key = { it.id }) { instance ->
            InstanceGridCard(
                instance = instance,
                actions = actions,
                selected = instance.id == selectedId,
                busy = busy || actions.controller.isInstanceBusy(instance.id),
                onSelect = { onSelect(instance) },
                onManage = { onManage(instance.id) },
                onLaunch = { onLaunch(instance) },
                onDelete = { onDelete(instance) },
                onFavorite = { onFavorite(instance.id) },
                onRename = { onRename(instance) },
                onCopy = { onCopy(instance) },
                onExport = { onExport(instance) },
            )
        }
    }
}

@Composable
private fun InstanceGridCard(
    instance: GameInstance,
    actions: UiActions,
    selected: Boolean,
    busy: Boolean,
    onSelect: () -> Unit,
    onManage: () -> Unit,
    onLaunch: () -> Unit,
    onDelete: () -> Unit,
    onFavorite: () -> Unit,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onExport: () -> Unit,
) {
    val shape = RoundedCornerShape(Metrics.cardRadius)
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()
    var menuOpen by remember(instance.id) { mutableStateOf(false) }
    var menuOffset by remember(instance.id) { mutableStateOf(DpOffset.Zero) }
    // 右键菜单贴在指针位置，⋯ 菜单贴在按钮自身：两者用同一个 DropdownMenu 实现，
    // 但锚点必须落在各自的实际坐标空间里，否则弹窗会跑到页面左侧或窗口外。
    var menuAnchor by remember(instance.id) { mutableStateOf(InstanceMenuAnchor.Pointer) }
    val density = LocalDensity.current
    Box(Modifier.fillMaxWidth()) {
        Surface(
            Modifier
                .fillMaxWidth()
                .height(InstanceCardHeight)
                .pointerInput(instance.id) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (
                                event.type == PointerEventType.Press &&
                                event.buttons.isSecondaryPressed
                            ) {
                                event.changes.firstOrNull()?.let { change ->
                                    menuOffset =
                                        with(density) {
                                            DpOffset(
                                                change.position.x.toDp(),
                                                change.position.y.toDp(),
                                            )
                                        }
                                }
                                menuAnchor = InstanceMenuAnchor.Pointer
                                menuOpen = true
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }.hoverable(hoverSource)
                .clickable(onClick = onSelect),
            shape = shape,
            color = cardSurfaceColor(hovered),
            border = cardBorder(selected, hovered),
        ) {
            Column(Modifier.fillMaxSize().padding(14.dp)) {
                InstanceArtwork(instance, actions, Modifier.size(32.dp))
                Spacer(Modifier.height(10.dp))
                Text(
                    instance.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                InstanceCardSubtitle(instance)
                // 弹性间隔吃掉剩余高度：无论名称长短，操作行都贴在卡片底部，跨卡片对齐。
                Spacer(Modifier.weight(1f))
                InstanceCardActions(instance, busy, onLaunch, onFavorite, onManage, onDelete)
            }
        }
        // 网格卡没有空间再放一个图标按钮：右键菜单与列表行是同一份内容。
        InstanceContextMenu(
            expanded = menuOpen,
            offset = menuOffset,
            instance = instance,
            busy = busy,
            onDismiss = { menuOpen = false },
            onLaunch = onLaunch,
            onRename = onRename,
            onCopy = onCopy,
            onOpenFolder = { actions.run { actions.controller.openFolder(instance.id) } },
            onExport = onExport,
            onDelete = onDelete,
        )
    }
}

/** 卡片底色：悬停时向主题色靠 4%。 */
@Composable
private fun cardSurfaceColor(hovered: Boolean): Color =
    if (hovered) {
        lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.primary, .04f)
    } else {
        MaterialTheme.colorScheme.surface
    }

/** 卡片边框：选中用主题色 70%，悬停 45%，其余细线。 */
@Composable
private fun cardBorder(
    selected: Boolean,
    hovered: Boolean,
): BorderStroke =
    BorderStroke(
        1.dp,
        when {
            selected -> MaterialTheme.colorScheme.primary.copy(alpha = .7f)
            hovered -> MaterialTheme.colorScheme.primary.copy(alpha = .45f)
            else -> hairline()
        },
    )

/** 卡片副标题：无法识别时给出原因并标红。 */
@Composable
private fun InstanceCardSubtitle(instance: GameInstance) {
    val strings = LocalStrings.current
    Text(
        if (instance.broken) {
            strings.t("pages.instance.card.broken", instance.brokenReason)
        } else {
            // 自定义整合包 id 提不出真实版本时显示「版本未知」，不输出空段。
            instance.gameVersion.ifBlank { strings.t("pages.instance.meta.versionUnknown") } +
                " · " + instance.loader.title
        },
        fontSize = 12.sp,
        color =
            if (instance.broken) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 底部操作行：开始 / 收藏 / 设置 / 删除。 */
@Composable
private fun InstanceCardActions(
    instance: GameInstance,
    busy: Boolean,
    onLaunch: () -> Unit,
    onFavorite: () -> Unit,
    onManage: () -> Unit,
    onDelete: () -> Unit,
) {
    val strings = LocalStrings.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InstanceTextAction(
            strings.t("pages.instances.row.launch"),
            onLaunch,
            enabled = !busy && !instance.broken,
            tint = MaterialTheme.colorScheme.primary,
        )
        InstanceTextAction(
            if (instance.favorite) {
                strings.t("pages.instances.row.favorited")
            } else {
                strings.t("pages.instances.row.favorite")
            },
            onFavorite,
            tint =
                if (instance.favorite) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
        InstanceTextAction(strings.t("pages.instances.row.settings"), onManage)
        InstanceTextAction(
            strings.t("pages.instances.row.delete"),
            onDelete,
            tint = MaterialTheme.colorScheme.error,
        )
    }
}
