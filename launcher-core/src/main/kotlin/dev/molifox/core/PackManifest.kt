// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责整合包格式识别与清单数据。

package dev.molifox.core

import com.google.gson.JsonObject
import java.nio.file.Path
import java.util.zip.ZipFile

internal data class PackManifest(
    val index: JsonObject?,
    val request: InstallRequest,
    val optionalFiles: List<String>,
    val requiredFiles: Int,
    val version: String = "",
    val summary: String = "",
    val curseForge: JsonObject? = null,
    val payloadPrefix: String = "",
    val excludedRoots: Set<String> = emptySet(),
    val checkedFiles: List<PackCheckedFile> = emptyList(),
    val launchOptions: PackLaunchOptions? = null,
) {
    val overridePrefixes: List<String>
        get() =
            when {
                payloadPrefix.isNotBlank() -> listOf(payloadPrefix)
                index != null -> listOf("overrides/", "client-overrides/")
                curseForge != null -> listOf("${curseForge.string("overrides")}/")
                else -> listOf("files/")
            }
}

/** Shared by preview and installation: malformed packs never create an instance. */
internal fun readPackManifest(
    zip: ZipFile,
    root: Path,
    os: String,
): PackManifest {
    val index = packJson(zip, "modrinth.index.json")
    val exported = packJson(zip, "molifox-instance.json")
    val legacy = findArchiveMetadata(zip, setOf("mcbbs.packmeta", "modpack.json", "manifest.json"))
    require(legacy.map { it.substringBeforeLast('/', "") }.distinct().size <= 1) { "整合包包含多个实例的清单" }
    val legacyRoot =
        legacy.firstOrNull()?.substringBeforeLast('/', "").orEmpty().let {
            if (it.isEmpty()) "" else "$it/"
        }
    if (legacy.isNotEmpty()) safePath(root, legacyRoot + "manifest-validation")
    val mcbbsPath = legacy.find { it.endsWith("mcbbs.packmeta") }
    val legacyPackPath = legacy.find { it.endsWith("modpack.json") }
    val manifestPath = legacy.find { it.endsWith("manifest.json") }
    val generic = manifestPath?.let { packJson(zip, it) }
    val mcbbs = mcbbsPath?.let { packJson(zip, it) } ?: generic?.takeIf { it.has("addons") }
    val curseForge = generic?.takeIf { mcbbs == null }
    val multiMc = findMultiMcManifest(zip)
    require(listOfNotNull(index, exported, curseForge, multiMc, mcbbs, legacyPackPath).size <= 1) {
        "整合包含有冲突的格式清单"
    }
    if (mcbbs != null) {
        return readMcbbsManifest(
            mcbbs,
            zip,
            legacyRoot,
            root,
            os,
            if (mcbbsPath == null) null else generic,
        )
    }
    if (legacyPackPath != null) {
        return readLegacyPackManifest(
            requireNotNull(packJson(zip, legacyPackPath)),
            zip,
            legacyRoot,
            root,
            os,
        )
    }
    if (multiMc != null) return readMultiMcManifest(zip, multiMc, root, os)
    if (curseForge != null) {
        return readCurseForgeManifest(
            curseForge.deepCopy().apply {
                if (legacyRoot.isNotEmpty()) {
                    addProperty("overrides", legacyRoot + curseForge.string("overrides"))
                }
            },
            zip,
            root,
            os,
        )
    }
    require(index != null || exported != null) {
        "请选择 Modrinth、CurseForge、MultiMC/Prism、HMCL/MCBBS 或 MoliFox 整合包"
    }
    val prefixes =
        if (index != null) listOf("overrides/", "client-overrides/") else listOf("files/")
    for (prefix in prefixes) {
        for ((_, target) in validateArchive(zip, prefix, root)) {
            packPath(
                root,
                root
                    .toAbsolutePath()
                    .normalize()
                    .relativize(target)
                    .joinToString("/"),
            )
        }
    }
    if (index == null) {
        requireNotNull(exported) { "MoliFox 整合包清单缺失" }
        require(exported.int("formatVersion") == 1) { "MoliFox 整合包格式无效" }
        return PackManifest(
            null,
            gson.fromJson(exported.obj("instance"), InstallRequest::class.java),
            emptyList(),
            validateArchive(zip, "files/", root).size,
        )
    }
    require(
        index["formatVersion"]?.let {
            it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asString == "1"
        } == true &&
            index.string("game") == "minecraft" &&
            index["versionId"]?.let {
                it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.isNotBlank()
            } == true &&
            index.has("files") &&
            index["files"].isJsonArray,
    ) {
        "整合包清单格式无效或缺少版本标识"
    }
    val dependencies = index.obj("dependencies")
    require(
        dependencies.entrySet().all {
            it.value.isJsonPrimitive && it.value.asJsonPrimitive.isString
        },
    ) {
        "整合包依赖版本必须是文本"
    }
    val supported =
        linkedMapOf(
            "fabric-loader" to LoaderType.Fabric,
            "quilt-loader" to LoaderType.Quilt,
            "forge" to LoaderType.Forge,
            "neoforge" to LoaderType.NeoForge,
        )
    require(dependencies.entrySet().all { it.key == "minecraft" || it.key in supported }) {
        "整合包使用了不支持的依赖"
    }
    val loaders = supported.entries.filter { dependencies.has(it.key) }
    require(loaders.size <= 1) { "整合包包含冲突的加载器" }
    val files = index.array("files").map { it.asJsonObject }
    require(files.size <= 20_000) { "整合包文件过多" }
    val paths = hashSetOf<String>()
    for (file in files) {
        val path = packPath(root, file.string("path"))
        val key =
            root.toAbsolutePath().normalize().relativize(path).joinToString("/").let {
                if (os == "windows") it.lowercase() else it
            }
        require(paths.add(key)) { "整合包包含重复文件：${file.string("path")}" }
        require(file.array("downloads").size() > 0) { "整合包缺少下载地址：${file.string("path")}" }
        file.array("downloads").forEach { packDownload(file, path, it.asString) }
    }
    for (path in paths) {
        var parent = path.substringBeforeLast('/', "")
        while (parent.isNotEmpty()) {
            require(parent !in paths) { "整合包文件和目录冲突：$parent" }
            parent = parent.substringBeforeLast('/', "")
        }
    }
    val optional =
        files
            .filter { it.obj("env").string("client", "required") == "optional" }
            .map { it.string("path") }
    return PackManifest(
        index,
        InstallRequest(
            index.string("name"),
            dependencies.string("minecraft"),
            loaders.firstOrNull()?.value ?: LoaderType.Vanilla,
            loaders.firstOrNull()?.let { dependencies.string(it.key) }.orEmpty(),
        ),
        optional,
        files.count { it.obj("env").string("client", "required") == "required" },
        index.string("versionId"),
        index.string("summary"),
    )
}
