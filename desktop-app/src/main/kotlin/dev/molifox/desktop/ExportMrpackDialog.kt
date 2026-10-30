// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 集中整合包导出状态，避免与目录迁移弹窗互相依赖。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import dev.molifox.core.MrpackExportMetadata
import dev.molifox.core.MrpackExportPlan
import dev.molifox.core.PackExportFormat
import dev.molifox.core.PackExportOptions
import dev.molifox.core.PackExportScan
import dev.molifox.core.defaultExportEntries
import dev.molifox.core.exportLocalResourcePaths
import dev.molifox.core.exportSelectedPaths
import dev.molifox.core.retainExportSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * 导出整合包或完整备份：先只读预览，再由任务队列写文件，进度与取消都在日志页。
 * 「选择内容」按实例目录的真实顶层条目勾选（文件夹整段写入或整段排除），
 * 默认只勾 mods/config/resourcepacks/shaderpacks/options.txt；取消不会留下半写文件。
 */
@Composable
fun ExportMrpackDialog(
    instance: GameInstance,
    actions: UiActions,
    onDismiss: () -> Unit,
    onStarted: () -> Unit = {},
) {
    var format by remember(instance.id) { mutableStateOf(PackExportFormat.Modrinth) }
    var options by remember(instance.id) {
        mutableStateOf(PackExportOptions.defaultsFor(PackExportFormat.Modrinth))
    }
    val stem = remember(instance.name) { exportFileStem(instance.name) }
    val defaultTarget =
        remember(stem) {
            runCatching {
                Path
                    .of(System.getProperty("user.home", "."))
                    .toAbsolutePath()
                    .resolve("$stem.mrpack")
                    .toString()
            }.getOrDefault("$stem.mrpack")
        }
    var target by remember(instance.id) { mutableStateOf(defaultTarget) }
    var version by remember(instance.id) { mutableStateOf("1.0.0") }
    var plan by remember(instance.id) { mutableStateOf<MrpackExportPlan?>(null) }
    var loading by remember(instance.id) { mutableStateOf(true) }
    var error by remember(instance.id) { mutableStateOf<String?>(null) }
    var attempt by remember(instance.id) { mutableIntStateOf(0) }
    var exporting by remember(instance.id) { mutableStateOf(false) }
    // 顶层内容扫描：先列出条目，再逐步补全文件数与体积；关闭对话框即取消。
    var scan by remember(instance.id) { mutableStateOf<PackExportScan?>(null) }
    var scanError by remember(instance.id) { mutableStateOf<String?>(null) }
    var scanAttempt by remember(instance.id) { mutableIntStateOf(0) }
    // 勾选只按条目键保存；touched 记录用户手动改过的行，切换格式时不重置。
    var checked by remember(instance.id) { mutableStateOf<Set<String>>(emptySet()) }
    var touched by remember(instance.id) { mutableStateOf<Set<String>>(emptySet()) }
    // 资源管理器「复制路径」会带引号，先去掉再解析，避免把合法路径判成无效。
    val destination = runCatching { Path.of(target.trim().trim('"').trim()) }.getOrNull()
    val fileName = destination?.fileName?.toString().orEmpty()
    val packName =
        if (fileName.endsWith(".${format.extension}", true)) {
            fileName.dropLast(format.extension.length + 1)
        } else {
            fileName
        }
    val versionText = version.trim()
    val validName = packName.isNotBlank() && packName.length <= 200
    val validVersion = versionText.isNotBlank() && versionText.length <= 100
    val validTarget =
        destination != null && fileName.endsWith(".${format.extension}", true) && validName
    val busy = actions.controller.busy
    LaunchedEffect(instance.id, scanAttempt) {
        scanError = null
        try {
            actions.controller.content
                .exportEntries(instance.id)
                .collect { scan = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            scanError = failure.message ?: "无法读取实例目录"
        }
    }
    // 条目第一次到达时套用默认勾选（mods/config/resourcepacks/shaderpacks/options.txt）；
    // 之后扫描只更新数字，不会覆盖用户手动改动。默认值与格式无关，切换格式时保持勾选。
    val entryKeys = scan?.entries?.map { it.key }
    LaunchedEffect(entryKeys) {
        val entries = scan?.entries ?: return@LaunchedEffect
        val next = defaultExportEntries(entries).toMutableSet()
        for (key in touched) if (key in checked) next += key else next -= key
        checked = next
    }
    val selectableEntries =
        remember(scan?.entries, format) {
            scan?.entries.orEmpty().filter {
                it.selectable && (format == PackExportFormat.Backup || !it.backupOnly)
            }
        }
    val selectableKeys =
        remember(selectableEntries) { selectableEntries.mapTo(mutableSetOf()) { it.key } }
    val selectedEntries = checked.filterTo(mutableSetOf()) { it in selectableKeys }
    // 勾选日志行本身就是显式同意：只有完整备份会带着它重新预览。
    val includeLogs =
        format == PackExportFormat.Backup &&
            scan?.entries.orEmpty().any { it.backupOnly && it.key in selectedEntries }
    // 打开即预览：只读扫描实例目录，并按哈希查询可用的下载来源（网络失败只记为警告）。
    LaunchedEffect(instance.id, attempt, format, includeLogs) {
        loading = true
        error = null
        try {
            plan =
                withContext(Dispatchers.IO) {
                    actions.controller.content.previewMrpackExport(
                        instance.id,
                        MrpackExportMetadata(stem, "1.0.0"),
                        resolveDownloads = format == PackExportFormat.Modrinth,
                        resolveCurseForge = format == PackExportFormat.CurseForge,
                        includeLogs = includeLogs,
                    )
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "无法读取实例目录"
        } finally {
            loading = false
        }
    }
    val prepared = plan
    val localPaths =
        remember(prepared, format) {
            prepared?.let { exportLocalResourcePaths(it, format) }.orEmpty()
        }
    val downloads =
        if (format == PackExportFormat.Modrinth) {
            prepared?.files.orEmpty().count { it.downloadUrl != null }
        } else {
            0
        }
    val managedFiles =
        if (format == PackExportFormat.CurseForge) {
            prepared?.files.orEmpty().count { it.curseForgeProjectId != null }
        } else {
            0
        }
    val selection =
        remember(prepared, format, options, selectedEntries) {
            prepared?.let { exportSelectedPaths(it, format, options, selectedEntries) }.orEmpty()
        }
    val disabledMods =
        remember(prepared) {
            prepared?.files.orEmpty().count {
                it.path.startsWith("mods/", true) &&
                    it.path.substringAfterLast('/').endsWith(".disabled", true)
            }
        }

    // 清单里的开关按实际勾选写入，完整备份的记录与归档内容保持一致。
    fun effectiveOptions(): PackExportOptions =
        options.copy(
            includeSaves = "saves" in selectedEntries,
            includeScreenshots = "screenshots" in selectedEntries,
            includeOptionsTxt =
                selectedEntries.any {
                    it == "options.txt" || it == "optionsof.txt" || it == "optionsshaders.txt"
                },
            includeLogs = includeLogs,
        )

    fun toggleEntry(
        key: String,
        on: Boolean,
    ) {
        touched = touched + key
        checked = if (on) checked + key else checked - key
    }
    val canConfirm =
        prepared != null &&
            prepared.files.isNotEmpty() &&
            selection.isNotEmpty() &&
            !loading &&
            !exporting &&
            !busy &&
            validTarget &&
            validVersion
    val currentScan = scan
    Modal(
        "导出整合包 / 完整备份",
        "${instance.name} · ${format.title}",
        width = 640.dp,
        onDismiss = onDismiss,
    ) {
        SimpleDropdown(
            format.name,
            PackExportFormat.entries.map { it.name to it.title },
            { name ->
                val chosen = PackExportFormat.valueOf(name)
                target =
                    runCatching {
                        val file = Path.of(target.trim().trim('"'))
                        file
                            .resolveSibling(
                                file.fileName.toString().substringBeforeLast('.') +
                                    "." +
                                    chosen.extension,
                            ).toString()
                    }.getOrDefault(target)
                // 默认勾选与格式无关：保留用户当前选择，只丢弃已不存在的条目；选项也不重置。
                checked = scan?.let { retainExportSelection(it.entries, checked) } ?: checked
                format = chosen
            },
            Modifier.fillMaxWidth(),
            enabled = !exporting,
        )
        Text(
            format.purpose,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            CompactTextField(
                target,
                { target = it },
                Modifier.weight(1f),
                label = "目标文件",
                placeholder = "$stem.${format.extension}",
                enabled = !exporting,
            )
            SecondaryButton(
                {
                    actions.run {
                        actions.choose("选择整合包导出目录", directory = true).firstOrNull()?.let {
                            target = it.resolve(fileName.ifBlank { "$stem.mrpack" }).toString()
                        }
                    }
                },
                enabled = !exporting,
            ) {
                Text("选择目录…", fontSize = 12.sp)
            }
        }
        CompactTextField(
            version,
            { version = it.take(100) },
            Modifier.fillMaxWidth(),
            label = "整合包版本",
            placeholder = "1.0.0",
            enabled = !exporting,
        )
        Text(
            "导出文件需放在游戏目录与启动器数据目录之外；导出在后台任务中写入，进度在日志页。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 中间区域单独滚动，底部按钮始终可见；顶层条目很多时也只在这里滚动。
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ExportEntriesSection(
                ExportEntriesState(
                    loaded = currentScan != null,
                    scanError = scanError,
                    selectableEntries = selectableEntries,
                    selectedEntries = selectedEntries,
                    exporting = exporting,
                    omitted = currentScan?.omitted.orEmpty(),
                ),
                onRetry = { scanAttempt++ },
                onToggle = ::toggleEntry,
            )
            ExportPreviewSection(
                ExportPreviewState(
                    loading = loading,
                    error = error,
                    totalFiles = prepared?.files?.size,
                    selectedFiles = selection.size,
                    format = format,
                    options = options,
                    localResources = localPaths.size,
                    downloads = downloads,
                    managedFiles = managedFiles,
                    disabledMods = disabledMods,
                    exporting = exporting,
                    warnings = prepared?.warnings.orEmpty(),
                ),
                onRetry = { attempt++ },
                onOptionsChange = { options = it },
            )
        }
        if (prepared != null) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        }
        DialogActions(
            confirmText =
                when {
                    exporting -> if (format == PackExportFormat.Backup) "正在备份…" else "正在导出…"
                    format == PackExportFormat.Backup -> "生成备份"
                    else -> "开始导出"
                },
            enabled = canConfirm,
            onCancel = onDismiss,
            onConfirm = {
                val current = plan
                val destinationPath = destination
                if (current != null && destinationPath != null) {
                    exporting = true
                    error = null
                    actions.run {
                        try {
                            val metadata = MrpackExportMetadata(packName, versionText)
                            val wantsLogs = includeLogs
                            // 名称、版本或日志勾选改过时重新预览，确保清单与当前输入一致。
                            val fresh =
                                if (current.metadata == metadata &&
                                    current.includesLogs == wantsLogs
                                ) {
                                    current
                                } else {
                                    withContext(Dispatchers.IO) {
                                        actions.controller.content.previewMrpackExport(
                                            instance.id,
                                            metadata,
                                            resolveDownloads = format == PackExportFormat.Modrinth,
                                            resolveCurseForge =
                                                format == PackExportFormat.CurseForge,
                                            includeLogs = wantsLogs,
                                        )
                                    }
                                }
                            val chosen = effectiveOptions()
                            val local = exportLocalResourcePaths(fresh, format)
                            actions.controller.exportMrpack(
                                fresh,
                                destinationPath,
                                exportSelectedPaths(fresh, format, chosen, selectedEntries),
                                if (chosen.includeLocalResources) local else emptySet(),
                                format,
                                chosen,
                            )
                            onStarted()
                            onDismiss()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            exporting = false
                            error = failure.message ?: "无法开始导出，请稍后重试"
                        }
                    }
                }
            },
            hint =
                when {
                    loading -> "正在读取导出内容…"
                    prepared == null -> ""
                    !validTarget -> "目标文件需要是 .${format.extension} 路径"
                    !validVersion -> "版本号需要 1–100 个字符"
                    busy -> "请等待当前任务结束再导出"
                    selection.isEmpty() && selectedEntries.isNotEmpty() ->
                        "勾选的内容没有可写入的文件；本地资源需先确认分发权限"
                    selection.isEmpty() -> "请至少选择一项要写入的内容"
                    else -> "取消不会留下文件。"
                },
        )
    }
}
