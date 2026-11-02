// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.desktop

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/** 列表页共享的瞬时选择状态；只保存当前可见内容的稳定键，不读写业务数据。 */
@Stable
internal class ManagedSelection {
    var selected by mutableStateOf<Set<String>>(emptySet())
        private set

    val bounds = mutableMapOf<String, Rect>()
    var containerOrigin = Offset.Zero
    private var anchor: String? = null
    private var dragBase: Set<String> = emptySet()
    private var dragAnchor: String? = null

    fun retain(keys: List<String>) {
        val valid = keys.toSet()
        selected = selected.intersect(valid)
        bounds.keys.retainAll(valid)
        if (anchor !in valid) anchor = null
    }

    fun clear() {
        selected = emptySet()
        anchor = null
    }

    fun all(keys: List<String>) {
        selected = keys.toSet()
        anchor = keys.firstOrNull()
    }

    fun selectForMenu(key: String) {
        if (key !in selected) selected = setOf(key)
        anchor = key
    }

    fun modifiedClick(
        key: String,
        keys: List<String>,
        toggle: Boolean,
        extend: Boolean,
    ) {
        selected =
            when {
                extend && anchor != null -> range(keys, anchor, key)
                toggle && key in selected -> selected - key
                toggle -> selected + key
                else -> setOf(key)
            }
        if (!extend) anchor = key
    }

    fun beginDrag(
        key: String,
        additive: Boolean,
    ) {
        dragAnchor = key
        dragBase = if (additive) selected else emptySet()
    }

    fun dragTo(
        key: String?,
        keys: List<String>,
    ) {
        val start = dragAnchor ?: return
        val end = key ?: return
        selected = dragBase + range(keys, start, end)
        anchor = start
    }

    fun keyAt(windowPosition: Offset): String? = bounds.entries.firstOrNull { (_, rect) -> windowPosition in rect }?.key

    private fun range(
        keys: List<String>,
        first: String?,
        last: String,
    ): Set<String> {
        val start = keys.indexOf(first)
        val end = keys.indexOf(last)
        if (start < 0 || end < 0) return setOf(last)
        return keys.subList(minOf(start, end), maxOf(start, end) + 1).toSet()
    }
}

/**
 * 直接按住左键跨行拖动即可多选。只有超过系统拖动阈值后才消费事件，普通点击仍交给行内详情。
 */
internal fun Modifier.dragSelectRows(
    selection: ManagedSelection,
    keys: List<String>,
): Modifier =
    onGloballyPositioned { selection.containerOrigin = it.boundsInWindow().topLeft }
        .onPreviewKeyEvent { event ->
            selection.handleKey(event.type, event.key, event.isCtrlPressed, event.isMetaPressed, keys)
        }.pointerInput(selection, keys) { awaitEachGesture { handleRowGesture(selection, keys) } }

private fun ManagedSelection.handleKey(
    type: KeyEventType,
    key: Key,
    control: Boolean,
    meta: Boolean,
    keys: List<String>,
): Boolean =
    when {
        type != KeyEventType.KeyDown -> false

        key == Key.Escape -> {
            clear()
            true
        }

        key == Key.A && (control || meta) -> {
            all(keys)
            true
        }

        else -> false
    }

private suspend fun AwaitPointerEventScope.handleRowGesture(
    selection: ManagedSelection,
    keys: List<String>,
) {
    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    val startKey = selection.keyAt(down.position + selection.containerOrigin) ?: return
    val event = awaitPointerEvent(PointerEventPass.Initial)
    if (!event.buttons.isPrimaryPressed) return
    val toggle = event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed
    val extend = event.keyboardModifiers.isShiftPressed
    val dragging = trackRowDrag(selection, keys, startKey, down, event, toggle)
    if (!dragging && (toggle || extend)) {
        selection.modifiedClick(startKey, keys, toggle, extend)
        down.consume()
    }
}

private suspend fun AwaitPointerEventScope.trackRowDrag(
    selection: ManagedSelection,
    keys: List<String>,
    startKey: String,
    down: PointerInputChange,
    initialEvent: PointerEvent,
    additive: Boolean,
): Boolean {
    var event = initialEvent
    var dragging = false
    while (event.buttons.isPrimaryPressed) {
        val current = event.changes.firstOrNull { it.id == down.id } ?: return dragging
        if (!dragging && (current.position - down.position).getDistance() > viewConfiguration.touchSlop) {
            selection.beginDrag(startKey, additive)
            dragging = true
        }
        if (dragging) {
            current.consume()
            selection.dragTo(selection.keyAt(current.position + selection.containerOrigin), keys)
        }
        event = awaitPointerEvent(PointerEventPass.Initial)
    }
    if (dragging) event.changes.forEach { it.consume() }
    return dragging
}
