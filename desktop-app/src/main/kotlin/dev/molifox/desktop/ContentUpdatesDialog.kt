// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责已安装资源的更新检查、版本锁定和应用。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.onClick
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.JsonParseException
import dev.molifox.core.ContentHit
import dev.molifox.core.ContentInstallPlan
import dev.molifox.core.ContentSource
import dev.molifox.core.ContentUpdate
import dev.molifox.core.ContentUpdateReport
import dev.molifox.core.ModFile
import dev.molifox.desktop.content.contentSourceName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** 资源更新管理：检查更新后选择资源、准备安装计划，再确认执行更新任务。 */
@Composable
fun ContentUpdatesDialog(
    instanceId: String,
    actions: UiActions,
    onDismiss: () -> Unit,
) {
    var report by remember(instanceId) { mutableStateOf<ContentUpdateReport?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var includePrerelease by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var plan by remember { mutableStateOf<ContentInstallPlan?>(null) }
    var details by remember { mutableStateOf<ContentSource?>(null) }
    val scope = rememberCoroutineScope()
    val state by actions.controller.state.collectAsState()
    val busy =
        actions.controller.game.isRunning(instanceId) ||
            actions.controller.isInstanceBusy(instanceId)
    LaunchedEffect(instanceId, includePrerelease, refresh) {
        loading = true
        error = null
        plan = null
        report = null
        selected = emptySet()
        try {
            val message =
                catchContentFailure {
                    val checkedReport =
                        withContext(Dispatchers.IO) {
                            actions.controller.content.catalog
                                .checkUpdates(instanceId, includePrerelease)
                        }
                    report = checkedReport
                    selected =
                        checkedReport.items
                            .filter { it.update != null }
                            .map { it.key }
                            .toSet()
                }
            if (message != null) {
                error = message
            }
        } finally {
            loading = false
        }
    }
    val source = details
    if (source != null) {
        ContentInstallDialog(
            ContentHit(source.projectId, "", source.title, "", "", 0, source.kind, null),
            instanceId,
            actions,
            { details = null },
            onDismiss,
            source,
            source.versionId,
            report?.items.orEmpty().mapNotNull { it.source },
        )
        return
    }
    Modal("资源更新管理", "检查模组、资源包和光影 · 只匹配当前游戏与加载器", onDismiss = onDismiss) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ContentCheckSettings(
                includePrerelease = includePrerelease,
                onIncludePrereleaseChange = { includePrerelease = it },
                sourceName = contentSourceName(state.data.settings.contentDownloadSource),
                loading = loading,
                busy = busy,
                onRecheck = { refresh++ },
            )
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
            ContentUpdatesBody(
                plan = plan,
                report = report,
                selected = selected,
                loading = loading,
                busy = busy,
                onSelectionChange = { selected = it },
                onDetails = { details = it },
                onToggleLock = { origin ->
                    loading = true
                    error = null
                    scope.launch {
                        val message =
                            catchContentFailure {
                                actions.controller.requireIdle(instanceId)
                                withContext(Dispatchers.IO) {
                                    actions.controller.content.catalog
                                        .setLocked(
                                            instanceId,
                                            origin,
                                            !origin.locked,
                                        )
                                }
                                refresh++
                            }
                        if (message != null) {
                            error = message
                            loading = false
                        }
                    }
                },
            )
        }
        ContentUpdatesFooter(
            plan = plan,
            selectedCount = selected.size,
            loading = loading,
            busy = busy,
            onClose = onDismiss,
            onBackToSelection = { plan = null },
            onPrimary = {
                loading = true
                error = null
                scope.launch {
                    try {
                        val message =
                            catchContentFailure {
                                val prepared = plan
                                if (prepared != null) {
                                    actions.controller.installContent(prepared)
                                    onDismiss()
                                } else {
                                    plan =
                                        withContext(Dispatchers.IO) {
                                            actions.controller.content.planUpdates(
                                                requireNotNull(report),
                                                selected,
                                            )
                                        }
                                }
                            }
                        if (message != null) {
                            error = message
                        }
                    } finally {
                        loading = false
                    }
                }
            },
        )
    }
}

/**
 * 执行一次内容目录操作，只把可预期的文件、解析与前置校验失败交给界面提示。
 *
 * 取消继续上抛；未列出的异常（程序错误）保持原样抛出，不再显示成用户提示。
 */
private suspend fun catchContentFailure(block: suspend () -> Unit): String? =
    try {
        block()
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: IOException) {
        failure.message
    } catch (failure: JsonParseException) {
        failure.message
    } catch (failure: IllegalStateException) {
        failure.message
    } catch (failure: IllegalArgumentException) {
        failure.message
    }

/** 检查设置：是否包含预发布版本与重新检查按钮。 */
@Composable
private fun ContentCheckSettings(
    includePrerelease: Boolean,
    onIncludePrereleaseChange: (Boolean) -> Unit,
    sourceName: String,
    loading: Boolean,
    busy: Boolean,
    onRecheck: () -> Unit,
) {
    SettingsSection(
        "检查设置",
        "按文件哈希在当前内容源（$sourceName）识别资源，只匹配当前游戏版本与加载器。",
    ) {
        SettingRow("包含测试版和预览版", "版本列表会包含测试版与预览版") {
            CompactSwitch(
                includePrerelease,
                onIncludePrereleaseChange,
                enabled = !loading && !busy,
            )
        }
        SettingRow("重新检查", "重新读取本地文件并按哈希匹配来源") {
            SecondaryButton(onRecheck, enabled = !loading && !busy) {
                Text("重新检查", fontSize = 12.sp)
            }
        }
    }
}

/** 已经准备好的安装计划预览。 */
@Composable
private fun PreparedPlanSection(prepared: ContentInstallPlan) {
    SettingsSection("即将安装", "将安装 ${prepared.files.size} 项（包括必要前置）") {
        prepared.files.forEach { file ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = Metrics.rowHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${file.folder}/${file.filename}",
                    Modifier.weight(1f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Tag(
                    "%.1f MB".format(file.size / 1048576f),
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (prepared.optional.isNotEmpty()) {
            MutedText("可选前置默认不安装；可在项目详情中单独选择。")
        }
    }
}

/** 主体：优先展示准备好的计划，否则展示检查结果或空状态。 */
@Composable
private fun ContentUpdatesBody(
    plan: ContentInstallPlan?,
    report: ContentUpdateReport?,
    selected: Set<String>,
    loading: Boolean,
    busy: Boolean,
    onSelectionChange: (Set<String>) -> Unit,
    onDetails: (ContentSource) -> Unit,
    onToggleLock: (ContentSource) -> Unit,
) {
    if (plan != null) {
        PreparedPlanSection(plan)
        return
    }
    val result = report ?: return
    if (result.items.isEmpty()) {
        Panel(Modifier.fillMaxWidth()) {
            EmptyState(
                "没有可检查的资源",
                "没有可检查的 JAR 或 ZIP 资源。",
                Icons.Outlined.CloudOff,
            )
        }
        return
    }
    SettingsSection(
        "可更新资源",
        "已识别 ${result.items.size} 个文件 · 已选 ${selected.size} 项",
    ) {
        result.items.forEach { item ->
            ContentUpdateRow(
                item = item,
                checked = item.key in selected,
                loading = loading,
                busy = busy,
                onCheckedChange = { checked ->
                    onSelectionChange(
                        if (checked) selected + item.key else selected - item.key,
                    )
                },
                onDetails = onDetails,
                onToggleLock = onToggleLock,
            )
        }
    }
}

/** 单个可更新资源行：勾选框、文件名与版本说明、详情/锁定操作。 */
@Composable
private fun ContentUpdateRow(
    item: ContentUpdate,
    checked: Boolean,
    loading: Boolean,
    busy: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onDetails: (ContentSource) -> Unit,
    onToggleLock: (ContentSource) -> Unit,
) {
    val origin = item.source
    Row(
        Modifier.fillMaxWidth().heightIn(min = Metrics.rowHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(
            checked,
            onCheckedChange,
            enabled = item.update != null && !loading && !busy,
        )
        Column(Modifier.weight(1f)) {
            Text(
                item.filename,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                updateStatusLine(item),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (origin != null) {
            TextButton({ onDetails(origin) }, enabled = !busy) {
                Text("详情", fontSize = 12.sp)
            }
            TextButton({ onToggleLock(origin) }, enabled = !loading && !busy) {
                Text(
                    if (origin.locked) "解锁" else "锁定",
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/** 资源行的版本说明：警告优先，其次「当前 → 目标」，否则说明当前筛选下没有更高版本。 */
private fun updateStatusLine(item: ContentUpdate): String =
    when {
        item.warning.isNotBlank() -> item.warning
        item.update != null ->
            "${item.currentVersion} → " +
                "${item.update?.number}"
        else -> "${item.currentVersion} · " + "当前筛选下没有更高版本"
    }

/** 底部操作：关闭、返回选择与准备/确认更新。 */
@Composable
private fun ContentUpdatesFooter(
    plan: ContentInstallPlan?,
    selectedCount: Int,
    loading: Boolean,
    busy: Boolean,
    onClose: () -> Unit,
    onBackToSelection: () -> Unit,
    onPrimary: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClose) { Text("关闭更新管理") }
        if (plan != null) TextButton(onBackToSelection) { Text("返回选择") }
        PrimaryButton(
            if (plan == null) "准备所选更新 ($selectedCount)" else "确认更新",
            enabled = !loading && !busy && selectedCount > 0,
            onClick = onPrimary,
        )
    }
}

@Composable
fun ModDetailsDialog(
    mod: ModFile,
    onDismiss: () -> Unit,
) {
    Modal(mod.metadata.title.ifBlank { mod.filename }, mod.filename, onDismiss = onDismiss) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MutedText("${mod.metadata.loader} · ${"%.1f MB".format(mod.size / 1048576f)}")
            if (mod.metadata.warning.isNotBlank()) Text(mod.metadata.warning)
            mod.metadata.entries.forEach { entry ->
                Text(
                    "${entry.name} · ${entry.version}",
                    style = MaterialTheme.typography.titleSmall,
                )
                MutedText("Mod ID：${entry.id}")
                if (entry.authors.isNotBlank()) Text("作者：${entry.authors}")
                if (entry.description.isNotBlank()) Text(entry.description)
                if (entry.dependencies.isNotEmpty()) {
                    Text("声明的依赖：\n${entry.dependencies.joinToString("\n")}")
                }
            }
            MutedText("使用模组页的「检查更新」查看更新；这里可按文件哈希识别内容来源，再查看项目详情、选择版本或锁定。")
        }
        TextButton(onDismiss) { Text("关闭模组详情") }
    }
}
