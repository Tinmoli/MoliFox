// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay

private const val SETTINGS_SAVE_DELAY_MILLIS = 300L

/** 连续编辑共用防抖与离开时补写，具体校验、线程和错误提示由调用方处理。 */
@Composable
internal fun <T> SaveSettingsDraft(
    draft: T,
    saved: T,
    save: suspend (T) -> Unit,
    owner: Any = Unit,
    enabled: Boolean = true,
    flush: (T) -> Unit,
) = key(owner) {
    val latestSaved by rememberUpdatedState(saved)
    val latestDraft by rememberUpdatedState(draft)
    val latestSave by rememberUpdatedState(save)
    val latestFlush by rememberUpdatedState(flush)
    LaunchedEffect(draft, enabled) {
        if (!enabled || draft == latestSaved) return@LaunchedEffect
        delay(SETTINGS_SAVE_DELAY_MILLIS)
        if (draft != latestSaved) latestSave(draft)
    }
    DisposableEffect(Unit) {
        onDispose {
            val pending = latestDraft
            if (pending != latestSaved) latestFlush(pending)
        }
    }
}
