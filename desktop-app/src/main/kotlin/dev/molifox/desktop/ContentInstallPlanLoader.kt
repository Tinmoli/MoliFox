// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责资源安装计划的读取、版本选择与安装排队：只做数据准备，不渲染界面。

package dev.molifox.desktop

import com.google.gson.JsonParseException
import dev.molifox.core.ContentDetails
import dev.molifox.core.ContentHit
import dev.molifox.core.ContentInstallPlan
import dev.molifox.core.ContentSource
import dev.molifox.core.ContentVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import java.io.IOException

/** 版本更新日志：网络、JSON 解析与版本校验错误由对话框提示，不中断页面。 */
internal suspend fun loadChangelog(
    actions: UiActions,
    hit: ContentHit,
    instanceId: String,
    versionId: String,
): Result<ContentVersion> =
    try {
        Result.success(
            withContext(Dispatchers.IO) {
                actions.controller.content.catalog
                    .versionDetails(hit.id, versionId, instanceId)
            },
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: IOException) {
        Result.failure(failure)
    } catch (failure: JsonParseException) {
        Result.failure(failure)
    } catch (failure: IllegalStateException) {
        Result.failure(failure)
    } catch (failure: IllegalArgumentException) {
        Result.failure(failure)
    }

/** 安装计划与自动选定的兼容版本：同时带回首次读取的项目详情。 */
internal class LoadedInstallPlan(
    val details: ContentDetails,
    val versionId: String,
    val plan: ContentInstallPlan,
)

/** 读取项目详情并生成安装计划；写入目录按用户确认的文件夹替换，其余校验保持原样。 */
internal suspend fun loadInstallPlan(
    actions: UiActions,
    hit: ContentHit,
    instanceId: String,
    selected: Set<String>,
    versionId: String,
    knownDetails: ContentDetails?,
    source: ContentSource?,
    relatedSources: List<ContentSource>,
    destinationFolder: String?,
): Result<LoadedInstallPlan> =
    try {
        Result.success(
            withContext(Dispatchers.IO) {
                val details =
                    knownDetails
                        ?: actions.controller.content.catalog
                            .details(hit.id, instanceId)
                val resolvedVersionId = resolveContentVersionId(details, versionId)
                require(resolvedVersionId.isNotBlank()) { "没有兼容当前游戏版本与加载器的版本" }
                val planned =
                    actions.controller.content.planContent(
                        hit,
                        instanceId,
                        selected,
                        resolvedVersionId,
                        mergedContentSources(relatedSources, source),
                    )
                LoadedInstallPlan(
                    details,
                    resolvedVersionId,
                    applyDestinationFolder(planned, destinationFolder),
                )
            },
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: IOException) {
        Result.failure(failure)
    } catch (failure: JsonParseException) {
        Result.failure(failure)
    } catch (failure: IllegalStateException) {
        Result.failure(failure)
    } catch (failure: IllegalArgumentException) {
        Result.failure(failure)
    }

/** 没有指定版本时按「正式版优先，其次第一个兼容版本」自动选择。 */
private fun resolveContentVersionId(
    details: ContentDetails,
    versionId: String,
): String {
    if (versionId.isNotBlank()) return versionId
    val versions = details.versions
    return (versions.firstOrNull { it.channel == "release" } ?: versions.firstOrNull())
        ?.id
        .orEmpty()
}

/** 用户确认的目录就是实际写入的目录：只替换目录名，依赖、哈希与账本校验保持原样。 */
private fun applyDestinationFolder(
    planned: ContentInstallPlan,
    destinationFolder: String?,
): ContentInstallPlan =
    if (destinationFolder == null) {
        planned
    } else {
        planned.copy(
            files = planned.files.map { it.copy(folder = destinationFolder) },
        )
    }

/** 来源去重：同一项目、目录与文件名只保留一条，顺序沿用调用方给出的来源。 */
private fun mergedContentSources(
    relatedSources: List<ContentSource>,
    source: ContentSource?,
): List<ContentSource> =
    (relatedSources + listOfNotNull(source)).distinctBy {
        Triple(it.projectId, it.folder, it.filename)
    }

/** 开始安装只是排队校验；真正的下载与写入在任务中心，失败由任务状态展示。 */
internal suspend fun installContentPlan(
    actions: UiActions,
    plan: ContentInstallPlan,
): Result<Unit> =
    try {
        actions.controller.installContent(plan)
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: IllegalStateException) {
        Result.failure(failure)
    } catch (failure: IllegalArgumentException) {
        Result.failure(failure)
    }

/** 只有计划就绪、没有加载中或错误、且来源未被锁定时才允许安装。 */
internal fun installEnabled(
    loading: Boolean,
    error: String?,
    plan: ContentInstallPlan?,
    source: ContentSource?,
): Boolean = !loading && error == null && plan != null && source?.locked != true
