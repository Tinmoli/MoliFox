// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责导出对话框的派生值与提示文案：只做纯计算，不渲染界面。

package dev.molifox.desktop

import dev.molifox.core.MrpackExportPlan
import dev.molifox.core.PackExportEntry
import dev.molifox.core.PackExportFormat
import dev.molifox.core.PackExportOptions
import dev.molifox.core.PackExportScan
import dev.molifox.core.defaultExportEntries
import java.nio.file.Path

/** 与整合包清单的名称/版本上限保持一致。 */
private const val MAX_EXPORT_NAME_LENGTH = 200
private const val MAX_EXPORT_VERSION_LENGTH = 100

/** 导出对话框的派生状态：路径、名称、校验结果与文件计数都在这里计算。 */
internal class ExportMrpackView(
    target: String,
    format: PackExportFormat,
    version: String,
    prepared: MrpackExportPlan?,
) {
    val destination: Path? = runCatching { Path.of(target.trim().trim('"').trim()) }.getOrNull()

    val fileName: String = destination?.fileName?.toString().orEmpty()

    val packName: String =
        if (fileName.endsWith(".${format.extension}", true)) {
            fileName.dropLast(format.extension.length + 1)
        } else {
            fileName
        }

    val versionText: String = version.trim()

    val validName: Boolean = packName.isNotBlank() && packName.length <= MAX_EXPORT_NAME_LENGTH

    val validVersion: Boolean =
        versionText.isNotBlank() && versionText.length <= MAX_EXPORT_VERSION_LENGTH

    val validTarget: Boolean =
        destination != null && fileName.endsWith(".${format.extension}", true) && validName

    val downloads: Int =
        if (format == PackExportFormat.Modrinth) {
            prepared?.files.orEmpty().count { it.downloadUrl != null }
        } else {
            0
        }

    val managedFiles: Int =
        if (format == PackExportFormat.CurseForge) {
            prepared?.files.orEmpty().count { it.curseForgeProjectId != null }
        } else {
            0
        }

    val disabledMods: Int =
        prepared?.files.orEmpty().count {
            it.path.startsWith("mods/", true) &&
                it.path.substringAfterLast('/').endsWith(".disabled", true)
        }
}

/** 切换导出格式时只替换目标文件的扩展名，保留用户改过的目录与文件名。 */
internal fun exportTargetWithExtension(
    target: String,
    extension: String,
): String =
    runCatching {
        val file = Path.of(target.trim().trim('"'))
        val name = file.fileName?.toString().orEmpty()
        if (name.isEmpty()) {
            file.toString()
        } else {
            val stem = name.substringBeforeLast('.', name).ifBlank { name }
            file.resolveSibling("$stem.$extension").toString()
        }
    }.getOrDefault(target)

/** 清单里的开关按实际勾选写入，完整备份的记录与归档内容保持一致。 */
internal fun exportEffectiveOptions(
    options: PackExportOptions,
    selectedEntries: Set<String>,
    includeLogs: Boolean,
): PackExportOptions =
    options.copy(
        includeSaves = "saves" in selectedEntries,
        includeScreenshots = "screenshots" in selectedEntries,
        includeOptionsTxt =
            selectedEntries.any {
                it == "options.txt" || it == "optionsof.txt" || it == "optionsshaders.txt"
            },
        includeLogs = includeLogs,
    )

/** 勾选日志行本身就是显式同意：只有完整备份会带着它重新预览。 */
internal fun exportIncludeLogs(
    format: PackExportFormat,
    scan: PackExportScan?,
    selectedEntries: Set<String>,
): Boolean =
    format == PackExportFormat.Backup &&
        scan?.entries.orEmpty().any { it.backupOnly && it.key in selectedEntries }

/** 只有预览就绪、有可写文件、目标与版本合法且没有任务在跑时才允许确认。 */
internal fun exportCanConfirm(
    prepared: MrpackExportPlan?,
    selectionSize: Int,
    loading: Boolean,
    exporting: Boolean,
    busy: Boolean,
    validTarget: Boolean,
    validVersion: Boolean,
): Boolean =
    prepared != null &&
        prepared.files.isNotEmpty() &&
        selectionSize > 0 &&
        !loading &&
        !exporting &&
        !busy &&
        validTarget &&
        validVersion

/** 确认按钮文案：完整备份与导出分开，正在写入时显示进度语义。 */
internal fun exportConfirmText(
    exporting: Boolean,
    format: PackExportFormat,
): String =
    when {
        exporting -> if (format == PackExportFormat.Backup) "正在备份…" else "正在导出…"
        format == PackExportFormat.Backup -> "生成备份"
        else -> "开始导出"
    }

/** 底部提示：按阻塞原因给出最短说明，优先解释用户能直接改的输入。 */
internal fun exportHint(
    loading: Boolean,
    prepared: MrpackExportPlan?,
    validTarget: Boolean,
    validVersion: Boolean,
    busy: Boolean,
    selectionSize: Int,
    selectedEntriesNotEmpty: Boolean,
    format: PackExportFormat,
): String =
    when {
        loading -> "正在读取导出内容…"
        prepared == null -> ""
        !validTarget -> "目标文件需要是 .${format.extension} 路径"
        !validVersion -> "版本号需要 1–100 个字符"
        busy -> "请等待当前任务结束再导出"
        selectionSize == 0 && selectedEntriesNotEmpty ->
            "勾选的内容没有可写入的文件；本地资源需先确认分发权限"
        selectionSize == 0 -> "请至少选择一项要写入的内容"
        else -> "取消不会留下文件。"
    }

/** 条目第一次到达时套用默认勾选，再用用户手动改过的行覆盖。 */
internal fun defaultExportChecked(
    entries: List<PackExportEntry>,
    touched: Set<String>,
    checked: Set<String>,
): Set<String> {
    val next = defaultExportEntries(entries).toMutableSet()
    for (key in touched) if (key in checked) next += key else next -= key
    return next
}
