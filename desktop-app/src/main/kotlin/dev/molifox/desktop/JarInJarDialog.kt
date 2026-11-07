// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责模组页「嵌入依赖」只读弹窗的骨架：固定头部、可滚动正文与固定底部关闭按钮；
// 不下载、不安装，也不会改动模组文件。问题区在 JarInJarDialogIssues.kt，
// 只展示模组依赖、冲突与警告；搜索规则在 JarInJarDialogFilters.kt。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.GameInstance
import dev.molifox.core.JarInJarConflictPair
import dev.molifox.core.JarInJarDependencyRow
import dev.molifox.core.JarInJarHost
import dev.molifox.core.JarInJarReport
import dev.molifox.core.JarInJarWarning
import dev.molifox.core.ModFile
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** 正文滚动区上限：Modal 的 640dp 上限扣除头部、工具栏与底部按钮后的安全高度。 */
private const val BODY_MAX_HEIGHT_DP = 380

private const val DIALOG_WIDTH_DP = 660

/** 只读诊断：扫描和缓存由 core 管理，关闭弹窗不会改动任何文件。 */
@Composable
internal fun JarInJarDialog(
    instance: GameInstance,
    mods: List<ModFile>,
    actions: UiActions,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    var report by remember(instance.id) { mutableStateOf<JarInJarReport?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    val instanceBusy = rememberInstanceBusy(actions, instance.id)
    val busy = actions.controller.game.isRunning(instance.id) || instanceBusy
    LaunchedEffect(instance.id, mods, refresh, busy) {
        loading = true
        error = null
        report = null
        if (busy) {
            error = tr("dialogs.jar.busyNotice")
        } else {
            val outcome = runScan(actions, instance, mods)
            report = outcome.report
            error = outcome.message
        }
        loading = false
    }
    Modal(
        strings.t("dialogs.jar.title"),
        strings.t("dialogs.jar.subtitle"),
        width = DIALOG_WIDTH_DP.dp,
        onDismiss = onDismiss,
    ) {
        JarInJarToolbar(
            loading = loading,
            query = query,
            onQueryChange = { query = it },
            onRefresh = { refresh++ },
        )
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        JarInJarScrollBody(error, report, query)
        JarInJarFooter(onDismiss)
    }
}

/** 一次扫描的结果：成功时只有报告，失败时只有用户可读的提示。 */
private class ScanOutcome(
    val report: JarInJarReport? = null,
    val message: String? = null,
)

private suspend fun runScan(
    actions: UiActions,
    instance: GameInstance,
    mods: List<ModFile>,
): ScanOutcome =
    try {
        ScanOutcome(report = actions.controller.jarInJarReport(instance.id, mods))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: IOException) {
        failureOutcome(failure)
    } catch (failure: IllegalArgumentException) {
        failureOutcome(failure)
    } catch (failure: IllegalStateException) {
        failureOutcome(failure)
    }

private fun failureOutcome(failure: Exception): ScanOutcome =
    ScanOutcome(message = failure.message?.takeIf(String::isNotBlank) ?: tr("dialogs.jar.readFailure"))

@Composable
private fun JarInJarToolbar(
    loading: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    val strings = LocalStrings.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.weight(1f)) {
            ToolbarSearchField(query, onQueryChange, strings.t("dialogs.jar.searchLabel"))
        }
        SecondaryButton(onRefresh, enabled = !loading) {
            Text(if (loading) strings.t("dialogs.jar.reading") else strings.t("dialogs.jar.rescan"), fontSize = 12.sp)
        }
    }
}

@Composable
private fun JarInJarScrollBody(
    message: String?,
    report: JarInJarReport?,
    query: String,
) {
    val strings = LocalStrings.current
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = BODY_MAX_HEIGHT_DP.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            message != null ->
                Text(message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            report != null -> JarInJarSections(report, query)
            else -> MutedText(strings.t("dialogs.jar.notScanned"))
        }
    }
}

@Composable
private fun JarInJarSections(
    report: JarInJarReport,
    query: String,
) {
    val strings = LocalStrings.current
    Text(summaryText(report), fontSize = 12.sp)
    if (report.incomplete) {
        MutedText(strings.t("dialogs.jar.incompleteNote", report.incompleteNote))
    }
    val data = filteredSections(report, query)
    var visible = false
    if (data.warnings.isNotEmpty()) {
        visible = true
        JarInJarWarningSection(data.warnings)
    }
    if (data.conflicts.isNotEmpty()) {
        visible = true
        JarInJarConflictSection(data.conflicts)
    }
    if (data.hosts.isNotEmpty()) {
        visible = true
        JarInJarDependencySection(data.hosts, data.rows, query)
    }
    if (!visible) MutedText(if (query.isBlank()) strings.t("dialogs.jar.none") else strings.t("dialogs.jar.noMatch"))
}

/** 搜索命中的四个分区数据；搜索为空时等价于报告全集，依赖区按宿主模组折叠展示。 */
private class SectionData(
    val warnings: List<JarInJarWarning>,
    val conflicts: List<JarInJarConflictPair>,
    val hosts: List<JarInJarHost>,
    val rows: List<JarInJarDependencyRow>,
)

private fun filteredSections(
    report: JarInJarReport,
    query: String,
): SectionData {
    val rows = report.rows.filter { matchesDependencyRow(it, query) }
    val hosts =
        report.hosts.filter { host ->
            matches(query, host.filename, host.displayName, host.modId) ||
                rows.any { it.hostFilename == host.filename }
        }
    return SectionData(
        warnings =
            report.warnings.filter {
                matches(query, it.dependencyId, it.declarer, it.hostFilename, it.constraint)
            },
        conflicts =
            report.conflicts.filter {
                matches(query, it.targetId, it.target, it.declarer, it.declarerHost, it.targetHost)
            },
        hosts = hosts,
        rows = rows,
    )
}

private fun summaryText(report: JarInJarReport): String =
    buildString {
        append(tr("dialogs.jar.summary", report.hosts.size, report.rows.count { !it.embedded }))
        if (report.pendingCount > 0) append(tr("dialogs.jar.pendingSuffix", report.pendingCount))
        if (report.conflicts.isNotEmpty()) append(tr("dialogs.jar.conflictSuffix", report.conflicts.size))
    }

/** 关闭按钮固定在滚动区之外，正文再长也不会被裁掉。 */
@Composable
private fun JarInJarFooter(onDismiss: () -> Unit) {
    val strings = LocalStrings.current
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        MoliTextButton(onDismiss) { Text(strings.t("dialogs.common.close"), fontSize = 12.sp) }
    }
}
