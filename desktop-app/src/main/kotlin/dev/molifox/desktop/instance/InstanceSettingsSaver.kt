// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 实例设置页签的自动保存与名称提交：页面级状态仍由页面持有，这里集中写盘逻辑。

package dev.molifox.desktop.instance

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import dev.molifox.core.GameInstance
import dev.molifox.core.validateInstanceDirectoryName
import dev.molifox.desktop.UiActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

/** 设置页签的保存结果与名称提交状态；按实例 id 重建，切换实例时不串用提示。 */
internal class InstanceSettingsSaver(
    private val actions: UiActions,
    private val scope: CoroutineScope,
) {
    var saveError by mutableStateOf<String?>(null)
    var nameNotice by mutableStateOf<String?>(null)

    // 名称提交（可能移动目录）进行中：期间不发起自动保存，避免把旧名称写回记录。
    var nameCommitting by mutableStateOf(false)

    // 提交名称：只有真的变化才调用 renameInstance，失败或非法时回退到目录当前名称。
    fun commitName(
        instance: GameInstance,
        currentDraft: () -> GameInstance,
        snapshot: GameInstance,
        busy: Boolean,
        onDraftChange: (GameInstance) -> Unit,
    ) {
        if (nameCommitting) return
        val draft = currentDraft()
        val trimmed = draft.name.trim()
        if (trimmed == instance.name) {
            if (draft.name != trimmed) onDraftChange(draft.copy(name = trimmed))
            nameNotice = null
            return
        }
        val problem =
            runCatching { validateInstanceDirectoryName(trimmed) }.exceptionOrNull()?.message
        if (problem != null || busy) {
            onDraftChange(draft.copy(name = instance.name))
            nameNotice =
                if (problem != null) {
                    "名称无效，已恢复为「${instance.name}」：$problem"
                } else {
                    "请等待当前任务结束后再重命名。"
                }
            return
        }
        val candidate = snapshot.copy(name = trimmed)
        nameCommitting = true
        scope.launch {
            var renamedId: String? = null
            val failure =
                runCatching {
                    withContext(Dispatchers.IO) {
                        val renamed = actions.controller.renameInstance(instance.id, trimmed)
                        renamedId = renamed.id
                        // 目录改名会立刻让旧 id 失效；这里在同一个同步段内改导航目标，
                        // 免得详情页先被卸载、协程被取消而回落到实例列表。
                        if (actions.navigation.editing == instance.id) {
                            actions.navigation.editing = renamed.id
                        }
                        actions.controller.saveInstanceConfiguration(
                            candidate.copy(id = renamed.id),
                            notify = false,
                        )
                    }
                }.exceptionOrNull()
                    ?.message
            withContext(Dispatchers.Swing) {
                nameCommitting = false
                if (failure == null) {
                    nameNotice = null
                    saveError = null
                } else {
                    nameNotice =
                        if (renamedId != null) {
                            "名称已改为「$trimmed」，但其它设置未能保存：$failure"
                        } else {
                            "重命名失败，已恢复为「${instance.name}」：$failure"
                        }
                    if (renamedId == null) onDraftChange(currentDraft().copy(name = instance.name))
                    saveError = null
                }
            }
        }
    }
}

/** 自动保存：设置页的任何改动立即写盘；离开页面时补写还没到防抖时间的有效改动。 */
@Composable
internal fun InstanceSettingsAutoSave(
    instance: GameInstance,
    actions: UiActions,
    saver: InstanceSettingsSaver,
    snapshot: GameInstance,
    busy: Boolean,
) {
    LaunchedEffect(instance.id, snapshot, busy, saver.nameCommitting) {
        if (busy || saver.nameCommitting || snapshot == instance) return@LaunchedEffect
        // 连续输入合并成一次写盘；核心会再次校验，非法值不会落盘。
        delay(300)
        if (saver.nameCommitting) return@LaunchedEffect
        val failure =
            runCatching {
                withContext(Dispatchers.IO) {
                    // 名称以记录当前值为准：重命名进行中也不会把旧名称写回去。
                    val currentName =
                        actions.controller.store
                            .instance(instance.id)
                            .name
                    actions.controller.saveInstanceConfiguration(
                        snapshot.copy(name = currentName),
                        notify = false,
                    )
                }
            }.exceptionOrNull()
                ?.message
        saver.saveError = failure
    }
    // 离开页面时补写还没到防抖时间的有效改动；非法值仍由核心拒绝，不会落盘。
    val latestEdit by rememberUpdatedState(snapshot)
    val latestInstance by rememberUpdatedState(instance)
    DisposableEffect(instance.id) {
        onDispose {
            val pending = latestEdit
            if (!actions.controller.isInstanceBusy(pending.id) && pending != latestInstance) {
                actions.controller.scope.launch {
                    runCatching {
                        val currentName =
                            actions.controller.store
                                .instance(pending.id)
                                .name
                        actions.controller.saveInstanceConfiguration(
                            pending.copy(name = currentName),
                            notify = false,
                        )
                    }
                }
            }
        }
    }
}
