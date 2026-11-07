// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 集中整合包导出状态，避免与目录迁移弹窗互相依赖。

package dev.molifox.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.molifox.core.GameInstance
import dev.molifox.core.PackExportFormat
import dev.molifox.core.PackExportOptions
import dev.molifox.core.PackExportScan
import dev.molifox.core.retainExportSelection
import java.nio.file.Path

/**
 * 导出页面只列选择；读取选中文件、可选远程查询与压缩全部在导出任务中执行。
 * 「选择内容」按实例目录的真实顶层条目勾选（文件夹整段写入或整段排除），
 * 默认只勾 mods/config/resourcepacks/shaderpacks；取消不会留下半写文件。
 */
@Composable
fun ExportMrpackPage(
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
            // 默认目标名跟随初始格式的扩展名；切换格式时只替换扩展名，保留用户改过的名字。
            val fileName = "$stem.${PackExportFormat.Modrinth.extension}"
            runCatching {
                Path
                    .of(System.getProperty("user.home", "."))
                    .toAbsolutePath()
                    .resolve(fileName)
                    .toString()
            }.getOrDefault(fileName)
        }
    var target by remember(instance.id) { mutableStateOf(defaultTarget) }
    var packName by remember(instance.id) { mutableStateOf(instance.name) }
    var version by remember(instance.id) { mutableStateOf("1.0.0") }
    var error by remember(instance.id) { mutableStateOf<String?>(null) }
    var exporting by remember(instance.id) { mutableStateOf(false) }
    // 顶层内容扫描：先列出条目，再逐步补全文件数与体积；关闭对话框即取消。
    var scan by remember(instance.id) { mutableStateOf<PackExportScan?>(null) }
    var scanError by remember(instance.id) { mutableStateOf<String?>(null) }
    var scanAttempt by remember(instance.id) { mutableIntStateOf(0) }
    // 勾选只按条目键保存；touched 记录用户手动改过的行，切换格式时不重置。
    var checked by remember(instance.id) { mutableStateOf<Set<String>>(emptySet()) }
    var touched by remember(instance.id) { mutableStateOf<Set<String>>(emptySet()) }
    // 树里被单独取消的文件/子目录；父文件夹仍勾选时只排除这些路径。
    var excludedPaths by remember(instance.id) { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(instance.id, scanAttempt) {
        scanError = null
        collectExportScan(actions, instance.id, { scan = it }) { scanError = it }
    }
    // 条目第一次到达时套用默认勾选（mods/config/resourcepacks/shaderpacks/options.txt）；
    // 之后扫描只更新数字，不会覆盖用户手动改动。默认值与格式无关，切换格式时保持勾选。
    val entryKeys = scan?.entries?.map { it.key }
    LaunchedEffect(entryKeys) {
        val entries = scan?.entries ?: return@LaunchedEffect
        checked = defaultExportChecked(entries, touched, checked)
    }
    val selectableEntries = rememberSelectableExportEntries(scan, format)
    val selectableKeys = rememberSelectableExportKeys(selectableEntries)
    val selectedEntries = checked.filterTo(mutableSetOf()) { it in selectableKeys }
    val includeLogs = exportIncludeLogs(format, scan, selectedEntries)
    val view = ExportMrpackView(target, format, version, packName)

    fun toggleEntry(
        key: String,
        on: Boolean,
    ) {
        touched = touched + key
        checked = if (on) checked + key else checked - key
        excludedPaths =
            excludedPaths.filterNot { it == key || it.startsWith("$key/") }.toSet()
    }

    fun toggleNode(
        path: String,
        on: Boolean,
    ) {
        excludedPaths = if (on) excludedPaths - path else excludedPaths + path
    }
    // busy 是快照值：挂在 state 上重算，导出任务开始/结束时确认按钮才会刷新。
    val launcherBusy = rememberLauncherBusy(actions)
    val canConfirm =
        exportCanConfirm(
            selectedEntries.size,
            exporting,
            launcherBusy,
            view.validTarget,
            view.validVersion,
        )
    ExportMrpackPageBody(
        instance = instance,
        actions = actions,
        format = format,
        stem = stem,
        target = target,
        packName = packName,
        version = version,
        exporting = exporting,
        error = error,
        scan = scan,
        scanError = scanError,
        selectableEntries = selectableEntries,
        selectedEntries = selectedEntries,
        excludedPaths = excludedPaths,
        view = view,
        includeLogs = includeLogs,
        options = options,
        canConfirm = canConfirm,
        onTargetChange = { target = it },
        onPackNameChange = { packName = it },
        onVersionChange = { version = it },
        onFormatChosen = { name ->
            val chosen = PackExportFormat.valueOf(name)
            target = exportTargetWithExtension(target, chosen.extension)
            // ZIP 固定全量打包；Modrinth 保留用户当前的下载/上传选项。
            options =
                if (chosen == PackExportFormat.Modrinth) {
                    options
                } else {
                    PackExportOptions(
                        remoteResources = false,
                    )
                }
            // 默认勾选与格式无关：保留用户当前选择，只丢弃已不存在的条目。
            checked = scan?.let { retainExportSelection(it.entries, checked) } ?: checked
            format = chosen
        },
        onRetryScan = { scanAttempt++ },
        onToggleEntry = { key, on -> toggleEntry(key, on) },
        onToggleNode = { path, on -> toggleNode(path, on) },
        onOptionsChange = { options = it },
        onExportStart = {
            exporting = true
            error = null
        },
        onExportFailure = {
            exporting = false
            error = it
        },
        onStarted = onStarted,
        onDismiss = onDismiss,
    )
}
