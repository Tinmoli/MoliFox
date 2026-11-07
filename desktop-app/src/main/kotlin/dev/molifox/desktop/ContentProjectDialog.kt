// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责资源项目详情、版本选择和下载入口。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.ContentFilter
import dev.molifox.core.ContentHit
import dev.molifox.core.ContentVersion
import dev.molifox.core.GameInstance
import dev.molifox.core.LauncherState
import dev.molifox.core.filteredContentVersions
import dev.molifox.core.recommendedContentVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.io.path.isDirectory

/** Project browsing is available without an installation target, including old versions. */
@Composable
fun ContentProjectDialog(
    hit: ContentHit,
    state: LauncherState,
    actions: UiActions,
    initialFilter: ContentFilter,
    onDismiss: () -> Unit,
    onInstallStarted: () -> Unit,
) {
    // 从依赖点进来的项目会压栈；返回时回到上一个项目，关闭按钮始终关闭整个对话框。
    var stack by remember(hit.id) { mutableStateOf(listOf(hit)) }
    val currentHit = stack.last()
    val ui =
        remember(currentHit.id) {
            ContentProjectUiState(initialFilter, state.data.selectedInstanceId)
        }
    val clipboard = LocalClipboardManager.current
    ContentProjectEffects(ui, currentHit, actions)
    val versions =
        filteredContentVersions(
            ui.details?.versions.orEmpty(),
            ContentFilter(ui.gameVersion, ui.loader),
            ui.channel,
        ).sortedWith(newestVersionFirst)
    val target = state.data.instances.find { it.id == ui.targetId && it.installed }
    // 数据包项目优先推荐数据包 zip；项目只发布模组版时才回退到模组 jar。
    val datapackVersions =
        versions.filter {
            currentHit.type != "datapack" || contentVersionInstallKind(currentHit, it) == "datapack"
        }
    val recommendation =
        recommendedContentVersion(datapackVersions, target, currentHit.type)
            ?: recommendedContentVersion(versions, target, currentHit.type)
    ContentProjectVersionEffect(ui, currentHit, target, versions, recommendation, actions)
    val chosen = versions.find { it.id == ui.versionId }
    // 下载目标的默认值：实例内容目录（mods / resourcepacks / shaderpacks），
    // 数据包落到所选存档的 datapacks；没有实例时由用户从数据目录里自己挑。
    ContentProjectWorldEffect(ui, currentHit, target, chosen, actions)
    ContentProjectDependencyEffect(ui, currentHit, chosen, actions)
    // 同一页选好目标后直接排队；依赖由「同时安装依赖」开关决定，安装进度只进任务中心。
    ContentConflictEffect(ui, actions, onInstallStarted)
    val back: (() -> Unit)? =
        if (stack.size > 1) {
            { stack = stack.dropLast(1) }
        } else {
            null
        }
    Modal(
        currentHit.title,
        "",
        width = 740.dp,
        onDismiss = onDismiss,
        onBack = back,
        compact = true,
    ) {
        ContentProjectModalBody(
            hit = currentHit,
            state = state,
            actions = actions,
            ui = ui,
            clipboard = clipboard,
            versions = versions,
            target = target,
            recommendation = recommendation,
            chosen = chosen,
            onDismiss = onDismiss,
            onInstallStarted = onInstallStarted,
            onOpenProject = { dependency -> stack = stack + dependency },
        )
    }
}

@Composable
private fun ColumnScope.ContentProjectModalBody(
    hit: ContentHit,
    state: LauncherState,
    actions: UiActions,
    ui: ContentProjectUiState,
    clipboard: ClipboardManager,
    versions: List<ContentVersion>,
    target: GameInstance?,
    recommendation: ContentVersion?,
    chosen: ContentVersion?,
    onDismiss: () -> Unit,
    onInstallStarted: () -> Unit,
    onOpenProject: (ContentHit) -> Unit,
) {
    val strings = LocalStrings.current
    if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (ui.error != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ui.error.orEmpty(), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
            MoliTextButton({ ui.retry++ }) { Text(strings.t("dialogs.common.retry")) }
        }
    }
    Column(
        Modifier.fillMaxWidth().weight(1f),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ContentProjectHeader(hit, ui, actions, clipboard)
        ContentProjectFilters(hit, ui, chosen)
        // 版本列表吃掉剩余高度，避免依赖区和目标栏把可滚动区域越挤越小。
        ContentProjectTabList(
            hit,
            ui,
            versions,
            recommendation,
            chosen,
            dependencyHits = ui.dependencyHits,
            onOpenProject = onOpenProject,
            modifier = Modifier.weight(1f),
        )
    }
    // 目标栏固定在滚动区外，资源再多也不会把“安装到哪里”挤掉。
    ContentProjectTargetPickers(hit, state, ui, chosen)
    ui.installError?.let {
        Text(it, Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
    }
    if (ui.installing) LinearProgressIndicator(Modifier.fillMaxWidth())
    ContentProjectFooter(
        hit = hit,
        actions = actions,
        ui = ui,
        target = target,
        chosen = chosen,
        onDismiss = onDismiss,
        onInstallStarted = onInstallStarted,
    )
}

/** 项目详情与复制反馈：网络、JSON、目录与参数错误都只提示，不中断页面。 */
@Composable
private fun ContentProjectEffects(
    ui: ContentProjectUiState,
    hit: ContentHit,
    actions: UiActions,
) {
    val strings = LocalStrings.current
    LaunchedEffect(ui.copied) {
        if (ui.copied) {
            delay(1800)
            ui.copied = false
        }
    }
    LaunchedEffect(hit.id, ui.retry) {
        ui.loading = true
        ui.error = null
        try {
            resourceAttempt {
                ui.details =
                    withContext(Dispatchers.IO) {
                        actions.controller.content.catalog
                            .browse(hit.id)
                    }
            }.onFailure { failure ->
                ui.error = failure.message ?: strings.t("dialogs.content.project.unavailable")
            }
        } finally {
            if (isActive) ui.loading = false
        }
    }
}

/** 版本选择与更新日志：版本不可用时自动回退到推荐版本。 */
@Composable
private fun ContentProjectVersionEffect(
    ui: ContentProjectUiState,
    hit: ContentHit,
    target: GameInstance?,
    versions: List<ContentVersion>,
    recommendation: ContentVersion?,
    actions: UiActions,
) {
    LaunchedEffect(hit.id, ui.targetId, ui.gameVersion, ui.loader, ui.channel, ui.details) {
        val current = versions.find { it.id == ui.versionId }
        if (current == null || isUnusableFor(current, target, hit)) {
            ui.versionId = recommendation?.id.orEmpty()
        }
    }
    LaunchedEffect(hit.id, ui.versionId, ui.tab) {
        ui.changelog = null
        ui.changelogError = null
        ui.changelogLoading = false
        if (ui.tab != "changelog" || ui.versionId.isBlank()) return@LaunchedEffect
        ui.changelogLoading = true
        try {
            resourceAttempt {
                ui.changelog =
                    withContext(Dispatchers.IO) {
                        actions.controller.content.catalog
                            .browseVersion(hit.id, ui.versionId)
                    }
            }.onFailure { failure -> ui.changelogError = failure.message }
        } finally {
            if (isActive) ui.changelogLoading = false
        }
    }
}

/** 依赖标签页按需取项目名称；只解析尚未缓存的 projectId，失败时仍显示 id。 */
@Composable
private fun ContentProjectDependencyEffect(
    ui: ContentProjectUiState,
    hit: ContentHit,
    chosen: ContentVersion?,
    actions: UiActions,
) {
    LaunchedEffect(hit.id, ui.tab, chosen?.id) {
        if (ui.tab != "dependencies" || chosen == null) return@LaunchedEffect
        val ids =
            chosen.dependencies
                .map { it.projectId }
                .filter { it.isNotBlank() }
                .distinct()
        ui.dependencyHits = ui.dependencyHits.filterKeys { it in ids }
        val missing = ids.filter { it !in ui.dependencyHits }
        if (missing.isEmpty()) return@LaunchedEffect
        val loaded =
            withContext(Dispatchers.IO) {
                coroutineScope {
                    missing
                        .map { id ->
                            async {
                                runCatching {
                                    actions.controller.content.catalog
                                        .projectHit(id)
                                }.getOrNull()
                            }
                        }.awaitAll()
                }
            }
        ui.dependencyHits = ui.dependencyHits + loaded.filterNotNull().associateBy { it.id }
    }
}

/** 数据包的世界列表：切实例或切项目时重扫，失败只提示不阻断。 */
@Composable
private fun ContentProjectWorldEffect(
    ui: ContentProjectUiState,
    hit: ContentHit,
    target: GameInstance?,
    chosen: ContentVersion?,
    actions: UiActions,
) {
    val datapackTarget = contentTargetSaveEnabled(hit, chosen)
    LaunchedEffect(ui.targetId, hit.type, datapackTarget) {
        ui.worlds = emptyList()
        ui.worldName = ""
        ui.worldError = null
        if (!datapackTarget || target == null) return@LaunchedEffect
        resourceAttempt {
            ui.worlds =
                withContext(Dispatchers.IO) {
                    actions.controller.saves.listSaves(target.id).filter {
                        it.hasLevelDat && it.path.isDirectory()
                    }
                }
            ui.worldName =
                ui.worlds
                    .firstOrNull()
                    ?.name
                    .orEmpty()
        }.onFailure { failure -> ui.worldError = failure.message }
    }
}
