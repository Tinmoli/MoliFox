// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责资源安装计划、兼容提示和安装确认。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.onClick
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.ContentDetails
import dev.molifox.core.ContentHit
import dev.molifox.core.ContentInstallPlan
import dev.molifox.core.ContentSource
import dev.molifox.core.ContentVersion
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Path

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContentInstallDialog(
    hit: ContentHit,
    instanceId: String,
    actions: UiActions,
    onDismiss: () -> Unit,
    onInstalled: () -> Unit,
    source: ContentSource? = null,
    initialVersionId: String = "",
    relatedSources: List<ContentSource> = emptyList(),
    destination: Path? = null,
) {
    val strings = LocalStrings.current
    var selected by remember(hit.id, instanceId) { mutableStateOf(emptySet<String>()) }
    var plan by remember(hit.id, instanceId) { mutableStateOf<ContentInstallPlan?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var retry by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var details by remember(hit.id, instanceId) { mutableStateOf<ContentDetails?>(null) }
    var versionId by remember(hit.id, instanceId) { mutableStateOf(initialVersionId) }
    var channel by remember { mutableStateOf("all") }
    var expanded by remember { mutableStateOf(false) }
    var changelog by remember(hit.id, instanceId) { mutableStateOf<ContentVersion?>(null) }
    var changelogError by remember(hit.id, instanceId) { mutableStateOf<String?>(null) }
    val instanceRoot =
        remember(instanceId) {
            runCatching { actions.controller.store.instancePath(instanceId) }.getOrNull()
        }
    // 用户在下载对话框里确认过的目录 → 实例内的资源子目录；无法映射时沿用计划里的默认目录。
    val destinationFolder = contentFolderOf(instanceRoot, destination)
    LaunchedEffect(versionId) {
        changelog = null
        changelogError = null
        if (versionId.isBlank()) return@LaunchedEffect
        loadChangelog(actions, hit, instanceId, versionId)
            .onSuccess { changelog = it }
            .onFailure { changelogError = it.message }
    }
    LaunchedEffect(hit.id, instanceId, selected, retry, versionId, destinationFolder) {
        loading = true
        error = null
        plan = null
        loadInstallPlan(
            actions = actions,
            hit = hit,
            instanceId = instanceId,
            selected = selected,
            versionId = versionId,
            knownDetails = details,
            source = source,
            relatedSources = relatedSources,
            destinationFolder = destinationFolder,
        ).onSuccess { loaded ->
            details = loaded.details
            versionId = loaded.versionId
            plan = loaded.plan
        }.onFailure { error = it.message }
        if (isActive) loading = false
    }
    Modal(
        hit.title,
        strings.t(
            "dialogs.content.install.subtitle",
            actions.controller.store
                .instance(instanceId)
                .name,
        ),
        onDismiss = onDismiss,
    ) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error != null) {
            Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
            TextButton({ retry++ }) { Text(strings.t("dialogs.common.retry")) }
        }
        DestinationNotice(destination, destinationFolder, hit.type)
        Column(
            Modifier.fillMaxWidth().heightIn(max = 390.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(hit.description, style = MaterialTheme.typography.bodyMedium)
            details?.let { info ->
                ContentProjectDetails(
                    info = info,
                    actions = actions,
                    loading = loading,
                    channel = channel,
                    onChannelChange = { channel = it },
                    versionId = versionId,
                    onVersionSelect = { chosenId ->
                        versionId = chosenId
                        selected = emptySet()
                    },
                    changelog = changelog,
                    changelogError = changelogError,
                    expanded = expanded,
                    onToggleExpanded = { expanded = !expanded },
                    locked = source?.locked == true,
                )
            }
            plan?.let { current ->
                InstallPlanPreview(current, selected, loading) { dependencyId, enabled ->
                    selected =
                        if (enabled) {
                            selected + dependencyId
                        } else {
                            selected - dependencyId
                        }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onDismiss) { Text(strings.t("dialogs.common.cancel")) }
            Spacer(Modifier.width(12.dp))
            PrimaryButton(
                strings.t("dialogs.content.install.start"),
                enabled = installEnabled(loading, error, plan, source),
                onClick = {
                    scope.launch {
                        val current = plan ?: return@launch
                        installContentPlan(actions, current)
                            .onSuccess { onInstalled() }
                            .onFailure { error = it.message }
                    }
                },
            )
        }
    }
}

/** 下载位置提示：确认过的目录不在实例内时，说明会回退到实例的资源目录。 */
@Composable
private fun DestinationNotice(
    destination: Path?,
    destinationFolder: String?,
    type: String,
) {
    if (destination != null) {
        if (destinationFolder == null) {
            MutedText(tr("dialogs.content.install.outsideFolder", contentFolderForType(type)))
        } else {
            MutedText(tr("dialogs.content.downloadLocation", destination))
        }
    }
}

/** 项目来源、许可、兼容版本数与外部链接。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContentProjectDetails(
    info: ContentDetails,
    actions: UiActions,
    loading: Boolean,
    channel: String,
    onChannelChange: (String) -> Unit,
    versionId: String,
    onVersionSelect: (String) -> Unit,
    changelog: ContentVersion?,
    changelogError: String?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    locked: Boolean,
) {
    val strings = LocalStrings.current
    MutedText(
        "${info.providerName} · " +
            "${info.license.ifBlank { strings.t("dialogs.content.install.noLicense") }} · " +
            strings.t("dialogs.content.install.versionCount", info.versions.size),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        info.links.forEach { link ->
            TextButton(
                {
                    actions.run { actions.controller.platform.openUrl(link.url) }
                },
                enabled = !loading,
            ) {
                Text(link.title, fontSize = 12.sp)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            "all" to strings.t("dialogs.content.filter.all"),
            "release" to strings.t("dialogs.content.releaseType.release"),
            "beta" to strings.t("dialogs.content.releaseType.beta"),
            "alpha" to strings.t("dialogs.content.releaseType.alpha"),
        ).forEach { (key, title) ->
            FilterChip(channel == key, { onChannelChange(key) }, label = { Text(title) })
        }
    }
    val versions = info.versions.filter { channel == "all" || it.channel == channel }
    val chosen = info.versions.find { it.id == versionId }
    VersionPicker(versions, chosen, loading, onVersionSelect)
    chosen?.let {
        Text(
            "${it.name} · ${it.channel} · ${it.published.take(10)}",
            style = MaterialTheme.typography.bodySmall,
        )
        val log =
            changelog?.takeIf { log -> log.id == it.id }?.changelog ?: it.changelog
        if (log.isNotBlank()) {
            Text(strings.t("dialogs.content.tab.changelog"), style = MaterialTheme.typography.titleSmall)
            Text(log, style = MaterialTheme.typography.bodySmall)
        }
        if (changelogError != null) MutedText(strings.t("dialogs.content.install.changelogFailed"))
    }
    TextButton(onToggleExpanded) {
        Text(
            if (expanded) {
                strings.t("dialogs.content.install.collapse")
            } else {
                strings.t("dialogs.content.install.expand")
            },
        )
    }
    if (expanded) Text(info.body, style = MaterialTheme.typography.bodySmall)
    if (locked) MutedText(strings.t("dialogs.content.install.locked"))
}

/** 兼容版本下拉：按通道过滤后的版本列表与当前选中项。 */
@Composable
private fun VersionPicker(
    versions: List<ContentVersion>,
    chosen: ContentVersion?,
    loading: Boolean,
    onVersionSelect: (String) -> Unit,
) {
    val strings = LocalStrings.current
    var versionMenu by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            { versionMenu = true },
            modifier = Modifier.semantics { contentDescription = strings.t("dialogs.content.install.versionLabel") },
            enabled = !loading,
        ) {
            Text(
                strings.t(
                    "dialogs.content.install.selectVersion",
                    chosen?.number?.ifBlank { chosen.name } ?: strings.t("dialogs.content.install.noCompatible"),
                ),
            )
        }
        DropdownMenu(
            versionMenu,
            { versionMenu = false },
            Modifier.heightIn(max = 240.dp),
        ) {
            versions.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Text(
                            "${item.number.ifBlank {
                                item.name
                            }} · ${item.channel} · ${item.published.take(
                                10,
                            )}",
                        )
                    },
                    onClick = {
                        onVersionSelect(item.id)
                        versionMenu = false
                    },
                )
            }
            if (versions.isEmpty()) {
                DropdownMenuItem({ Text(strings.t("dialogs.content.install.noneInChannel")) }, {}, enabled = false)
            }
        }
    }
}

/** 安装内容与可选前置：复选框只在计划就绪后启用。 */
@Composable
private fun InstallPlanPreview(
    current: ContentInstallPlan,
    selected: Set<String>,
    loading: Boolean,
    onOptionalToggle: (String, Boolean) -> Unit,
) {
    val strings = LocalStrings.current
    Text(
        strings.t("dialogs.content.install.contentsTitle", current.files.size),
        style = MaterialTheme.typography.titleSmall,
    )
    current.files.forEach { file ->
        SettingRow(file.title, "${file.folder}/${file.filename}") { Tag(strings.t("dialogs.content.install.matched")) }
    }
    if (current.optional.isNotEmpty()) {
        HorizontalDivider()
        Text(strings.t("dialogs.content.install.optionalDeps"), style = MaterialTheme.typography.titleSmall)
        current.optional.forEach { dependency ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    dependency.id in selected,
                    { enabled -> onOptionalToggle(dependency.id, enabled) },
                    enabled = !loading,
                )
                Text(dependency.title)
            }
        }
    }
}
