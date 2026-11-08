// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 把非响应式的忙碌判定接进组合：isInstanceBusy / busy 读的是快照值，
// 必须挂在 state 变化上重算，否则任务开始/结束时按钮不刷新。

package dev.molifox.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

/**
 * 组合期可响应的实例忙碌状态。任务列表或实例数据变化时重算；
 * 纯日志刷新不会重建这份缓存，不影响频繁重组的页面。
 */
@Composable
internal fun rememberInstanceBusy(
    actions: UiActions,
    instanceId: String,
): Boolean {
    val state by actions.controller.state.collectAsState()
    return remember(state.jobs, state.data) { actions.controller.isInstanceBusy(instanceId) }
}

/** 组合期可响应的全局忙碌状态：任务入队/出队都会更新 state.jobs，据此重算。 */
@Composable
internal fun rememberLauncherBusy(actions: UiActions): Boolean {
    val state by actions.controller.state.collectAsState()
    return remember(state.jobs) { actions.controller.busy }
}
