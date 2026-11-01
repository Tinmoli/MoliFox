// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责资源项目详情、版本选择和下载入口。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import dev.molifox.core.ContentFilter
import dev.molifox.core.ContentHit
import dev.molifox.core.ContentVersion
import dev.molifox.core.GameInstance
import dev.molifox.core.LauncherState
import dev.molifox.core.filteredContentVersions
import dev.molifox.core.recommendedContentVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.file.Path
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
    val strings = LocalStrings.current
    val ui =
        remember(hit.id) {
            ContentProjectUiState(initialFilter, state.data.selectedInstanceId)
        }
    val clipboard = LocalClipboardManager.current
    val store = actions.controller.store
    ContentProjectEffects(ui, hit, actions)
    val versions =
        filteredContentVersions(
            ui.details?.versions.orEmpty(),
            ContentFilter(ui.gameVersion, ui.loader),
            ui.channel,
        ).sortedWith(newestVersionFirst)
    val target = state.data.instances.find { it.id == ui.targetId && it.installed }
    val recommendation = recommendedContentVersion(versions, target, hit.type)
    ContentProjectVersionEffect(ui, hit, target, versions, recommendation, actions)
    val chosen = versions.find { it.id == ui.versionId }
    val instanceRoot = target?.let { runCatching { store.instancePath(it.id) }.getOrNull() }
    // 下载目标的默认值：实例内容目录（mods / resourcepacks / shaderpacks），
    // 数据包落到所选存档的 datapacks；没有实例时由用户从数据目录里自己挑。
    val defaultDestination = contentDefaultDestination(instanceRoot, hit.type, ui.worldName)
    ContentProjectWorldEffect(ui, hit, target, actions)
    val installing = ui.installVersion
    // 数据包确认、世界确认与安装进度共用一层覆盖层：同一时间只显示其中一个。
    val overlayShown =
        when {
            ui.confirmDatapack && target != null && chosen != null -> {
                ContentDatapackConfirmDialog(
                    hit,
                    ui,
                    target,
                    chosen,
                    defaultDestination,
                    actions,
                    onInstallStarted,
                )
                true
            }
            ui.confirmWorld && target != null && chosen != null -> {
                ContentWorldConfirmDialog(hit, ui, target, chosen, actions, onInstallStarted)
                true
            }
            installing != null && target != null -> {
                ContentInstallDialog(
                    hit,
                    target.id,
                    actions,
                    { ui.installVersion = null },
                    onInstallStarted,
                    initialVersionId = installing.id,
                    destination = ui.destination,
                )
                true
            }
            else -> false
        }
    if (overlayShown) return
    Modal(
        hit.title,
        strings.t("dialogs.content.project.subtitle"),
        width = 740.dp,
        onDismiss = onDismiss,
    ) {
        ContentProjectModalBody(
            hit = hit,
            state = state,
            actions = actions,
            ui = ui,
            clipboard = clipboard,
            versions = versions,
            target = target,
            recommendation = recommendation,
            chosen = chosen,
            instanceRoot = instanceRoot,
            defaultDestination = defaultDestination,
            onDismiss = onDismiss,
            onInstallStarted = onInstallStarted,
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
    instanceRoot: Path?,
    defaultDestination: Path?,
    onDismiss: () -> Unit,
    onInstallStarted: () -> Unit,
) {
    val strings = LocalStrings.current
    if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (ui.error != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ui.error.orEmpty(), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
            TextButton({ ui.retry++ }) { Text(strings.t("dialogs.common.retry")) }
        }
    }
    Column(
        Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ContentProjectHeader(hit, ui, actions, clipboard)
        ContentProjectFilters(hit, ui)
        ContentProjectTabList(ui, versions, recommendation, chosen)
        ContentProjectTargetPickers(hit, state, ui)
    }
    ContentProjectFooter(
        hit = hit,
        actions = actions,
        ui = ui,
        target = target,
        chosen = chosen,
        instanceRoot = instanceRoot,
        defaultDestination = defaultDestination,
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
        if (current == null || isUnusableFor(current, target, hit.type)) {
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

/** 数据包的世界列表：切实例或切项目时重扫，失败只提示不阻断。 */
@Composable
private fun ContentProjectWorldEffect(
    ui: ContentProjectUiState,
    hit: ContentHit,
    target: GameInstance?,
    actions: UiActions,
) {
    LaunchedEffect(ui.targetId, hit.type) {
        ui.worlds = emptyList()
        ui.worldName = ""
        ui.worldError = null
        if (hit.type != "datapack" || target == null) return@LaunchedEffect
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
