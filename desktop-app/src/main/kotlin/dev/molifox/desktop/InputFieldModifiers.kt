// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 文本输入框共用的交互修饰链：外部聚焦入口、失焦回调、回车提交与无障碍名。
// 这些与主题配色无关，独立成文件避免挤占 Theme/SettingsComponents 的单文件函数上限。

package dev.molifox.desktop

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** 输入框的无障碍名：可见标签优先，其次仅语义的 description；两者都缺时保持默认命名。 */
internal fun fieldA11yName(
    label: String,
    description: String,
): Modifier =
    when {
        label.isNotBlank() -> Modifier.semantics { contentDescription = label }
        description.isNotBlank() -> Modifier.semantics { contentDescription = description }
        else -> Modifier
    }

/** 输入框修饰链：外部聚焦入口、失焦回调、回车提交与无障碍名都是可选槽，缺省直接跳过。 */
internal fun fieldInteractionModifiers(
    focusRequester: FocusRequester?,
    onFocusChange: ((Boolean) -> Unit)?,
    onSubmit: (() -> Unit)?,
    label: String,
    description: String,
): Modifier =
    Modifier
        .fillMaxWidth()
        .then(
            if (focusRequester == null) {
                Modifier
            } else {
                Modifier.focusRequester(focusRequester)
            },
        ).then(
            if (onFocusChange == null) {
                Modifier
            } else {
                Modifier.onFocusChanged { onFocusChange(it.isFocused) }
            },
        ).then(
            if (onSubmit == null) {
                Modifier
            } else {
                Modifier.submitOnEnter(onSubmit)
            },
        ).then(fieldA11yName(label, description))

/** 桌面端物理回车提交：BasicTextField 不一定把回车转成 IME Done，这里显式兜住。 */
private fun Modifier.submitOnEnter(onSubmit: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown &&
            (event.key == Key.Enter || event.key == Key.NumPadEnter)
        ) {
            onSubmit()
            true
        } else {
            false
        }
    }
