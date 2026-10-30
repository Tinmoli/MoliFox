// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责整合包导出对话框与导出端共用的勾选、默认值与可选内容判定。

package dev.molifox.core

import java.util.Locale

/** 会被原样打包的本地资源：需要用户确认分发权限。备份只留在本机，不做这层限制。 */
fun exportLocalResourcePaths(
    plan: MrpackExportPlan,
    format: PackExportFormat,
): Set<String> =
    if (format == PackExportFormat.Backup) {
        emptySet()
    } else {
        plan.files
            .filter { it.requiresPermission && it.embeddedIn(format) }
            .map { it.path }
            .toSet()
    }

/**
 * 与界面共用的默认勾选集：默认内容 + 开关控制的可选内容，并剔除禁用模组。
 * 导出端再按同一规则过滤，避免界面计数与实际写入不一致。
 */
fun exportDefaultSelection(
    plan: MrpackExportPlan,
    format: PackExportFormat,
    options: PackExportOptions,
): Set<String> {
    val all = plan.files
    val selected =
        if (format == PackExportFormat.Backup) {
            all.map { it.path }.toMutableSet()
        } else {
            plan.defaultSelection.toMutableSet()
        }
    if (format == PackExportFormat.Backup) {
        if (!options.includeSaves) selected -= all.exportPathsWhere(::saveExportPath)
        if (!options.includeScreenshots) selected -= all.exportPathsWhere(::screenshotExportPath)
        if (!options.includeOptionsTxt) selected -= all.exportPathsWhere(::preferenceExportPath)
        if (!options.includeLogs) selected -= all.exportPathsWhere(::backupOnlyExportPath)
    } else {
        if (options.includeSaves) selected += all.exportPathsWhere(::saveExportPath)
        if (options.includeScreenshots) selected += all.exportPathsWhere(::screenshotExportPath)
        if (options.includeOptionsTxt) selected += all.exportPathsWhere(::preferenceExportPath)
        selected -= all.exportPathsWhere(::backupOnlyExportPath)
        if (options.includeLocalResources) {
            selected += exportLocalResourcePaths(plan, format)
        } else {
            selected -= exportLocalResourcePaths(plan, format)
        }
    }
    if (options.enabledModsOnly) {
        selected -= all.exportPathsWhere(::disabledModExportPath)
    }
    return selected
}

/** 文件列表里命中某个分类的文件路径，供勾选集复用。 */
private fun List<MrpackExportFile>.exportPathsWhere(predicate: (String) -> Boolean): List<String> =
    filter { predicate(it.path) }.map { it.path }

/**
 * 默认勾选：只勾 mods/、config/、resourcepacks/、shaderpacks/、options.txt（存在才勾）。
 * 所有格式共用同一套默认值，因此切换格式不改变用户已勾选的内容。
 */
private val defaultExportEntryKeys =
    setOf("mods", "config", "resourcepacks", "shaderpacks", "options.txt")

fun defaultExportEntries(entries: List<PackExportEntry>): Set<String> =
    entries
        .filter { it.selectable && it.backupOnly.not() && it.key in defaultExportEntryKeys }
        .map { it.key }
        .toSet()

/**
 * 切换格式时保留用户勾选：默认值所有格式一致，不重新套用；只丢弃已不存在的条目。
 * 这样用户手动勾选/取消的行不会因为换格式被重置。
 */
fun retainExportSelection(
    entries: List<PackExportEntry>,
    checked: Set<String>,
): Set<String> {
    val keys = entries.mapTo(mutableSetOf()) { it.key }
    return checked.filterTo(mutableSetOf()) { it in keys }
}

/**
 * 把顶层勾选落到具体文件：文件夹未勾选时其下所有文件都不写入。
 * 与旧开关共用规则：日志只进完整备份、禁用模组按选项排除、本地资源未经许可不分发。
 */
fun exportSelectedPaths(
    plan: MrpackExportPlan,
    format: PackExportFormat,
    options: PackExportOptions,
    entries: Set<String>,
): Set<String> {
    val selected =
        plan.files
            .filter { it.path.substringBefore('/').lowercase(Locale.ROOT) in entries }
            .filter { format == PackExportFormat.Backup || !backupOnlyExportPath(it.path) }
            .map { it.path }
            .toMutableSet()
    if (format != PackExportFormat.Backup && !options.includeLocalResources) {
        selected -= exportLocalResourcePaths(plan, format)
    }
    if (options.enabledModsOnly) {
        selected -= plan.files.filter { disabledModExportPath(it.path) }.map { it.path }
    }
    return selected
}

/** 实例自身的版本文件与升级备份副本：属于实例元数据，不当作整合包内容。 */
internal fun instanceOwnExportFile(
    name: String,
    versionId: String,
): Boolean {
    val lower = name.lowercase(Locale.ROOT)
    if (lower.contains(".json.molifox-backup-")) return true
    val id = versionId.lowercase(Locale.ROOT)
    return id.isNotBlank() && (lower == "$id.json" || lower == "$id.jar")
}

/** 启动器/其他启动器的私有数据与包元数据：永不出现在可选列表里。 */
internal fun launcherPrivateExportEntry(name: String): Boolean {
    val key = name.lowercase(Locale.ROOT)
    if (key.endsWith(".part")) return true
    if (key.startsWith(".molifox")) return true
    if (key.startsWith("credentials")) return true
    // 日志与缓存是可选的导出内容，不是私有数据。
    if (key in backupOnlyExportRoots || key == "cache" || key.startsWith(".cache")) return false
    if (key == "backups") return false
    return key in launcherPrivateExportNames
}

internal val launcherPrivateExportNames =
    setOf(
        "molifox",
        "accounts",
        "tokens",
        "launcher.json",
        "launcher.json.bak",
        "accounts.json",
        "hmcl.json",
        "hmclversion.cfg",
        "instance.cfg",
        "launcher_profiles.json",
        "launcher_accounts.json",
        "launcher_msa_credentials.bin",
        "usernamecache.json",
        "usercache.json",
        "realms_persistence.json",
        "modrinth.index.json",
        "mcbbs.packmeta",
        "molifox-instance.json",
        "molifox-backup.json",
        "manifest.json",
        "modpack.json",
        "mmc-pack.json",
        "libraries",
        "assets",
        "versions",
        "natives",
        ".git",
        ".hmcl",
        ".fabric",
        ".quilt",
    )
