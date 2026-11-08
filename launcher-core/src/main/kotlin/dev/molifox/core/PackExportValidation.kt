// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责导出包的写后校验：必需条目、勾选内容、条目大小与条目名安全。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.nio.file.Path
import java.util.zip.ZipFile

private val payloadPrefixes = listOf(".minecraft/", "client-overrides/", "overrides/", "minecraft/")

/**
 * 写完后重新打开归档，按格式断言必需条目、清单字段，并确认没有写入私密文件。
 * 任何一项不成立都当作导出失败，旧文件保持不变。
 */
internal fun validatePackExport(
    archive: Path,
    format: PackExportFormat,
    layout: PackExportLayout,
    plan: MrpackExportPlan,
    options: PackExportOptions,
    selected: Set<String>,
) {
    val allowLogs = format == PackExportFormat.Backup && options.includeLogs
    ZipFile(archive.toFile()).use { zip ->
        val names =
            zip
                .entries()
                .asSequence()
                .map { it.name }
                .toList()
        val payloadNames = names.filterNot { it.endsWith("/") }
        require(payloadNames.size == payloadNames.distinct().size) {
            I18n.t("packsA1.packExportValidation.duplicateEntries")
        }
        validateRequiredEntries(zip, layout)
        validateSelectedPayload(layout, plan, format, selected)
        validatePayloadEntries(zip, layout)
        validateArchiveEntryNames(names, allowLogs)
        validateExportManifest(zip, format, layout, plan)
    }
}

private fun validateRequiredEntries(
    zip: ZipFile,
    layout: PackExportLayout,
) {
    for (name in layout.requiredEntries) {
        require(zip.getEntry(name) != null) {
            I18n.t("packsA1.packExportValidation.requiredEntryMissing", name)
        }
    }
}

/** 只校验用户本次勾选的内容：未勾选的文件夹既不要求存在，也不允许混进来。 */
private fun validateSelectedPayload(
    layout: PackExportLayout,
    plan: MrpackExportPlan,
    format: PackExportFormat,
    selected: Set<String>,
) {
    val payloadPaths = layout.payload.map { it.second.path }
    require(payloadPaths.all { it in selected }) {
        I18n.t(
            "packsA1.packExportValidation.unselectedContent",
            payloadPaths.first { it !in selected },
        )
    }
    val embeddedSelected =
        selected.filter { path ->
            plan.files.any { it.path == path && it.embeddedIn(format) }
        }
    require(embeddedSelected.all { it in payloadPaths }) {
        I18n.t(
            "packsA1.packExportValidation.selectedContentMissing",
            embeddedSelected.first { it !in payloadPaths },
        )
    }
}

private fun validatePayloadEntries(
    zip: ZipFile,
    layout: PackExportLayout,
) {
    for ((archivePath, file) in layout.payload) {
        val entry = zip.getEntry(archivePath)
        require(entry != null && !entry.isDirectory && entry.size == file.size) {
            I18n.t("packsA1.packExportValidation.entrySizeMismatch", archivePath)
        }
        require(
            zip.getInputStream(entry).use { it.readNBytes(1) }.size == 1 || file.size == 0L,
        ) {
            I18n.t("packsA1.packExportValidation.entryEmpty", archivePath)
        }
    }
}

private fun validateArchiveEntryNames(
    names: List<String>,
    allowLogs: Boolean,
) {
    for (name in names) {
        require(
            !name.startsWith("/") &&
                name.split('/').none { it == ".." } &&
                !payloadPrefixes.any {
                    name.startsWith(it) && forbiddenExportEntry(name, allowLogs)
                },
        ) {
            I18n.t("packsA1.packExportValidation.forbiddenEntry", name)
        }
    }
}

private fun forbiddenExportEntry(
    name: String,
    allowLogs: Boolean,
): Boolean {
    val relative =
        payloadPrefixes.firstOrNull { name.startsWith(it) }?.let { name.removePrefix(it) }
            ?: return false
    return excludedExportPath(relative, allowLogs)
}
