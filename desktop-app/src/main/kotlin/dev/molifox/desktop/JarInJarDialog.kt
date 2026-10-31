// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责模组页「嵌入依赖」只读弹窗：扫描入口、工具栏、汇总与内嵌结构；问题区在
// JarInJarDialogIssues.kt，不提供安装或删除。

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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.EmbeddedJar
import dev.molifox.core.GameInstance
import dev.molifox.core.JarInJarAnalyzer
import dev.molifox.core.JarInJarHost
import dev.molifox.core.JarInJarReport
import dev.molifox.core.ModFile
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** 只读诊断：扫描结果按会话缓存在 [JarInJarAnalyzer] 内，关闭弹窗不会改动任何文件。 */
@Composable
internal fun JarInJarDialog(
    instance: GameInstance,
    mods: List<ModFile>,
    actions: UiActions,
    onDismiss: () -> Unit,
) {
    var report by remember(instance.id) { mutableStateOf<JarInJarReport?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    val analyzer = remember(instance.id) { JarInJarAnalyzer() }
    val busy =
        actions.controller.game.isRunning(instance.id) ||
            actions.controller.isInstanceBusy(instance.id)
    LaunchedEffect(instance.id, mods, refresh, busy) {
        loading = true
        error = null
        report = null
        if (busy) {
            error = "游戏运行中不读取模组文件，请先退出游戏。"
            loading = false
            return@LaunchedEffect
        }
        val modsDirectory =
            runCatching {
                actions.controller.store
                    .instancePath(instance.id)
                    .resolve("mods")
            }.getOrNull()
        if (modsDirectory == null) {
            error = "无法定位实例的 mods 目录"
            loading = false
            return@LaunchedEffect
        }
        try {
            report =
                analyzer.analyze(
                    instanceId = instance.id,
                    gameVersion = instance.gameVersion,
                    loader = instance.loader,
                    mods = mods,
                    modsDirectory = modsDirectory,
                    temporaryDirectory = actions.controller.store.temporaryDirectory,
                )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            error = failure.message ?: "无法读取模组文件"
        } catch (failure: IllegalArgumentException) {
            error = failure.message ?: "无法读取模组文件"
        } catch (failure: IllegalStateException) {
            error = failure.message ?: "无法读取模组文件"
        } finally {
            loading = false
        }
    }
    Modal(
        "嵌入依赖",
        "只读读取 JAR 内的依赖声明；不下载、不安装，也不会改动模组文件。",
        width = 660.dp,
        onDismiss = onDismiss,
    ) {
        JarInJarToolbar(
            loading = loading,
            query = query,
            onQueryChange = { query = it },
            onRefresh = { refresh++ },
        )
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        JarInJarOutcome(error, report, query)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onDismiss) { Text("关闭", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun JarInJarOutcome(
    message: String?,
    report: JarInJarReport?,
    query: String,
) {
    when {
        message != null ->
            Text(message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        report != null -> JarInJarBody(report, query)
        else -> MutedText("尚未读取模组文件。")
    }
}

@Composable
private fun JarInJarToolbar(
    loading: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.weight(1f)) {
            ToolbarSearchField(query, onQueryChange, "搜索依赖、模组或内嵌 JAR")
        }
        SecondaryButton(onRefresh, enabled = !loading) {
            Text(if (loading) "读取中…" else "重新扫描", fontSize = 12.sp)
        }
    }
}

@Composable
private fun JarInJarBody(
    report: JarInJarReport,
    query: String,
) {
    val warnings =
        report.warnings.filter {
            matches(query, it.dependencyId, it.declarer, it.hostFilename, it.constraint)
        }
    val conflicts =
        report.conflicts.filter {
            matches(query, it.targetId, it.target, it.declarer, it.declarerHost, it.targetHost)
        }
    val hosts = report.hosts.filter { matchesHostOrNode(it, query) }
    Column(
        Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(summaryText(report), fontSize = 12.sp)
        if (report.incomplete) {
            MutedText(
                "部分文件无法完整解析，缺失与版本结论仅供参考。${report.incompleteNote}",
            )
        }
        if (report.warnings.isNotEmpty()) JarInJarWarningSection(warnings)
        if (report.conflicts.isNotEmpty()) JarInJarConflictSection(conflicts)
        JarInJarTreeSection(hosts)
        if (report.pendingCount == 0 && report.conflicts.isEmpty() && report.embeddedCount == 0) {
            MutedText("没有发现嵌入依赖。")
        }
    }
}

private fun summaryText(report: JarInJarReport): String =
    buildString {
        append("${report.hosts.size} 个模组 · ${report.embeddedCount} 个内嵌 JAR")
        if (report.pendingCount > 0) append(" · ${report.pendingCount} 项待处理")
        if (report.conflicts.isNotEmpty()) append(" · ${report.conflicts.size} 项冲突")
    }

@Composable
private fun JarInJarTreeSection(hosts: List<JarInJarHost>) {
    val visible = hosts.filter { it.embedded.isNotEmpty() }
    if (visible.isEmpty()) return
    Text("内嵌结构", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    visible.forEach { host ->
        Text(
            "${host.filename} · ${host.displayName}",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        if (!host.complete && host.note.isNotBlank()) {
            MutedText(host.note, Modifier.padding(start = 12.dp))
        }
        flattenNodes(host.embedded).forEach { node -> JarInJarNodeRow(node) }
    }
}

@Composable
private fun JarInJarNodeRow(node: EmbeddedJar) {
    val indent = ((node.depth - 1) * 12).dp
    Row(
        Modifier.fillMaxWidth().padding(start = indent),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            node.displayName,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (node.version.isNotBlank()) {
            Tag(node.version, MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Tag(node.loader.title)
    }
    MutedText(
        node.path + if (node.complete) "" else " · 读取不完整",
        Modifier.padding(start = indent),
    )
}

/** 关键词为空视为全部命中；问题区与树区共用同一套匹配规则。 */
internal fun matches(
    query: String,
    vararg values: String,
): Boolean = query.isBlank() || values.any { it.contains(query, true) }

internal fun flattenNodes(nodes: List<EmbeddedJar>): List<EmbeddedJar> =
    nodes.flatMap { node -> listOf(node) + flattenNodes(node.children) }

private fun matchesHostOrNode(
    host: JarInJarHost,
    query: String,
): Boolean {
    if (query.isBlank()) return true
    if (matches(query, host.filename, host.modId, host.name)) return true
    return flattenNodes(host.embedded).any { node ->
        matches(query, node.path, node.modId, node.name, node.version, node.identifier)
    }
}
