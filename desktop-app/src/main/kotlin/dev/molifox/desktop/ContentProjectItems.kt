// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责资源项目对话框的列表条目与下载动作：条目只渲染，下载交给调用方确认。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import dev.molifox.core.ContentDependency
import dev.molifox.core.ContentDetails
import dev.molifox.core.ContentHit
import dev.molifox.core.ContentVersion
import dev.molifox.core.GameInstance

/** 安装按钮：选好实例/存档后直接排队，进度只进任务中心；整合包下载完直接安装，不再预览确认。 */
internal fun contentDownload(
    actions: UiActions,
    hit: ContentHit,
    version: ContentVersion,
    target: GameInstance?,
    ui: ContentProjectUiState,
    onInstallStarted: () -> Unit,
    onError: (String) -> Unit,
) {
    if (hit.type == "modpack") {
        // 下载页选中的整合包直接走下载 + 导入；可选文件按安装器默认全部包含。
        actions.run { actions.controller.installContent(hit, null) }
        onInstallStarted()
        return
    }
    val instance = target ?: return
    when (hit.type) {
        "world" -> {
            actions.run { actions.controller.installWorld(hit, version.id, instance.id, null) }
            onInstallStarted()
        }

        "datapack" ->
            downloadDatapack(
                actions,
                hit,
                version,
                instance,
                ui,
                onInstallStarted,
                onError,
            )

        else -> installContentToInstance(actions, hit, version, instance, ui, onInstallStarted, onError)
    }
}

/** 数据包项目的两个安装分支：模组版走普通模组安装，数据包版必须写入具体存档。 */
private fun downloadDatapack(
    actions: UiActions,
    hit: ContentHit,
    version: ContentVersion,
    instance: GameInstance,
    ui: ContentProjectUiState,
    onInstallStarted: () -> Unit,
    onError: (String) -> Unit,
) {
    if (contentVersionInstallKind(hit, version) == "mod") {
        installContentToInstance(
            actions,
            hit.copy(type = "mod"),
            version,
            instance,
            ui,
            onInstallStarted,
            onError,
        )
        return
    }
    if (ui.worldName.isBlank()) {
        onError(tr("dialogs.content.noWorlds"))
        return
    }
    actions.run { actions.controller.installDatapack(hit, version.id, instance.id, ui.worldName) }
    onInstallStarted()
}

/** 普通内容直接排队安装；发现同名冲突时先挂起计划，等用户选择替换或跳过。 */
private fun installContentToInstance(
    actions: UiActions,
    hit: ContentHit,
    version: ContentVersion,
    instance: GameInstance,
    ui: ContentProjectUiState,
    onInstallStarted: () -> Unit,
    onError: (String) -> Unit,
) {
    val instanceRoot = runCatching { actions.controller.store.instancePath(instance.id) }.getOrNull()
    val destinationFolder = contentFolderOf(instanceRoot, ui.destination)
    actions.run {
        ui.installing = true
        ui.installError = null
        try {
            val loaded =
                loadInstallPlan(
                    actions = actions,
                    hit = hit,
                    instanceId = instance.id,
                    selected = emptySet(),
                    versionId = version.id,
                    knownDetails = ui.details,
                    source = null,
                    relatedSources = emptyList(),
                    destinationFolder = destinationFolder,
                ).getOrElse {
                    onError(it.message ?: tr("dialogs.content.project.unavailable"))
                    return@run
                }
            // 依赖默认不装：开关打开时才把未安装的依赖随主文件一起装。
            val selected = selectedDependencyIds(ui, loaded.plan)
            installOrConfirmPlan(
                actions = actions,
                ui = ui,
                plan = planWithSelectedDependencies(loaded.plan, selected),
                instanceRoot = instanceRoot,
                onInstallStarted = onInstallStarted,
                onError = onError,
            )
        } finally {
            ui.installing = false
        }
    }
}

/** 另存为：只下载选中版本的主文件，不安装、不写实例目录。 */
internal fun saveContentAs(
    actions: UiActions,
    hit: ContentHit,
    version: ContentVersion,
) {
    val extension = contentSaveExtension(hit, version)
    actions.run {
        val destination =
            actions
                .choose(
                    title = tr("deskD.content.saveAsTitle", hit.title),
                    save = true,
                    extensions = listOf(extension),
                    defaultName = contentSaveDefaultName(hit.title, extension),
                    initialDirectory = actions.controller.store.downloadDirectory,
                ).firstOrNull()
                ?: return@run
        actions.controller.saveContentFile(hit, version.id, destination)
    }
}

/** 另存为的默认扩展名；用户仍可在系统保存框里改文件名或选“所有文件”。 */
internal fun contentSaveExtension(
    hit: ContentHit,
    version: ContentVersion,
): String =
    when {
        hit.type == "mod" -> "jar"
        hit.type == "datapack" && contentVersionInstallKind(hit, version) == "mod" -> "jar"
        hit.type == "modpack" && hit.id.startsWith("curseforge:") -> "zip"
        hit.type == "modpack" -> "mrpack"
        else -> "zip"
    }

internal fun contentSaveDefaultName(
    title: String,
    extension: String,
): String {
    val safe = title.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "content" }
    return "$safe.$extension"
}

/** 依赖标签页：显示依赖项目的标题和简介，点整行进入该项目的详情。 */
internal fun LazyListScope.contentDependencyItems(
    dependencies: List<ContentDependency>,
    hits: Map<String, ContentHit>,
    onOpenProject: (ContentHit) -> Unit,
) {
    if (dependencies.isEmpty()) {
        item {
            Text(
                LocalStrings.current.t("dialogs.content.dependencies.empty"),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    items(dependencies, key = { "${it.projectId}:${it.versionId}:${it.type}" }) { dependency ->
        ContentDependencyRow(dependency, hits[dependency.projectId], onOpenProject)
    }
}

/** 项目介绍：Modrinth 的 body 是 Markdown（可能夹带内联 HTML），交给 MarkdownText 渲染。 */
internal fun LazyListScope.contentDescriptionItem(details: ContentDetails?) {
    item {
        val strings = LocalStrings.current
        // 这里不预先剥离标记，否则 ##、-、[文字](链接) 会退化成原文。
        val body = details?.body
        if (body == null) {
            Text(strings.t("deskD.content.loading"), style = MaterialTheme.typography.bodySmall)
        } else {
            MarkdownText(body.ifBlank { strings.t("deskD.content.noDescription") }, Modifier.fillMaxWidth())
        }
    }
}

/** 更新日志：错误优先，其次作者原文，最后是「还没选版本」的占位。 */
internal fun LazyListScope.contentChangelogItem(
    changelogLoading: Boolean,
    changelog: ContentVersion?,
    changelogError: String?,
    chosen: ContentVersion?,
) {
    item {
        val strings = LocalStrings.current
        if (changelogLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(
            changelogError
                ?: changelog?.changelog?.ifBlank { strings.t("deskD.content.noChangelog") }
                ?: if (chosen == null) {
                    strings.t("deskD.content.chooseVersion")
                } else {
                    strings.t("deskD.content.loadingChangelog")
                },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** 推荐版本卡片：没有正式版时标题改成测试文件，按钮仍然写入当前选择。 */
internal fun LazyListScope.contentRecommendationItem(
    recommendation: ContentVersion?,
    onChoose: (ContentVersion) -> Unit,
) {
    recommendation?.let { best ->
        item {
            val strings = LocalStrings.current
            Panel(Modifier.fillMaxWidth(), padding = 10) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (best.channel != "release") {
                                strings.t("deskD.content.onlyBeta")
                            } else if (best.featured) {
                                strings.t("deskD.content.featuredRelease")
                            } else {
                                strings.t("deskD.content.latestRelease")
                            },
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                        )
                        Text(
                            best.number.ifBlank { best.name },
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    MoliTextButton({ onChoose(best) }) { Text(strings.t("deskD.content.chooseRecommended")) }
                }
            }
        }
    }
}

/** 版本列表：空结果给出恢复默认筛选的入口，其余逐个渲染可点选行。 */
internal fun LazyListScope.contentVersionItems(
    loading: Boolean,
    versions: List<ContentVersion>,
    versionId: String,
    formatLabel: (ContentVersion) -> String?,
    onSelect: (ContentVersion) -> Unit,
    onResetFilters: () -> Unit,
) {
    if (!loading && versions.isEmpty()) {
        item {
            val strings = LocalStrings.current
            EmptyState(
                strings.t("deskD.content.noVersionsTitle"),
                strings.t("deskD.content.noVersionsHint"),
                Icons.Outlined.SearchOff,
            ) {
                MoliTextButton(onResetFilters) { Text(strings.t("deskD.content.showAllVersions")) }
            }
        }
    }
    items(versions, key = { it.id }) { version ->
        ContentProjectVersionRow(
            version = version,
            selected = version.id == versionId,
            formatLabel = formatLabel(version),
            onSelect = onSelect,
        )
    }
}
