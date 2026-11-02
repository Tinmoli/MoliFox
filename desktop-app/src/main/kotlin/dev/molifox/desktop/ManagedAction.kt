// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.onClick
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal class ManagedAction(
    val title: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val run: () -> Unit,
)

/** 记录行坐标、绘制选择边框并在鼠标位置显示右键菜单。 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ManagedItemMenu(
    key: String,
    selection: ManagedSelection,
    actions: () -> List<ManagedAction>,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    var open by remember { mutableStateOf(false) }
    var offset by remember { mutableStateOf(DpOffset.Zero) }
    DisposableEffect(key) { onDispose { selection.bounds.remove(key) } }
    Box(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                selection.bounds[key] = coordinates.boundsInWindow()
            }.semantics { selected = key in selection.selected }
            .onPointerEvent(PointerEventType.Press) { event ->
                if (event.buttons.isSecondaryPressed) {
                    selection.selectForMenu(key)
                    val point = event.changes.firstOrNull()?.position ?: androidx.compose.ui.geometry.Offset.Zero
                    offset = with(density) { DpOffset(point.x.toDp(), point.y.toDp()) }
                    open = true
                    event.changes.forEach { it.consume() }
                }
            },
    ) {
        content()
        // 选中底色画在内容之上：行本身是不透明 Surface，画在下面会被完全遮住，只剩边框可见。
        if (key in selection.selected) {
            val shape = RoundedCornerShape(Metrics.cardRadius)
            Box(
                Modifier.matchParentSize().clip(shape).background(selectedOverlayColor()).border(
                    BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = .85f)),
                    shape,
                ),
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            offset = offset,
            modifier = Modifier.widthIn(min = 180.dp),
        ) {
            actions().forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.title) },
                    leadingIcon = { Icon(action.icon, null, Modifier.size(18.dp)) },
                    enabled = action.enabled,
                    onClick = {
                        open = false
                        action.run()
                    },
                )
            }
        }
    }
}

/** 选中行的覆盖层颜色：主题色 14%（浅）/ 20%（深），盖在内容之上仍看得清文字。 */
@Composable
private fun selectedOverlayColor(): Color =
    MaterialTheme.colorScheme.primary.copy(alpha = if (isDarkScheme()) .20f else .14f)

/** 多选动作栏：数量与操作排成一行，尽量少占高度，把空间留给下面的列表。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SelectionActionBar(
    selection: ManagedSelection,
    keys: List<String>,
    actions: List<ManagedAction> = emptyList(),
) {
    if (!selection.active) return
    val strings = LocalStrings.current
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Metrics.innerRadius),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                strings.t("selection.count", selection.selected.size),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 6.dp),
            )
            FlowRow(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                TextChip(strings.t("selection.all")) { selection.all(keys) }
                TextChip(strings.t("selection.clear")) { selection.clear() }
                actions.forEach { action -> BatchActionChip(action) }
            }
        }
    }
}

/** 纯文字小块：全选、清除选择这类不加图标也能看懂的操作用它，比带图标按钮矮一截。 */
@Composable
private fun TextChip(
    title: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .85f),
    ) {
        Text(
            title,
            Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 批量操作小按钮：比 TextButton 紧凑，一行能放下四五个。 */
@Composable
private fun BatchActionChip(action: ManagedAction) {
    Surface(
        onClick = action.run,
        enabled = action.enabled,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = if (action.enabled) .85f else .4f),
    ) {
        Row(
            Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(
                action.icon,
                null,
                Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = if (action.enabled) 1f else .45f),
            )
            Text(
                action.title,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (action.enabled) 1f else .45f),
            )
        }
    }
}
