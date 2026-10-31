// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责导出对话框的目录扫描、预览读取与导出启动：失败交给界面提示。

package dev.molifox.desktop

import com.google.gson.JsonParseException
import dev.molifox.core.GameInstance
import dev.molifox.core.MrpackExportMetadata
import dev.molifox.core.MrpackExportPlan
import dev.molifox.core.PackExportFormat
import dev.molifox.core.PackExportOptions
import dev.molifox.core.PackExportScan
import dev.molifox.core.exportLocalResourcePaths
import dev.molifox.core.exportSelectedPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path

/** 协程取消必须原样抛出，不能当成普通导出失败显示在界面上。 */
private fun rethrowCancellation(cancelled: CancellationException): Nothing = throw cancelled

/** 逐项扫描实例目录，边扫描边把快照交给界面。 */
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
        rethrowCancellation(cancelled)
    } catch (failure: IOException) {
        onError(failure.message ?: "无法读取实例目录")
    } catch (failure: JsonParseException) {
        onError(failure.message ?: "无法读取实例目录")
    } catch (failure: IllegalStateException) {
        onError(failure.message ?: "无法读取实例目录")
    } catch (failure: IllegalArgumentException) {
        onError(failure.message ?: "无法读取实例目录")
    }
}

/** 打开即预览：只读扫描实例目录，并按哈希查询可用的下载来源。 */
internal suspend fun loadExportPreview(
    actions: UiActions,
    instanceId: String,
    stem: String,
    format: PackExportFormat,
    remoteResources: Boolean,
    includeLogs: Boolean,
    onPlan: (MrpackExportPlan) -> Unit,
    onError: (String?) -> Unit,
) {
    try {
        onPlan(
            withContext(Dispatchers.IO) {
                actions.controller.content.previewMrpackExport(
                    instanceId,
                    MrpackExportMetadata(stem, "1.0.0"),
                    resolveDownloads = format == PackExportFormat.Modrinth && remoteResources,
                    resolveCurseForge = false,
                    includeLogs = includeLogs,
                )
            },
        )
    } catch (cancelled: CancellationException) {
        rethrowCancellation(cancelled)
    } catch (failure: IOException) {
        onError(failure.message ?: "无法读取实例目录")
    } catch (failure: JsonParseException) {
        onError(failure.message ?: "无法读取实例目录")
    } catch (failure: IllegalStateException) {
        onError(failure.message ?: "无法读取实例目录")
    } catch (failure: IllegalArgumentException) {
        onError(failure.message ?: "无法读取实例目录")
    }
}

/** 名称、版本或日志勾选改过时重新预览，确保清单与当前输入一致。 */
internal suspend fun freshExportPlan(
    actions: UiActions,
    instanceId: String,
    current: MrpackExportPlan,
    metadata: MrpackExportMetadata,
    format: PackExportFormat,
    remoteResources: Boolean,
    includeLogs: Boolean,
): MrpackExportPlan =
    if (current.metadata == metadata &&
        current.includesLogs == includeLogs &&
        current.matchesRemoteMode(format, remoteResources)
    ) {
        current
    } else {
        withContext(Dispatchers.IO) {
            actions.controller.content.previewMrpackExport(
                instanceId,
                metadata,
                resolveDownloads = format == PackExportFormat.Modrinth && remoteResources,
                resolveCurseForge = false,
                includeLogs = includeLogs,
            )
        }
    }

/** 快照里的远程来源是否与当前开关一致；不一致就重新预览，避免导出旧远程快照。 */
private fun MrpackExportPlan.matchesRemoteMode(
    format: PackExportFormat,
    remoteResources: Boolean,
): Boolean =
    when (format) {
        PackExportFormat.Modrinth ->
            if (!remoteResources) {
                files.none { it.downloadUrl != null || it.curseForgeProjectId != null }
            } else {
                files.none { it.curseForgeProjectId != null }
            }
        PackExportFormat.CurseForge ->
            remoteResources || files.none { it.curseForgeProjectId != null }
        else -> true
    }

/** 真正的写文件仍由任务队列执行，失败交给界面提示。 */
internal fun startMrpackExport(
    actions: UiActions,
    instance: GameInstance,
    current: MrpackExportPlan,
    destination: Path,
    metadata: MrpackExportMetadata,
    format: PackExportFormat,
    options: PackExportOptions,
    selectedEntries: Set<String>,
    includeLogs: Boolean,
    onStarted: () -> Unit,
    onDismiss: () -> Unit,
    onFailure: (Exception) -> Unit,
) {
    actions.run {
        try {
            val fresh =
                freshExportPlan(
                    actions,
                    instance.id,
                    current,
                    metadata,
                    format,
                    options.remoteResources,
                    includeLogs,
                )
            val chosen = exportEffectiveOptions(options, selectedEntries, includeLogs)
            val local = exportLocalResourcePaths(fresh, format)
            actions.controller.exportMrpack(
                fresh,
                destination,
                exportSelectedPaths(fresh, format, chosen, selectedEntries),
                local,
                format,
                chosen,
            )
            onStarted()
            onDismiss()
        } catch (cancelled: CancellationException) {
            rethrowCancellation(cancelled)
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
