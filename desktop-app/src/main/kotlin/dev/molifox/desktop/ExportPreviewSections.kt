// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 只呈现导出内容与预览统计，通过回调交还选择和重试，不访问网络或磁盘。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.PackExportEntry
import dev.molifox.core.PackExportFormat
import dev.molifox.core.PackExportOptions

internal data class ExportEntriesState(
    val loaded: Boolean,
    val scanError: String?,
    val selectableEntries: List<PackExportEntry>,
    val selectedEntries: Set<String>,
    val exporting: Boolean,
    val omitted: List<String>,
)

internal data class ExportPreviewState(
    val loading: Boolean,
    val error: String?,
    val totalFiles: Int?,
    val selectedFiles: Int,
    val format: PackExportFormat,
    val options: PackExportOptions,
    val localResources: Int,
    val downloads: Int,
    val managedFiles: Int,
    val disabledMods: Int,
    val exporting: Boolean,
    val warnings: List<String>,
)

@Composable
internal fun ExportEntriesSection(
    state: ExportEntriesState,
    onRetry: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
) {
    with(state) {
        // 顶层内容扫描与归档预览互相独立：预览联网时也能先勾选。
        when {
            !loaded && scanError != null ->
                Panel(Modifier.fillMaxWidth()) {
                    EmptyState(
                        "无法读取实例目录",
                        scanError.orEmpty(),
                        Icons.Outlined.ErrorOutline,
                    ) {
                        TextButton({ onRetry() }) { Text("重试") }
                    }
                }
            !loaded ->
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    MutedText("正在读取实例目录的顶层内容…")
                }
            else -> {
                SettingsSection(
                    "选择内容",
                    "只列出实例目录的真实顶层条目；数量与体积按磁盘文件统计，勾选决定写入包里的内容。",
                ) {
                    if (selectableEntries.isEmpty()) {
                        MutedText("实例目录里没有可勾选的内容。")
                    }
                    selectableEntries.forEach { entry ->
                        ExportEntryRow(
                            entry = entry,
                            checked = entry.key in selectedEntries,
                            enabled = !exporting,
                        ) { onToggle(entry.key, it) }
                    }
                }
                if (omitted.isNotEmpty()) {
                    Text(
                        "不导出：${omitted.take(8).joinToString("、")}" +
                            if (omitted.size > 8) {
                                " 等 ${omitted.size} 项"
                            } else {
                                ""
                            },
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ExportPreviewSection(
    state: ExportPreviewState,
    onRetry: () -> Unit,
    onOptionsChange: (PackExportOptions) -> Unit,
) {
    with(state) {
        when {
            loading -> ExportLoadingSection(format)
            totalFiles == null ->
                Panel(Modifier.fillMaxWidth()) {
                    EmptyState(
                        "无法预览这次导出",
                        error.orEmpty(),
                        Icons.Outlined.ErrorOutline,
                    ) {
                        TextButton({ onRetry() }) { Text("重试") }
                    }
                }
            totalFiles == 0 ->
                Panel(Modifier.fillMaxWidth()) {
                    EmptyState(
                        "实例目录没有可导出的文件",
                        "日志默认不写入；启动器私有文件与实例版本文件不会进入导出包；请先安装游戏内容。",
                        Icons.Outlined.FolderOpen,
                    )
                }
            else -> {
                ExportPreviewStatistics(state)
                SettingsSection("导出选项", "在已勾选内容的基础上进一步筛选写入的文件。") {
                    ExportOptionRow(
                        "仅保留已启用的模组",
                        if (disabledMods > 0) {
                            "勾选后不打包 $disabledMods 个 .disabled 模组（不会改名为启用）"
                        } else {
                            "勾选后不打包 .disabled 模组（不会改名为启用）"
                        },
                        options.enabledModsOnly,
                        !exporting,
                    ) { onOptionsChange(options.copy(enabledModsOnly = it)) }
                }
                ExportAdditionalOptions(
                    format = format,
                    localResources = localResources,
                    disabledMods = disabledMods,
                    options = options,
                    exporting = exporting,
                    warnings = warnings,
                    onOptionsChange = onOptionsChange,
                )
            }
        }
    }
}

/** 预览阶段的进度提示：不同目标格式匹配的内容不同，文案随之变化。 */
@Composable
private fun ExportLoadingSection(format: PackExportFormat) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        MutedText(
            when (format) {
                PackExportFormat.Modrinth -> "正在读取实例文件并匹配下载来源…"
                PackExportFormat.CurseForge -> "正在读取实例文件并匹配 CurseForge 编号…"
                else -> "正在读取实例文件…"
            },
        )
    }
}

/** 统计下方的补充选项与提示：备份说明、本地资源授权、禁用模组与警告列表。 */
@Composable
private fun ExportAdditionalOptions(
    format: PackExportFormat,
    localResources: Int,
    disabledMods: Int,
    options: PackExportOptions,
    exporting: Boolean,
    warnings: List<String>,
    onOptionsChange: (PackExportOptions) -> Unit,
) {
    if (format == PackExportFormat.Backup) {
        Text(
            "这是完整备份，不是可导入的整合包：其他启动器不会把它识别成整合包，只用于还原或留档。" +
                "游戏运行库（assets / libraries / versions）与启动器私有数据不会写入。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else if (localResources > 0) {
        ExportOptionRow(
            "确认有权打包这些本地资源（$localResources 个）",
            "未走下载清单的模组、资源包或光影会原样写入包内；取消勾选则不含。",
            options.includeLocalResources,
            !exporting,
        ) { onOptionsChange(options.copy(includeLocalResources = it)) }
    }
    if (options.enabledModsOnly && disabledMods > 0) {
        Text(
            "已排除 $disabledMods 个禁用的模组文件。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    warnings.forEach { warning ->
        Text(
            warning,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExportPreviewStatistics(state: ExportPreviewState) {
    with(state) {
        SettingsSection(
            "预览",
            when (format) {
                PackExportFormat.Backup ->
                    "只读扫描实例目录；日志与崩溃报告仅在勾选后写入，疑似含登录凭据的日志会跳过。"
                PackExportFormat.CurseForge ->
                    "只读扫描实例目录并联网匹配 CurseForge 文件编号；导出时复核文件哈希。"
                PackExportFormat.Modrinth ->
                    "只读扫描实例目录并联网核对下载地址；导出时按同一份快照复核文件哈希。"
                else -> "只读扫描实例目录；导出时复核文件哈希，将选中内容写入包内。"
            },
        ) {
            SettingRow(
                "将包含的文件",
                "按上方勾选的顶层内容写入；未勾选的文件夹整段不写入",
            ) {
                Text("$selectedFiles / $totalFiles 个", fontSize = 13.sp)
            }
            if (format == PackExportFormat.Modrinth) {
                SettingRow("导入时联网下载", "只写入下载链接，包里不含这些文件的内容") {
                    Text("$downloads 个", fontSize = 13.sp)
                }
            }
            if (format == PackExportFormat.CurseForge) {
                SettingRow(
                    "写入受管清单",
                    if (managedFiles ==
                        0
                    ) {
                        "没有匹配到 CurseForge 编号；全部内容放进 overrides/，不会自动更新"
                    } else {
                        "已匹配 projectID/fileID；未匹配内容放进 overrides/"
                    },
                ) {
                    Text("$managedFiles 个", fontSize = 13.sp)
                }
            }
            if (format != PackExportFormat.Backup) {
                SettingRow(
                    "按本地文件分发",
                    if (localResources == 0) {
                        "模组、资源包和光影都匹配到了下载来源"
                    } else {
                        "这些模组、资源包或光影会原样写入包内"
                    },
                ) {
                    Text(
                        "${if (options.includeLocalResources) localResources else 0} 个",
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}
