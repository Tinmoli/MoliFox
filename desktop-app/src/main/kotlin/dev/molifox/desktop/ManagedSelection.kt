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
import androidx.compose.ui.input.pointer.PointerEventType
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

    /** 有选区时单击行改为切换选中，不再打开详情。 */
    val active: Boolean
        get() = selected.isNotEmpty()

    val bounds = mutableMapOf<String, Rect>()
    var containerOrigin = Offset.Zero

    /** 拖动中最后一次指针的窗口坐标；滚动后按它重算选区。 */
    var dragPointer: Offset? = null
    private var anchor: String? = null
    private var dragBase: Set<String> = emptySet()
    private var dragAnchor: String? = null
    private var dragSelects = true

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

    /** 单击切换单个键的选中状态；其余选择保持不变。 */
    fun toggle(key: String) {
        selected = if (key in selected) selected - key else selected + key
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

    /** 从未选行开始拖动会添加范围，从已选行开始则取消范围；整次手势都按开始前的选区重算。 */
    fun beginDrag(key: String) {
        dragAnchor = key
        dragBase = selected
        dragSelects = key !in dragBase
    }

    fun dragTo(
        key: String?,
        keys: List<String>,
    ) {
        val start = dragAnchor ?: return
        val end = key ?: return
        val dragged = range(keys, start, end)
        selected = if (dragSelects) dragBase + dragged else dragBase - dragged
        anchor = start
    }

    /**
     * 拖动中滚动会让行位置变化，但不会再产生指针事件；用上一次指针位置重算选区，
     * 滚轮因此可以边滚边选。
     */
    fun reapplyDrag(keys: List<String>) {
        val position = dragPointer ?: return
        dragTo(keyAt(position), keys)
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
 * 拖动期间滚动会改变行坐标，因此每轮指针事件都按上一次指针位置重算选区，滚轮可以边滚边选。
 */
internal fun Modifier.dragSelectRows(
    selection: ManagedSelection,
    keys: List<String>,
): Modifier =
    onGloballyPositioned { selection.containerOrigin = it.boundsInWindow().topLeft }
        .onPreviewKeyEvent { event ->
            selection.handleKey(event.type, event.key, event.isCtrlPressed, event.isMetaPressed, keys)
        }.pointerInput(selection, keys) {
            awaitEachGesture { handleRowGesture(selection, keys) }
        }

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
    val dragging = trackPointerDrag(selection, keys, startKey, down, event)
    if (!dragging && (toggle || extend)) {
        selection.modifiedClick(startKey, keys, toggle, extend)
        down.consume()
    }
}

private suspend fun AwaitPointerEventScope.trackPointerDrag(
    selection: ManagedSelection,
    keys: List<String>,
    startKey: String,
    down: PointerInputChange,
    initialEvent: PointerEvent,
): Boolean {
    var event = initialEvent
    var dragging = false
    while (event.type == PointerEventType.Scroll || event.buttons.isPrimaryPressed) {
        // 滚动只改变行坐标、不产生指针移动：按上一次指针位置重算，滚轮因此仍可扩展选区。
        if (dragging) selection.reapplyDrag(keys)
        // 滚轮事件直接放行，否则拖动期间列表滚不动。
        if (event.type == PointerEventType.Scroll) {
            event = awaitPointerEvent(PointerEventPass.Initial)
            continue
        }
        val current = event.changes.firstOrNull { it.id == down.id } ?: return dragging
        if (!dragging && (current.position - down.position).getDistance() > viewConfiguration.touchSlop) {
            selection.beginDrag(startKey)
            dragging = true
        }
        if (dragging) {
            current.consume()
            val window = current.position + selection.containerOrigin
            selection.dragPointer = window
            selection.dragTo(selection.keyAt(window), keys)
        }
        event = awaitPointerEvent(PointerEventPass.Initial)
    }
    if (dragging) event.changes.forEach { it.consume() }
    return dragging
}
