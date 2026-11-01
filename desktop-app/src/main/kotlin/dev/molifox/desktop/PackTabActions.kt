// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 资源包/光影页签的列表加载与操作封装，保持页面组合函数只负责布局。

package dev.molifox.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dev.molifox.core.DownloadJob
import dev.molifox.core.LocalPackEntry
import dev.molifox.core.PackFolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 读取实例内容目录；加载中/成功/失败都通过回调交回页面，避免页面里堆叠 try/catch。 */
@Composable
internal fun PackListEffect(
    instanceId: String,
    folder: PackFolder,
    refresh: Int,
    jobs: List<DownloadJob>,
    load: suspend () -> List<LocalPackEntry>,
    onLoading: () -> Unit,
    onLoaded: (List<LocalPackEntry>) -> Unit,
    onFailed: (String) -> Unit,
) {
    LaunchedEffect(instanceId, folder, refresh, jobs.map { it.id + ":" + it.status }) {
        onLoading()
        try {
            onLoaded(withContext(Dispatchers.IO) { load() })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IllegalStateException) {
            onFailed(failure.message ?: tr("pages.packs.readFailed", folder.title))
        } catch (failure: IllegalArgumentException) {
            onFailed(failure.message ?: tr("pages.packs.readFailed", folder.title))
        }
    }
}

/** 统一的更新/导入入队：调用方只关心成功 job id 或中文失败原因。 */
internal fun enqueuePackAction(
    action: () -> String,
    onJob: (String) -> Unit,
    onError: (String) -> Unit,
) {
    runCatching { action() }
        .onSuccess(onJob)
        .onFailure { failure -> onError(failure.message ?: tr("pages.common.actionFailed")) }
}

/** 可能匹配的包先让用户确认；普通更新直接执行。 */
internal fun confirmPackUpdate(
    item: dev.molifox.core.PackUpdateItem,
    onConfirm: (dev.molifox.core.PackUpdateItem) -> Unit,
    start: (dev.molifox.core.PackUpdateItem) -> Unit,
) {
    if (item.possibleMatch) onConfirm(item) else start(item)
}

/** 拖入/选择文件后导入；空选择直接忽略，异常转成工具行错误。 */
internal fun importPackSelection(
    files: List<java.nio.file.Path>,
    import: (List<java.nio.file.Path>) -> String,
    onJob: (String) -> Unit,
    onError: (String) -> Unit,
) {
    if (files.isEmpty()) return
    enqueuePackAction({ import(files) }, onJob, onError)
}
