// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 只列导出内容并提交后台任务；页面不生成预览或查询远程来源。

package dev.molifox.desktop

import com.google.gson.JsonParseException
import dev.molifox.core.GameInstance
import dev.molifox.core.MrpackExportMetadata
import dev.molifox.core.PackExportFormat
import dev.molifox.core.PackExportOptions
import dev.molifox.core.PackExportRequest
import dev.molifox.core.PackExportScan
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.nio.file.Path

/** 顶层条目先显示；后台统计不阻止用户确认选择并开始导出。 */
internal suspend fun collectExportScan(
    actions: UiActions,
    instanceId: String,
    onScan: (PackExportScan) -> Unit,
    onError: (String?) -> Unit,
) {
    try {
        actions.controller.content
            .exportEntries(instanceId)
            .collect { onScan(it) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: IOException) {
        onError(failure.message ?: tr("dialogs.export.readDirFailed"))
    } catch (failure: JsonParseException) {
        onError(failure.message ?: tr("dialogs.export.readDirFailed"))
    } catch (failure: IllegalStateException) {
        onError(failure.message ?: tr("dialogs.export.readDirFailed"))
    } catch (failure: IllegalArgumentException) {
        onError(failure.message ?: tr("dialogs.export.readDirFailed"))
    }
}

/** 勾选、格式与开关固定在请求中，目录读取和可选来源匹配在任务里执行。 */
internal fun startMrpackExport(
    actions: UiActions,
    instance: GameInstance,
    destination: Path,
    metadata: MrpackExportMetadata,
    format: PackExportFormat,
    options: PackExportOptions,
    selectedEntries: Set<String>,
    excludedPaths: Set<String>,
    includeLogs: Boolean,
    onStarted: () -> Unit,
    onDismiss: () -> Unit,
    onFailure: (Exception) -> Unit,
) {
    actions.run {
        try {
            actions.controller.exportMrpack(
                PackExportRequest(
                    instanceId = instance.id,
                    metadata = metadata,
                    destination = destination,
                    selectedEntries = selectedEntries,
                    excludedPaths = excludedPaths,
                    format = format,
                    options = exportEffectiveOptions(options, selectedEntries, includeLogs),
                ),
            )
            onStarted()
            onDismiss()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            onFailure(failure)
        } catch (failure: JsonParseException) {
            onFailure(failure)
        } catch (failure: IllegalStateException) {
            onFailure(failure)
        } catch (failure: IllegalArgumentException) {
            onFailure(failure)
        }
    }
}
