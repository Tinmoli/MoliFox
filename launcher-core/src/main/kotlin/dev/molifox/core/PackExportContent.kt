// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责按导出格式组装归档结构：清单文档、内容条目、目录条目与必需条目。

package dev.molifox.core

/** 一次导出写入归档的文档、内容条目、目录条目、必需条目与图标路径。 */
internal data class PackExportContent(
    val documents: List<Pair<String, ByteArray>>,
    val payload: List<Pair<String, MrpackExportFile>>,
    val directories: List<String>,
    val required: List<String>,
    /** MultiMC/Prism 使用 `icons/<key>.png`，其它格式仍是根目录 `icon.png`。 */
    val iconPath: String = "icon.png",
)

/** 序列化清单文档：沿用全局 Gson 配置，输出与导出结构一致。 */
private fun document(
    name: String,
    value: Any,
): Pair<String, ByteArray> = name to gson.toJson(value).toByteArray(Charsets.UTF_8)

private fun textDocument(
    name: String,
    value: String,
): Pair<String, ByteArray> = name to value.toByteArray(Charsets.UTF_8)

internal fun packExportContent(
    format: PackExportFormat,
    plan: MrpackExportPlan,
    options: PackExportOptions,
    files: List<MrpackExportFile>,
    hasIcon: Boolean,
): PackExportContent =
    when (format) {
        PackExportFormat.Modrinth -> modrinthContent(plan, files)
        PackExportFormat.CurseForge -> curseForgeContent(plan, files)
        PackExportFormat.MultiMc -> multimcContent(plan, files, hasIcon)
        PackExportFormat.Mcbbs -> mcbbsContent(plan, files)
        PackExportFormat.Backup -> backupContent(plan, options, files)
    }

private fun modrinthContent(
    plan: MrpackExportPlan,
    files: List<MrpackExportFile>,
): PackExportContent =
    PackExportContent(
        documents = listOf(document("modrinth.index.json", mrpackIndex(plan, files))),
        payload =
            files.filter { it.downloadUrl == null }.map { "client-overrides/${it.path}" to it },
        directories = emptyList(),
        required = listOf("modrinth.index.json"),
    )

private fun curseForgeContent(
    plan: MrpackExportPlan,
    files: List<MrpackExportFile>,
): PackExportContent {
    // 同一项目只保留一个受管文件；其余重复版本的本地文件放进 overrides。
    val managed =
        files
            .filter { it.curseForgeProjectId != null && it.curseForgeFileId != null }
            .distinctBy { it.curseForgeProjectId }
    val managedPaths = managed.map { it.path }.toSet()
    return PackExportContent(
        documents = listOf(document("manifest.json", curseForgeManifest(plan, managed))),
        payload = files.filter { it.path !in managedPaths }.map { "overrides/${it.path}" to it },
        directories = listOf("overrides/"),
        required = listOf("manifest.json", "overrides/"),
    )
}

private fun multimcContent(
    plan: MrpackExportPlan,
    files: List<MrpackExportFile>,
    hasIcon: Boolean,
): PackExportContent {
    // Prism 只认 icons/<key>.png + instance.cfg 的 iconKey；没有实例图时用内置图标。
    val iconKey = if (hasIcon) MULTIMC_ICON_KEY else "default"
    val iconPath = if (hasIcon) "icons/$MULTIMC_ICON_KEY.png" else "icon.png"
    return PackExportContent(
        documents =
            listOf(
                textDocument("instance.cfg", multimcInstanceConfig(plan, iconKey)),
                document("mmc-pack.json", multimcManifest(plan)),
            ),
        payload = files.map { ".minecraft/${it.path}" to it },
        directories = if (hasIcon) listOf(".minecraft/", "icons/") else listOf(".minecraft/"),
        required =
            listOf("instance.cfg", "mmc-pack.json", ".minecraft/") +
                if (hasIcon) listOf(iconPath) else emptyList(),
        iconPath = iconPath,
    )
}

private fun mcbbsContent(
    plan: MrpackExportPlan,
    files: List<MrpackExportFile>,
): PackExportContent =
    PackExportContent(
        documents = listOf(document("mcbbs.packmeta", mcbbsManifest(plan, files))),
        payload = files.map { "overrides/${it.path}" to it },
        directories = listOf("overrides/"),
        required = listOf("mcbbs.packmeta", "overrides/"),
    )

private fun backupContent(
    plan: MrpackExportPlan,
    options: PackExportOptions,
    files: List<MrpackExportFile>,
): PackExportContent =
    PackExportContent(
        documents = listOf(document("molifox-backup.json", backupManifest(plan, options, files))),
        payload = files.map { "minecraft/${it.path}" to it },
        directories = listOf("minecraft/"),
        required = listOf("molifox-backup.json", "minecraft/"),
    )
