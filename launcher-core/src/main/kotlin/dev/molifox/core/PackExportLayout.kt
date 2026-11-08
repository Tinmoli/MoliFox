// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责各导出格式的归档结构、清单生成与写后校验。

package dev.molifox.core

import java.time.Instant

/** MultiMC/Prism 的实例图标键：导出写 `icons/<key>.png`，导入按 iconKey 回读。 */
internal const val MULTIMC_ICON_KEY = "pack"

/** 一次导出的归档结构：清单文档、按文件复制的条目、目录条目与必需条目。 */
internal class PackExportLayout(
    val documents: List<Pair<String, ByteArray>>,
    val payload: List<Pair<String, MrpackExportFile>>,
    val directories: List<String>,
    val icon: ByteArray?,
    val requiredEntries: List<String>,
    /** 图标在归档内的路径：MultiMC/Prism 用 `icons/<key>.png`，其它格式是 `icon.png`。 */
    val iconPath: String = "icon.png",
) {
    val totalEntries: Int
        get() =
            directories.size + documents.size + payload.size + if (icon != null) 1 else 0
}

internal fun buildPackExportLayout(
    format: PackExportFormat,
    plan: MrpackExportPlan,
    options: PackExportOptions,
    files: List<MrpackExportFile>,
    icon: ByteArray?,
): PackExportLayout {
    val content = packExportContent(format, plan, options, files, icon != null)
    return PackExportLayout(
        content.documents,
        content.payload,
        content.directories,
        icon,
        content.required,
        content.iconPath,
    )
}

private fun mrpackLoaderKey(loader: LoaderType): String? =
    when (loader) {
        LoaderType.Vanilla -> null
        LoaderType.Fabric,
        LoaderType.LegacyFabric,
        -> "fabric-loader"
        LoaderType.Quilt -> "quilt-loader"
        LoaderType.Forge -> "forge"
        LoaderType.NeoForge -> "neoforge"
        LoaderType.OptiFine,
        LoaderType.LiteLoader,
        LoaderType.Cleanroom,
        -> null
    }

internal fun mrpackIndex(
    plan: MrpackExportPlan,
    files: List<MrpackExportFile>,
): Map<String, Any> {
    val dependencies = linkedMapOf("minecraft" to plan.components.gameVersion)
    mrpackLoaderKey(plan.components.loader)?.let {
        dependencies[it] = plan.components.loaderVersion
    }
    return mapOf(
        "formatVersion" to 1,
        "game" to "minecraft",
        "name" to plan.metadata.name,
        "versionId" to plan.metadata.version,
        "summary" to plan.metadata.summary,
        "dependencies" to dependencies,
        "files" to
            files
                .filter { it.downloadUrl != null }
                .map { file ->
                    mapOf(
                        "path" to file.path,
                        "fileSize" to file.size,
                        "hashes" to mapOf("sha1" to file.sha1, "sha512" to file.sha512),
                        "downloads" to listOf(file.downloadUrl),
                        "env" to mapOf("client" to "required", "server" to "unsupported"),
                    )
                },
    )
}

/** CurseForge 清单里的加载器 id。 */
private fun curseForgeLoaderId(loader: LoaderType): String? =
    when (loader) {
        LoaderType.Vanilla -> null
        LoaderType.Fabric,
        LoaderType.LegacyFabric,
        -> "fabric"
        LoaderType.Quilt -> "quilt"
        LoaderType.Forge -> "forge"
        LoaderType.NeoForge -> "neoforge"
        LoaderType.OptiFine,
        LoaderType.LiteLoader,
        LoaderType.Cleanroom,
        -> null
    }

internal fun curseForgeManifest(
    plan: MrpackExportPlan,
    managed: List<MrpackExportFile>,
): Map<String, Any> {
    val loaderId = curseForgeLoaderId(plan.components.loader)
    return mapOf(
        "minecraft" to
            mapOf(
                "version" to plan.components.gameVersion,
                "modLoaders" to
                    if (loaderId == null) {
                        emptyList<Map<String, Any>>()
                    } else {
                        listOf(
                            mapOf(
                                "id" to "$loaderId-${plan.components.loaderVersion}",
                                "primary" to true,
                            ),
                        )
                    },
            ),
        "manifestType" to "minecraftModpack",
        "manifestVersion" to 1,
        "name" to plan.metadata.name,
        "version" to plan.metadata.version,
        "author" to "",
        "files" to
            managed.map {
                mapOf(
                    "projectID" to it.curseForgeProjectId,
                    "fileID" to it.curseForgeFileId,
                    "required" to true,
                )
            },
        "overrides" to "overrides",
    )
}

internal fun mcbbsManifest(
    plan: MrpackExportPlan,
    files: List<MrpackExportFile>,
): Map<String, Any> =
    mapOf(
        "manifestType" to "minecraftModpack",
        "manifestVersion" to 2,
        "name" to plan.metadata.name,
        "version" to plan.metadata.version,
        "description" to plan.metadata.summary,
        "author" to "",
        "fileApi" to "",
        "addons" to
            listOf(mapOf("id" to "game", "version" to plan.components.gameVersion)) +
            if (plan.components.loader == LoaderType.Vanilla) {
                emptyList()
            } else {
                listOf(
                    mapOf(
                        "id" to
                            plan.components.loader.name
                                .lowercase(),
                        "version" to plan.components.loaderVersion,
                    ),
                )
            },
        "files" to
            files.map {
                mapOf(
                    "type" to "addon",
                    "path" to it.path,
                    "hash" to it.sha1,
                    "force" to true,
                )
            },
    )

internal fun backupManifest(
    plan: MrpackExportPlan,
    options: PackExportOptions,
    files: List<MrpackExportFile>,
): Map<String, Any> =
    mapOf(
        "formatVersion" to 1,
        "type" to "molifox-backup",
        "name" to plan.metadata.name,
        "version" to plan.metadata.version,
        "summary" to plan.metadata.summary,
        "createdAt" to Instant.now().toString(),
        "instance" to
            mapOf(
                "name" to plan.components.name,
                "gameVersion" to plan.components.gameVersion,
                "loader" to plan.components.loader.name,
                "loaderVersion" to plan.components.loaderVersion,
            ),
        "options" to
            mapOf(
                "includeSaves" to options.includeSaves,
                "includeScreenshots" to options.includeScreenshots,
                "includeOptionsTxt" to options.includeOptionsTxt,
                "enabledModsOnly" to options.enabledModsOnly,
                "includeLogs" to options.includeLogs,
            ),
        "files" to
            files.map {
                mapOf("path" to it.path, "size" to it.size, "sha1" to it.sha1)
            },
    )
