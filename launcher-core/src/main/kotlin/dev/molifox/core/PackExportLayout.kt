// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责各导出格式的归档结构、清单生成与写后校验。

package dev.molifox.core

import java.nio.file.Path
import java.time.Instant
import java.util.zip.ZipFile
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readBytes

/** 一次导出的归档结构：清单文档、按文件复制的条目、目录条目与必需条目。 */
internal class PackExportLayout(
    val documents: List<Pair<String, ByteArray>>,
    val payload: List<Pair<String, MrpackExportFile>>,
    val directories: List<String>,
    val icon: ByteArray?,
    val requiredEntries: List<String>,
) {
    val totalEntries: Int
        get() =
            directories.size + documents.size + payload.size + if (icon != null) 1 else 0
}

private val payloadPrefixes = listOf(".minecraft/", "client-overrides/", "overrides/", "minecraft/")

internal fun buildPackExportLayout(
    format: PackExportFormat,
    plan: MrpackExportPlan,
    options: PackExportOptions,
    files: List<MrpackExportFile>,
    icon: ByteArray?,
): PackExportLayout {
    val documents = mutableListOf<Pair<String, ByteArray>>()
    val payload = mutableListOf<Pair<String, MrpackExportFile>>()
    val directories = mutableListOf<String>()
    var required = listOf<String>()

    fun document(
        name: String,
        value: Any,
    ) {
        documents += name to gson.toJson(value).toByteArray(Charsets.UTF_8)
    }

    fun text(
        name: String,
        value: String,
    ) {
        documents += name to value.toByteArray(Charsets.UTF_8)
    }
    when (format) {
        PackExportFormat.Modrinth -> {
            document("modrinth.index.json", mrpackIndex(plan, files))
            payload +=
                files.filter { it.downloadUrl == null }.map { "client-overrides/${it.path}" to it }
            required = listOf("modrinth.index.json")
        }
        PackExportFormat.CurseForge -> {
            // 同一项目只保留一个受管文件；其余重复版本的本地文件放进 overrides。
            val managed =
                files
                    .filter { it.curseForgeProjectId != null && it.curseForgeFileId != null }
                    .distinctBy { it.curseForgeProjectId }
            val managedPaths = managed.map { it.path }.toSet()
            document("manifest.json", curseForgeManifest(plan, managed))
            directories += "overrides/"
            payload +=
                files.filter { it.path !in managedPaths }.map { "overrides/${it.path}" to it }
            required = listOf("manifest.json", "overrides/")
        }
        PackExportFormat.MultiMc -> {
            text("instance.cfg", multimcInstanceConfig(plan))
            document("mmc-pack.json", multimcManifest(plan))
            directories += ".minecraft/"
            payload += files.map { ".minecraft/${it.path}" to it }
            required = listOf("instance.cfg", "mmc-pack.json", ".minecraft/")
        }
        PackExportFormat.Mcbbs -> {
            document("mcbbs.packmeta", mcbbsManifest(plan, files))
            directories += "overrides/"
            payload += files.map { "overrides/${it.path}" to it }
            required = listOf("mcbbs.packmeta", "overrides/")
        }
        PackExportFormat.Backup -> {
            document("molifox-backup.json", backupManifest(plan, options, files))
            directories += "minecraft/"
            payload += files.map { "minecraft/${it.path}" to it }
            required = listOf("molifox-backup.json", "minecraft/")
        }
    }
    return PackExportLayout(documents, payload, directories, icon, required)
}

private fun mrpackLoaderKey(loader: LoaderType): String? =
    when (loader) {
        LoaderType.Vanilla -> null
        LoaderType.Fabric -> "fabric-loader"
        LoaderType.Quilt -> "quilt-loader"
        LoaderType.Forge -> "forge"
        LoaderType.NeoForge -> "neoforge"
    }

/** MultiMC / Prism 组件 uid。 */
internal fun multimcLoaderUid(loader: LoaderType): String? =
    when (loader) {
        LoaderType.Vanilla -> null
        LoaderType.Fabric -> "net.fabricmc.fabric-loader"
        LoaderType.Quilt -> "org.quiltmc.quilt-loader"
        LoaderType.Forge -> "net.minecraftforge"
        LoaderType.NeoForge -> "net.neoforged"
    }

private fun mrpackIndex(
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

private fun curseForgeManifest(
    plan: MrpackExportPlan,
    managed: List<MrpackExportFile>,
): Map<String, Any> {
    val loaderId =
        when (plan.components.loader) {
            LoaderType.Vanilla -> null
            LoaderType.Fabric -> "fabric"
            LoaderType.Quilt -> "quilt"
            LoaderType.Forge -> "forge"
            LoaderType.NeoForge -> "neoforge"
        }
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

private fun multimcManifest(plan: MrpackExportPlan): Map<String, Any> {
    val components = mutableListOf<Map<String, Any>>()
    components +=
        mapOf(
            "uid" to "net.minecraft",
            "version" to plan.components.gameVersion,
            "important" to true,
        )
    multimcLoaderUid(plan.components.loader)?.let {
        components +=
            mapOf("uid" to it, "version" to plan.components.loaderVersion, "important" to true)
    }
    return mapOf("formatVersion" to 1, "components" to components)
}

private fun multimcInstanceConfig(plan: MrpackExportPlan): String {
    val name =
        plan.metadata.name
            .replace(Regex("[\\r\\n]"), " ")
            .trim()
    // MultiMC/Prism 的自由实例：OneSix + 由 mmc-pack.json 声明的组件。
    return buildString {
        appendLine("[General]")
        appendLine("ConfigVersion=1.4")
        appendLine("InstanceType=OneSix")
        appendLine("name=$name")
        appendLine("iconKey=default")
        appendLine("OverrideMemory=false")
        appendLine("OverrideJavaArgs=false")
        appendLine("OverrideGameTime=false")
        appendLine("joinServerOnLaunch=false")
    }
}

private fun mcbbsManifest(
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

private fun backupManifest(
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
        require(payloadNames.size == payloadNames.distinct().size) { "导出包包含重复条目" }
        for (name in layout.requiredEntries) {
            require(zip.getEntry(name) != null) { "导出包缺少必需条目：$name" }
        }
        // 只校验用户本次勾选的内容：未勾选的文件夹既不要求存在，也不允许混进来。
        val payloadPaths = layout.payload.map { it.second.path }
        require(payloadPaths.all { it in selected }) {
            "导出包包含未选择的内容：${payloadPaths.first { it !in selected }}"
        }
        val embeddedSelected =
            selected.filter { path ->
                plan.files.any { it.path == path && it.embeddedIn(format) }
            }
        require(embeddedSelected.all { it in payloadPaths }) {
            "导出包缺少已选择的内容：${embeddedSelected.first { it !in payloadPaths }}"
        }
        for ((archivePath, file) in layout.payload) {
            val entry = zip.getEntry(archivePath)
            require(entry != null && !entry.isDirectory && entry.size == file.size) {
                "导出包条目缺失或大小不符：$archivePath"
            }
            require(
                zip.getInputStream(entry).use { it.readNBytes(1) }.size == 1 || file.size == 0L,
            ) {
                "导出包条目为空：$archivePath"
            }
        }
        for (name in names) {
            require(
                !name.startsWith("/") &&
                    name.split('/').none { it == ".." } &&
                    !payloadPrefixes.any {
                        name.startsWith(it) &&
                            forbiddenExportEntry(name, allowLogs)
                    },
            ) {
                "导出包含有不应写入的条目：$name"
            }
        }
        when (format) {
            PackExportFormat.Modrinth -> {
                val index = requireNotNull(packJson(zip, "modrinth.index.json"))
                require(
                    index.get("formatVersion")?.asInt == 1 && index.string("game") == "minecraft",
                ) {
                    "modrinth.index.json 头部无效"
                }
                require(
                    index.string("name") == plan.metadata.name &&
                        index.string("versionId") == plan.metadata.version,
                ) {
                    "modrinth.index.json 名称或版本不符"
                }
                require(
                    index.obj("dependencies").string("minecraft") == plan.components.gameVersion,
                ) {
                    "modrinth.index.json 游戏版本不符"
                }
                val listed =
                    index.array("files").map { it.asJsonObject.string("path") }.toSet()
                require(listed.size == index.array("files").size()) { "modrinth.index.json 文件重复" }
                require(listed.intersect(layout.payload.map { it.second.path }.toSet()).isEmpty()) {
                    "下载清单与包内文件重复"
                }
                require(
                    index.array("files").all {
                        it.asJsonObject.string("path").isNotBlank() &&
                            it.asJsonObject.array("downloads").size() == 1
                    },
                ) {
                    "modrinth.index.json 文件条目缺少下载地址"
                }
            }
            PackExportFormat.CurseForge -> {
                val manifest = requireNotNull(packJson(zip, "manifest.json"))
                require(
                    manifest.string("manifestType") == "minecraftModpack" &&
                        manifest.string("manifestVersion") == "1",
                ) {
                    "manifest.json 不是 CurseForge 整合包清单"
                }
                require(
                    manifest.string("name") == plan.metadata.name &&
                        manifest.string("version") == plan.metadata.version,
                ) {
                    "manifest.json 名称或版本不符"
                }
                require(
                    manifest.string("overrides") == "overrides",
                ) { "manifest.json overrides 路径无效" }
                require(
                    manifest.obj("minecraft").string("version") == plan.components.gameVersion,
                ) {
                    "manifest.json 游戏版本不符"
                }
                val entries = manifest.array("files").map { it.asJsonObject }
                require(entries.map { it.string("projectID") }.distinct().size == entries.size) {
                    "manifest.json 重复项目"
                }
                require(
                    entries.all {
                        it.string("projectID").toLongOrNull() in 1L..0xffffffffL &&
                            it.string("fileID").toLongOrNull() in 1L..0xffffffffL &&
                            it.get("required")?.asBoolean == true
                    },
                ) {
                    "manifest.json 文件条目缺少 projectID/fileID 或 required"
                }
            }
            PackExportFormat.MultiMc -> {
                val pack = requireNotNull(packJson(zip, "mmc-pack.json"))
                require(pack.get("formatVersion")?.asInt == 1) { "mmc-pack.json 版本无效" }
                val components = pack.array("components").map { it.asJsonObject }
                require(
                    components.any {
                        it.string("uid") == "net.minecraft" &&
                            it.string("version") == plan.components.gameVersion
                    },
                ) {
                    "mmc-pack.json 缺少匹配的 net.minecraft 组件"
                }
                multimcLoaderUid(plan.components.loader)?.let { uid ->
                    require(
                        components.any {
                            it.string("uid") == uid &&
                                it.string("version") == plan.components.loaderVersion
                        },
                    ) {
                        "mmc-pack.json 缺少匹配的加载器组件"
                    }
                }
                val config =
                    zip
                        .getInputStream(requireNotNull(zip.getEntry("instance.cfg")))
                        .use { it.readBytes().toString(Charsets.UTF_8) }
                require(
                    "[General]" in config &&
                        "InstanceType=OneSix" in config &&
                        config.lineSequence().any {
                            it.trim().startsWith("name=") &&
                                it.trim().removePrefix("name=").isNotBlank()
                        },
                ) {
                    "instance.cfg 缺少实例名或 OneSix 类型"
                }
            }
            PackExportFormat.Mcbbs -> {
                val info = requireNotNull(packJson(zip, "mcbbs.packmeta"))
                require(info.string("manifestType") == "minecraftModpack") {
                    "mcbbs.packmeta 不是整合包清单"
                }
                require(
                    info.array("addons").any {
                        it.asJsonObject.string("id") == "game" &&
                            it.asJsonObject.string("version") == plan.components.gameVersion
                    },
                ) {
                    "mcbbs.packmeta 缺少游戏组件"
                }
                require(info.array("files").size() == layout.payload.size) {
                    "mcbbs.packmeta 文件数与包内容不符"
                }
            }
            PackExportFormat.Backup -> {
                require(
                    zip.getEntry("modrinth.index.json") == null &&
                        zip.getEntry("manifest.json") == null &&
                        zip.getEntry("mmc-pack.json") == null &&
                        zip.getEntry("mcbbs.packmeta") == null,
                ) {
                    "完整备份不应包含其他启动器的整合包清单"
                }
                val info = requireNotNull(packJson(zip, "molifox-backup.json"))
                require(
                    info.string(
                        "type",
                    ) == "molifox-backup" && info.get("formatVersion")?.asInt == 1,
                ) {
                    "备份清单无效"
                }
                require(info.array("files").size() == layout.payload.size) {
                    "备份清单文件数与包内容不符"
                }
            }
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
