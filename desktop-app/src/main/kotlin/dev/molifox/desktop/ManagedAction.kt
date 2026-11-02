// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.onClick
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Clear
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
        if (key in selection.selected) {
            Surface(
                Modifier.matchParentSize(),
                shape = RoundedCornerShape(Metrics.cardRadius),
                color = MaterialTheme.colorScheme.primary.copy(alpha = .06f),
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = .75f)),
            ) {}
        }
        content()
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

@Composable
internal fun SelectionBar(
    selection: ManagedSelection,
    keys: List<String>,
) {
    if (selection.selected.isEmpty()) return
    val strings = LocalStrings.current
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Metrics.innerRadius),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(strings.t("selection.count", selection.selected.size), Modifier.weight(1f))
            RowIconAction(Icons.Outlined.Checklist, strings.t("selection.all")) { selection.all(keys) }
            RowIconAction(Icons.Outlined.Clear, strings.t("selection.clear")) { selection.clear() }
        }
    }
}
