// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责整合包格式识别与清单数据。

package dev.molifox.core

import com.google.gson.JsonObject
import java.nio.file.Path
import java.util.zip.ZipFile

private const val MAX_MODRINTH_PACK_FILES = 20_000

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
    /** 只有声明了实例级默认值（MultiMC/Prism 的 instance.cfg）的格式才会填充。 */
    val instanceDefaults: PackInstanceDefaults? = null,
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
): PackManifest = PackManifestReader(zip, root, os).read()

private data class DetectedPackFormats(
    val legacyRoot: String,
    val mcbbsPath: String?,
    val legacyPackPath: String?,
    val generic: JsonObject?,
    val mcbbs: JsonObject?,
    val curseForge: JsonObject?,
    val multiMc: String?,
)

private data class ModrinthPackDependencies(
    val dependencies: JsonObject,
    val loaders: List<Map.Entry<String, LoaderType>>,
)

private class PackManifestReader(
    private val zip: ZipFile,
    private val root: Path,
    private val os: String,
) {
    fun read(): PackManifest {
        val index = packJson(zip, "modrinth.index.json")
        val exported = packJson(zip, "molifox-instance.json")
        val formats = detectFormats(index, exported)
        val declared = readDeclared(formats)
        if (declared != null) return declared
        require(index != null || exported != null) {
            "请选择 Modrinth、CurseForge、MultiMC/Prism、HMCL/MCBBS 或 MoliFox 整合包"
        }
        validateOverrides(index)
        return if (index == null) readExported(exported) else readModrinth(index)
    }

    private fun detectFormats(
        index: JsonObject?,
        exported: JsonObject?,
    ): DetectedPackFormats {
        val legacy =
            findArchiveMetadata(
                zip,
                setOf("mcbbs.packmeta", "modpack.json", "manifest.json"),
            )
        require(
            legacy.map { it.substringBeforeLast('/', "") }.distinct().size <= 1,
        ) {
            "整合包包含多个实例的清单"
        }
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
        val declaredFormats =
            listOfNotNull(index, exported, curseForge, multiMc, mcbbs, legacyPackPath)
        require(declaredFormats.size <= 1) {
            "整合包含有冲突的格式清单"
        }
        return DetectedPackFormats(
            legacyRoot,
            mcbbsPath,
            legacyPackPath,
            generic,
            mcbbs,
            curseForge,
            multiMc,
        )
    }

    private fun readDeclared(formats: DetectedPackFormats): PackManifest? =
        when {
            formats.mcbbs != null ->
                readMcbbsManifest(
                    formats.mcbbs,
                    zip,
                    formats.legacyRoot,
                    root,
                    os,
                    if (formats.mcbbsPath == null) null else formats.generic,
                )
            formats.legacyPackPath != null ->
                readLegacyPackManifest(
                    requireNotNull(packJson(zip, formats.legacyPackPath)),
                    zip,
                    formats.legacyRoot,
                    root,
                    os,
                )
            formats.multiMc != null -> readMultiMcManifest(zip, formats.multiMc, root, os)
            formats.curseForge != null -> {
                val curseForge = formats.curseForge
                readCurseForgeManifest(
                    curseForge.deepCopy().apply {
                        if (formats.legacyRoot.isNotEmpty()) {
                            val overrides = curseForge.string("overrides")
                            addProperty("overrides", formats.legacyRoot + overrides)
                        }
                    },
                    zip,
                    root,
                    os,
                )
            }
            else -> null
        }

    private fun validateOverrides(index: JsonObject?) {
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
    }

    private fun readExported(exported: JsonObject?): PackManifest {
        requireNotNull(exported) { "MoliFox 整合包清单缺失" }
        require(exported.int("formatVersion") == 1) { "MoliFox 整合包格式无效" }
        return PackManifest(
            null,
            gson.fromJson(exported.obj("instance"), InstallRequest::class.java),
            emptyList(),
            validateArchive(zip, "files/", root).size,
        )
    }

    private fun readModrinth(index: JsonObject): PackManifest {
        validateModrinthFormat(index)
        val (dependencies, loaders) = modrinthDependencies(index)
        val files = validatedModrinthFiles(index)
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

    private fun validateModrinthFormat(index: JsonObject) {
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
    }

    private fun modrinthDependencies(index: JsonObject): ModrinthPackDependencies {
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
        return ModrinthPackDependencies(dependencies, loaders)
    }

    private fun validatedModrinthFiles(index: JsonObject): List<JsonObject> {
        val files = index.array("files").map { it.asJsonObject }
        require(files.size <= MAX_MODRINTH_PACK_FILES) { "整合包文件过多" }
        val paths = hashSetOf<String>()
        for (file in files) {
            val path = packPath(root, file.string("path"))
            val relative =
                root
                    .toAbsolutePath()
                    .normalize()
                    .relativize(path)
                    .joinToString("/")
            val key = if (os == "windows") relative.lowercase() else relative
            require(paths.add(key)) { "整合包包含重复文件：${file.string("path")}" }
            require(file.array("downloads").size() > 0) {
                "整合包缺少下载地址：${file.string("path")}"
            }
            file.array("downloads").forEach { packDownload(file, path, it.asString) }
        }
        for (path in paths) {
            var parent = path.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                require(parent !in paths) { "整合包文件和目录冲突：$parent" }
                parent = parent.substringBeforeLast('/', "")
            }
        }
        return files
    }
}
