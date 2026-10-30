// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 实例列表视图：列表主体、行、行内文字操作与收藏 / 视图切换按钮。

package dev.molifox.desktop.instances

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.GameInstance
import dev.molifox.desktop.InstanceArtwork
import dev.molifox.desktop.Metrics
import dev.molifox.desktop.Motion
import dev.molifox.desktop.SmallIconButton
import dev.molifox.desktop.Tag
import dev.molifox.desktop.UiActions
import dev.molifox.desktop.hairline
import dev.molifox.desktop.hoverHighlight
import dev.molifox.desktop.selectedRowBackground

/** 实例行高：64dp，容纳两行文字与 12sp 文字操作。 */
private val InstanceRowHeight = 64.dp

/** 收藏 / 视图切换用的小切换按钮：34dp、圆角 4，选中态用主题色。 */
@Composable
internal fun CompactToggleButton(
    selected: Boolean,
    icon: ImageVector,
    label: String?,
    description: String? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Metrics.innerRadius)
    val background by
        animateColorAsState(
            if (selected) selectedRowBackground() else Color.Transparent,
            tween(Motion.FAST),
            label = "toggle-bg",
        )
    val tint =
        if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    Row(
        Modifier
            .height(Metrics.controlHeight)
            .then(if (label == null) Modifier.width(Metrics.controlHeight) else Modifier)
            .clip(shape)
            // 只有图标的切换按钮必须自带无障碍名字，否则读屏与 GUI 测试只能靠位置猜。
            .then(
                if (description == null) {
                    Modifier
                } else {
                    Modifier.semantics { contentDescription = description }
                },
            ).hoverHighlight(shape)
            .clickable(onClick = onClick)
            .background(background, shape)
            .padding(horizontal = if (label == null) 8.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, label, Modifier.size(16.dp), tint)
        if (label != null) Text(label, fontSize = 13.sp, color = tint, maxLines = 1)
    }
}

/** 行内文字按钮：12sp、触区高 28dp，悬停淡入。 */
@Composable
internal fun InstanceTextAction(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val shape = RoundedCornerShape(Metrics.innerRadius)
    Box(
        Modifier
            .height(28.dp)
            .clip(shape)
            .hoverHighlight(shape, enabled = enabled)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = if (enabled) tint else tint.copy(alpha = .38f),
            maxLines = 1,
        )
    }
}

/** 列表视图：行高 64dp、圆角 6、悬停淡入；右键或行尾「更多操作」打开上下文菜单。 */
@Composable
internal fun InstanceListBody(
    versions: List<GameInstance>,
    actions: UiActions,
    selectedId: String?,
    busy: Boolean,
    showNotes: Boolean,
    sizeRevision: Int,
    onSelect: (GameInstance) -> Unit,
    onManage: (String) -> Unit,
    onLaunch: (GameInstance) -> Unit,
    onDelete: (GameInstance) -> Unit,
    onFavorite: (String) -> Unit,
    onRename: (GameInstance) -> Unit,
    onCopy: (GameInstance) -> Unit,
    onExport: (GameInstance) -> Unit,
) {
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().padding(end = 12.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 6.dp),
        ) {
            items(versions, key = { it.id }) { instance ->
                InstanceListRow(
                    instance = instance,
                    actions = actions,
                    selected = instance.id == selectedId,
                    busy = busy || actions.controller.isInstanceBusy(instance.id),
                    showNotes = showNotes,
                    sizeRevision = sizeRevision,
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
        VerticalScrollbar(
            rememberScrollbarAdapter(listState),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

@Composable
private fun InstanceListRow(
    instance: GameInstance,
    actions: UiActions,
    selected: Boolean,
    busy: Boolean,
    showNotes: Boolean,
    sizeRevision: Int,
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
    var menuOpen by remember(instance.id) { mutableStateOf(false) }
    var menuOffset by remember(instance.id) { mutableStateOf(DpOffset.Zero) }
    // 右键菜单贴在指针位置，⋯ 菜单贴在按钮自身：两者用同一个 DropdownMenu 实现，
    // 但锚点必须落在各自的实际坐标空间里，否则弹窗会跑到页面左侧或窗口外。
    var menuAnchor by remember(instance.id) { mutableStateOf(InstanceMenuAnchor.Pointer) }
    val density = LocalDensity.current
    var directorySize by remember(instance.id, instance.gameDirectoryId, sizeRevision) {
        mutableStateOf<Long?>(null)
    }
    LaunchedEffect(instance.id, instance.gameDirectoryId, sizeRevision) {
        directorySize = actions.controller.instanceDirectorySize(instance.id) ?: -1L
    }
    Box(Modifier.fillMaxWidth()) {
        Surface(
            Modifier
                .fillMaxWidth()
                .height(InstanceRowHeight)
                // 右键：在指针位置打开同一份上下文菜单；已打开的菜单不重复弹。
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
                }.clickable(onClick = onSelect),
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
            border =
                BorderStroke(
                    1.dp,
                    if (selected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = .6f)
                    } else {
                        hairline()
                    },
                ),
        ) {
            Row(
                Modifier.fillMaxSize().hoverHighlight(shape).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InstanceArtwork(instance, actions, Modifier.size(28.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            instance.name,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (selected) Tag("当前版本")
                        if (!instance.broken && !instance.installed) {
                            Tag("待修复", MaterialTheme.colorScheme.error)
                        }
                    }
                    // 紧凑信息行：版本 · 加载器 · 最后游玩 · 占用大小；无法识别时整行用错误色。
                    Text(
                        instanceMetaLine(instance, directorySize, showNotes),
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
                InstanceTextAction(
                    "开始游戏",
                    onLaunch,
                    enabled = !busy && !instance.broken,
                    tint = MaterialTheme.colorScheme.primary,
                )
                InstanceTextAction(
                    if (instance.favorite) "已收藏" else "收藏",
                    onFavorite,
                    tint =
                        if (instance.favorite) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                InstanceTextAction("设置", onManage)
                InstanceTextAction("删除", onDelete, tint = MaterialTheme.colorScheme.error)
                // 触屏/键盘的等价入口：与右键菜单是同一份内容。菜单锚在这个按钮自身的 Box 上，
                // 右边缘对齐按钮，位置由 M3 弹窗自己限制在窗口内。
                Box {
                    SmallIconButton(
                        Icons.Outlined.MoreHoriz,
                        "更多操作 " + instance.name,
                        onClick = {
                            menuAnchor = InstanceMenuAnchor.Button
                            menuOpen = true
                        },
                        size = 28.dp,
                    )
                    if (menuAnchor == InstanceMenuAnchor.Button) {
                        InstanceContextMenu(
                            expanded = menuOpen,
                            offset = ButtonMenuOffset,
                            instance = instance,
                            busy = busy,
                            onDismiss = { menuOpen = false },
                            onLaunch = onLaunch,
                            onRename = onRename,
                            onCopy = onCopy,
                            onOpenFolder = {
                                actions.run {
                                    actions.controller.openFolder(
                                        instance.id,
                                    )
                                }
                            },
                            onExport = onExport,
                            onDelete = onDelete,
                        )
                    }
                }
            }
        }
        if (menuAnchor == InstanceMenuAnchor.Pointer) {
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
}
