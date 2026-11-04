// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责资源项目对话框的分区视图：标题、筛选、列表、目标选择与底部按钮。

package dev.molifox.desktop

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import dev.molifox.desktop.MoliTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import java.nio.file.Path

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
            TextButton(
                { actions.run { actions.controller.platform.openUrl(link.url) } },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Icon(Icons.Outlined.OpenInNew, null, Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text(link.title, fontSize = 12.sp)
            }
        }
        TextButton({
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
        listOf(
            "versions" to strings.t("dialogs.content.tab.versions"),
            "description" to strings.t("dialogs.content.tab.description"),
            "changelog" to strings.t("dialogs.content.tab.changelog"),
        ).forEach { (key, title) ->
            FilterChip(ui.tab == key, { ui.tab = key }, { Text(title, fontSize = 12.sp) })
        }
    }
}

/** 中间列表：按标签切换介绍、更新日志与版本列表，高度固定避免弹窗跳动。 */
@Composable
internal fun ContentProjectTabList(
    ui: ContentProjectUiState,
    versions: List<ContentVersion>,
    recommendation: ContentVersion?,
    chosen: ContentVersion?,
) {
    LazyColumn(
        Modifier.fillMaxWidth().height(235.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (ui.tab) {
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
                    version.gameVersions.joinToString(" / ") +
                        " · " +
                        version.loaders.joinToString(" / "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "${contentChannelTitle(version.channel)} · ${version.published.take(10)}",
                fontSize = 11.sp,
            )
        }
    }
}

/** 目标选择：非整合包选出实例，数据包再选出存档。 */
@Composable
internal fun ContentProjectTargetPickers(
    hit: ContentHit,
    state: LauncherState,
    ui: ContentProjectUiState,
) {
    val strings = LocalStrings.current
    if (hit.type != "modpack") {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(strings.t("dialogs.content.installTo"), fontSize = 12.sp)
            SimpleDropdown(
                ui.targetId,
                listOf("" to strings.t("dialogs.content.selectInstance")) +
                    state.data.instances
                        .filter { it.installed }
                        .map { it.id to "${it.name} · ${it.gameVersion}" },
                { ui.targetId = it },
                Modifier.weight(1f),
            )
        }
    }
    if (hit.type == "datapack") {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(strings.t("dialogs.content.targetSave"), fontSize = 12.sp)
            SimpleDropdown(
                ui.worldName,
                if (ui.worlds.isEmpty()) {
                    listOf("" to (ui.worldError ?: strings.t("dialogs.content.noWorlds")))
                } else {
                    ui.worlds.map { it.name to it.name }
                },
                { ui.worldName = it },
                Modifier.weight(1f),
            )
        }
    }
}

/** 底部：一行提示 + 关闭/下载；下载先确认目标文件夹，再交给对话框继续。 */
@Composable
internal fun ContentProjectFooter(
    hit: ContentHit,
    actions: UiActions,
    ui: ContentProjectUiState,
    target: GameInstance?,
    chosen: ContentVersion?,
    instanceRoot: Path?,
    defaultDestination: Path?,
    onDismiss: () -> Unit,
    onInstallStarted: () -> Unit,
) {
    val strings = LocalStrings.current
    val compatible = contentVersionCompatible(chosen, hit, target)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            contentInstallHint(hit, target, compatible),
            Modifier.weight(1f),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onDismiss) { Text(strings.t("dialogs.common.close")) }
        PrimaryButton(
            strings.t("dialogs.content.download"),
            enabled =
                contentDownloadEnabled(
                    ui.loading,
                    actions.controller.busy,
                    chosen,
                    target,
                    compatible,
                    hit,
                    ui.worldName,
                ),
            onClick = {
                val version = chosen ?: return@PrimaryButton
                contentDownload(
                    actions = actions,
                    hit = hit,
                    version = version,
                    instanceId = target?.id,
                    worldName = ui.worldName,
                    defaultDestination = defaultDestination,
                    instanceRoot = instanceRoot,
                    onDestination = { ui.destination = it },
                    onConfirmDatapack = { ui.confirmDatapack = true },
                    onConfirmWorld = { ui.confirmWorld = true },
                    onInstallVersion = { ui.installVersion = version },
                    onInstallStarted = onInstallStarted,
                )
            },
        )
    }
}

/** 数据包确认：先说明会写到哪个存档，再交给安装事务。 */
@Composable
internal fun ContentDatapackConfirmDialog(
    hit: ContentHit,
    ui: ContentProjectUiState,
    target: GameInstance,
    chosen: ContentVersion,
    defaultDestination: Path?,
    actions: UiActions,
    onInstallStarted: () -> Unit,
) {
    val strings = LocalStrings.current
    Modal(
        strings.t("dialogs.content.datapack.title"),
        "${target.name} / ${ui.worldName}",
        onDismiss = { ui.confirmDatapack = false },
    ) {
        Text(strings.t("dialogs.content.datapack.message", hit.title, chosen.number))
        Text(
            strings.t("dialogs.content.datapack.hint"),
            style = MaterialTheme.typography.bodySmall,
        )
        defaultDestination?.let { folder ->
            MutedText(strings.t("dialogs.content.downloadLocation", folder))
            if (ui.destination != null && ui.destination != folder) {
                MutedText(strings.t("dialogs.content.datapack.locationHint"))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton({ ui.confirmDatapack = false }) { Text(strings.t("dialogs.common.cancel")) }
            PrimaryButton(
                strings.t("dialogs.content.datapack.confirm"),
                enabled = !actions.controller.busy,
                onClick = {
                    actions.run {
                        actions.controller.installDatapack(hit, chosen.id, target.id, ui.worldName)
                    }
                    onInstallStarted()
                },
            )
        }
    }
}
