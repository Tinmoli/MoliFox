// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责资源项目对话框的分区视图：标题、筛选、列表、目标选择与底部按钮。

package dev.molifox.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.ContentHit
import dev.molifox.core.ContentVersion
import dev.molifox.core.GameInstance
import dev.molifox.core.LauncherState
import dev.molifox.core.LoaderType
import dev.molifox.desktop.MoliOutlinedButton as OutlinedButton

/** 项目说明与链接：链接由系统浏览器打开，复制按钮只复制项目名称。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ContentProjectHeader(
    hit: ContentHit,
    ui: ContentProjectUiState,
    actions: UiActions,
    clipboard: ClipboardManager,
) {
    val strings = LocalStrings.current
    Text(
        hit.description,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodySmall,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val links = ui.details?.links.orEmpty()
        links.forEach { link ->
            MoliTextButton(
                { actions.run { actions.controller.platform.openUrl(link.url) } },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Icon(Icons.Outlined.OpenInNew, null, Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text(link.title, fontSize = 12.sp)
            }
        }
        MoliTextButton({
            clipboard.setText(AnnotatedString(hit.title))
            ui.copied = true
        }) {
            Text(
                if (ui.copied) strings.t("dialogs.content.copyNameDone") else strings.t("dialogs.content.copyName"),
                fontSize = 12.sp,
            )
        }
    }
}

/** 游戏版本、加载器与发布类型筛选；加载器只对模组/整合包显示。 */
@Composable
internal fun ContentProjectFilters(
    hit: ContentHit,
    ui: ContentProjectUiState,
    chosen: ContentVersion?,
) {
    val strings = LocalStrings.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val gameVersions =
            ui.details
                ?.versions
                .orEmpty()
                .flatMap { it.gameVersions }
                .distinct()
        SimpleDropdown(
            ui.gameVersion,
            listOf("" to strings.t("dialogs.content.filter.allGameVersions")) +
                (listOf(ui.gameVersion).filter { it.isNotBlank() } + gameVersions)
                    .distinct()
                    .map { it to it },
            { ui.gameVersion = it },
            Modifier.weight(1f),
        )
        if (hit.type in setOf("mod", "modpack")) {
            SimpleDropdown(
                ui.loader?.name.orEmpty(),
                listOf("" to strings.t("dialogs.content.filter.allLoaders")) +
                    LoaderType.entries
                        .filter { it != LoaderType.Vanilla }
                        .map { it.name to it.title },
                { ui.loader = LoaderType.entries.find { entry -> entry.name == it } },
                Modifier.weight(1f),
            )
        }
        SimpleDropdown(
            ui.channel,
            listOf(
                "all" to strings.t("dialogs.content.filter.allReleaseTypes"),
                "release" to strings.t("dialogs.content.releaseType.release"),
                "beta" to strings.t("dialogs.content.releaseType.beta"),
                "alpha" to strings.t("dialogs.content.releaseType.alpha"),
            ),
            { ui.channel = it },
            Modifier.width(140.dp),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val dependencyCount = chosen?.dependencies?.count { it.type != "embedded" } ?: 0
        listOf(
            "versions" to strings.t("dialogs.content.tab.versions"),
            "dependencies" to
                if (dependencyCount > 0) {
                    "${strings.t("dialogs.content.tab.dependencies")} ($dependencyCount)"
                } else {
                    strings.t("dialogs.content.tab.dependencies")
                },
            "description" to strings.t("dialogs.content.tab.description"),
            "changelog" to strings.t("dialogs.content.tab.changelog"),
        ).forEach { (key, title) ->
            FilterChip(ui.tab == key, { ui.tab = key }, { Text(title, fontSize = 12.sp) })
        }
    }
}

/** 中间列表：按标签切换介绍、更新日志与版本列表，占用弹窗剩余高度。 */
@Composable
internal fun ContentProjectTabList(
    hit: ContentHit,
    ui: ContentProjectUiState,
    versions: List<ContentVersion>,
    recommendation: ContentVersion?,
    chosen: ContentVersion?,
    dependencyHits: Map<String, ContentHit>,
    onOpenProject: (ContentHit) -> Unit,
    modifier: Modifier = Modifier.height(235.dp),
) {
    LazyColumn(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (ui.tab) {
            "dependencies" ->
                contentDependencyItems(
                    chosen?.dependencies.orEmpty(),
                    dependencyHits,
                    onOpenProject,
                )

            "description" -> contentDescriptionItem(ui.details)

            "changelog" ->
                contentChangelogItem(
                    ui.changelogLoading,
                    ui.changelog,
                    ui.changelogError,
                    chosen,
                )

            else -> {
                contentRecommendationItem(recommendation) { ui.versionId = it.id }
                contentVersionItems(
                    loading = ui.loading,
                    versions = versions,
                    versionId = ui.versionId,
                    formatLabel = { contentVersionFormatLabel(hit, it) },
                    onSelect = { ui.versionId = it.id },
                    onResetFilters = { ui.resetFilters() },
                )
            }
        }
    }
}

/** 单个版本行：选中态同时改变底色与左侧单选框。 */
@Composable
internal fun ContentProjectVersionRow(
    version: ContentVersion,
    selected: Boolean,
    formatLabel: String?,
    onSelect: (ContentVersion) -> Unit,
) {
    Surface(
        onClick = { onSelect(version) },
        color =
            if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (selected) {
                    Icons.Outlined.RadioButtonChecked
                } else {
                    Icons.Outlined.RadioButtonUnchecked
                },
                null,
                Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    version.number.ifBlank { version.name },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp,
                )
                Text(
                    listOfNotNull(
                        formatLabel,
                        version.gameVersions.joinToString(" / ").ifBlank { null },
                        version.loaders.joinToString(" / ").ifBlank { null },
                    ).joinToString(" · "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 11.sp,
                    color =
                        if (formatLabel != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
            Text(
                "${contentChannelTitle(version.channel)} · ${version.published.take(10)}",
                fontSize = 11.sp,
            )
        }
    }
}

/** 目标选择：固定在模态底部；数据包左选实例、右选存档，其余只选实例。 */
@Composable
internal fun ContentProjectTargetPickers(
    hit: ContentHit,
    state: LauncherState,
    ui: ContentProjectUiState,
    chosen: ContentVersion?,
) {
    val strings = LocalStrings.current
    if (hit.type == "modpack") return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // 依赖默认不装：开关固定显示在目标栏上方，勾选后待安装依赖随主文件一起装。
        if (chosen?.dependencies.orEmpty().any { it.projectId.isNotBlank() }) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Metrics.innerRadius))
                    .clickable { ui.includeDependencies = !ui.includeDependencies }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    ui.includeDependencies,
                    { ui.includeDependencies = it },
                    modifier = Modifier.padding(end = 2.dp),
                )
                Column {
                    Text(strings.t("dialogs.content.dependencies.include"), fontSize = 12.sp)
                    Text(
                        strings.t("dialogs.content.dependencies.includeDetail"),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(strings.t("dialogs.content.installTo"), fontSize = 11.sp)
                SimpleDropdown(
                    ui.targetId,
                    listOf("" to strings.t("dialogs.content.selectInstance")) +
                        state.data.instances
                            .filter { it.installed }
                            .map { it.id to "${it.name} · ${it.gameVersion}" },
                    { ui.targetId = it },
                    Modifier.fillMaxWidth(),
                )
            }
            if (hit.type == "datapack") {
                val datapackTarget = contentTargetSaveEnabled(hit, chosen)
                val saveValue =
                    when {
                        !datapackTarget -> TARGET_SAVE_UNAVAILABLE
                        ui.worlds.isEmpty() -> TARGET_SAVE_NO_WORLDS
                        else -> ui.worldName
                    }
                val saveOptions =
                    when {
                        !datapackTarget ->
                            listOf(TARGET_SAVE_UNAVAILABLE to strings.t("dialogs.content.targetSaveUnavailable"))

                        ui.worlds.isEmpty() ->
                            listOf(
                                TARGET_SAVE_NO_WORLDS to
                                    (ui.worldError ?: strings.t("dialogs.content.noWorlds")),
                            )

                        else -> ui.worlds.map { it.name to it.name }
                    }
                Column(Modifier.weight(1f)) {
                    Text(strings.t("dialogs.content.targetSave"), fontSize = 11.sp)
                    SimpleDropdown(
                        saveValue,
                        saveOptions,
                        { ui.worldName = it },
                        Modifier.fillMaxWidth(),
                        enabled = datapackTarget && ui.worlds.isNotEmpty(),
                        label = strings.t("dialogs.content.targetSave"),
                    )
                }
            }
        }
    }
}

/** 目标存档下拉的占位值；不会写回 worldName，避免把“不可用”当成世界名。 */
private const val TARGET_SAVE_UNAVAILABLE = "__unavailable__"
private const val TARGET_SAVE_NO_WORLDS = "__no_worlds__"

/** 底部：一行提示 + 关闭/另存为/安装；安装直接使用实例目录，不再弹目录选择框。 */
@Composable
internal fun ContentProjectFooter(
    hit: ContentHit,
    actions: UiActions,
    ui: ContentProjectUiState,
    target: GameInstance?,
    chosen: ContentVersion?,
    onDismiss: () -> Unit,
    onInstallStarted: () -> Unit,
) {
    val strings = LocalStrings.current
    val compatible = contentVersionCompatible(chosen, hit, target)
    val installKind = chosen?.let { contentVersionInstallKind(hit, it) } ?: hit.type
    // busy 是快照值：挂在 state 上重算，任务开始/结束时安装按钮才会刷新。
    val launcherBusy = rememberLauncherBusy(actions)
    val installLabel =
        if (hit.type == "modpack") {
            strings.t("dialogs.content.installModpack")
        } else {
            strings.t("dialogs.content.installInstance")
        }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            contentInstallHint(hit, target, compatible, installKind),
            Modifier.weight(1f),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MoliTextButton(onDismiss) { Text(strings.t("dialogs.common.close")) }
        OutlinedButton(
            enabled = chosen != null && !launcherBusy,
            onClick = {
                val version = chosen ?: return@OutlinedButton
                saveContentAs(actions, hit, version)
            },
        ) {
            Text(strings.t("dialogs.content.saveAs"))
        }
        PrimaryButton(
            installLabel,
            enabled =
                !ui.installing &&
                    contentDownloadEnabled(
                        ui.loading,
                        launcherBusy,
                        chosen,
                        target,
                        compatible,
                        hit,
                        ui.worldName,
                        installKind,
                    ),
            onClick = {
                val version = chosen ?: return@PrimaryButton
                ui.installError = null
                contentDownload(
                    actions = actions,
                    hit = hit,
                    version = version,
                    target = target,
                    ui = ui,
                    onInstallStarted = onInstallStarted,
                    onError = { message -> ui.installError = message },
                )
            },
        )
    }
}
