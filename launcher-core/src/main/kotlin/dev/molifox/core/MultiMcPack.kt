// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责MultiMC 整合包的识别和导入。

package dev.molifox.core

import com.google.gson.JsonParser
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/** A root instance or one enclosing folder; never guess among several exported instances. */
internal fun findMultiMcManifest(zip: ZipFile): String? {
    val candidates = findArchiveMetadata(zip, setOf("mmc-pack.json"))
    require(candidates.size <= 1) { "压缩包包含多个 MultiMC/Prism 实例，请分别导入" }
    return candidates.singleOrNull()
}

internal fun readMultiMcManifest(
    zip: ZipFile,
    manifestPath: String,
    root: Path,
    os: String,
): PackManifest {
    fun text(
        name: String,
        limit: Int,
    ): String? {
        val entry = zip.getEntry(name) ?: return null
        require(entry.size in 1..limit.toLong()) { "MultiMC/Prism 元数据过大或为空" }
        val bytes = zip.getInputStream(entry).use { it.readNBytes(limit + 1) }
        require(bytes.size <= limit) { "MultiMC/Prism 元数据过大" }
        return bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
    }
    safePath(root, manifestPath)
    val prefix = manifestPath.removeSuffix("mmc-pack.json")
    val info = JsonParser.parseString(requireNotNull(text(manifestPath, 1024 * 1024))).asJsonObject
    require(info.string("formatVersion") == "1" && info.get("components")?.isJsonArray == true) {
        "不支持的 MultiMC/Prism 组件清单版本"
    }
    val all = info.array("components").map { it.asJsonObject }
    require(all.size in 1..32 && all.map { it.string("uid") }.distinct().size == all.size) {
        "整合包组件为空、重复或过多"
    }
    val loaders =
        mapOf(
            "net.fabricmc.fabric-loader" to LoaderType.Fabric,
            "org.quiltmc.quilt-loader" to LoaderType.Quilt,
            "net.minecraftforge" to LoaderType.Forge,
            "net.neoforged" to LoaderType.NeoForge,
        )
    val supported = loaders.keys + setOf("net.minecraft", "org.lwjgl", "org.lwjgl3")
    require(all.all { it.string("uid") in supported }) {
        "不支持的整合包组件：${all.filter {
            it.string(
                "uid",
            ) !in supported
        }.joinToString { it.string("uid") }}"
    }
    require(all.none { it.boolean("custom") }) { "暂不支持 MultiMC/Prism 自定义组件" }
    require(
        zip.entries().asSequence().none {
            !it.isDirectory &&
                (it.name.startsWith("${prefix}patches/") || it.name.startsWith("${prefix}jarmods/"))
        },
    ) {
        "暂不支持包含自定义组件补丁或 JAR 修改的整合包"
    }
    val enabled = all.filter { !it.boolean("disabled") }
    val minecraft =
        enabled.singleOrNull { it.string("uid") == "net.minecraft" }
            ?: error("整合包缺少启用的 Minecraft 组件")
    val gameVersion =
        minecraft.string("version").also {
            require(it.isNotBlank()) { "Minecraft 组件必须指定版本" }
            safeFilename(it)
        }
    val selected = enabled.filter { it.string("uid") in loaders }
    require(selected.size <= 1) { "整合包包含多个启用的加载器" }
    for (component in enabled.filter { it.string("uid") in setOf("org.lwjgl", "org.lwjgl3") }) {
        require(component.boolean("dependencyOnly")) { "暂不支持单独覆盖 LWJGL 版本的整合包" }
    }
    val loader = selected.singleOrNull()
    val loaderVersion = loader?.string("version")?.removePrefix("$gameVersion-").orEmpty()
    require(loader == null || loaderVersion.isNotBlank()) { "加载器组件必须指定版本" }
    if (loaderVersion.isNotBlank()) safeFilename(loaderVersion)
    val folders =
        listOf("$prefix.minecraft/", "${prefix}minecraft/").filter { candidate ->
            zip.entries().asSequence().any { it.name.startsWith(candidate) }
        }
    require(folders.size == 1) { "整合包需要唯一的 .minecraft 或 minecraft 内容目录" }
    val folder = folders.single()
    val excluded =
        setOf("assets", "libraries", "versions", "natives", "logs", "crash-reports", ".cache")
    val paths =
        validateArchive(zip, folder, root).mapNotNull { (_, target) ->
            val relative =
                root
                    .toAbsolutePath()
                    .normalize()
                    .relativize(target)
                    .joinToString("/")
            packPath(root, relative)
            if (relative.substringBefore('/').lowercase() in excluded) {
                null
            } else if (os == "windows") {
                relative.lowercase()
            } else {
                relative
            }
        }
    val seen = paths.toSet()
    require(seen.size == paths.size) { "整合包内容目录包含重复文件" }
    for (path in paths) {
        var parent = path.substringBeforeLast('/', "")
        while (parent.isNotBlank()) {
            require(parent !in seen) { "整合包文件和目录冲突" }
            parent = parent.substringBeforeLast('/', "")
        }
    }
    val config = requireNotNull(text("${prefix}instance.cfg", 1024 * 1024)) { "整合包缺少 instance.cfg" }
    var section = "General"
    var name = ""
    for (line in config.lineSequence()) {
        val trimmed = line.trim()
        if (trimmed.startsWith('[') && trimmed.endsWith(']')) {
            section = trimmed.substring(1, trimmed.length - 1)
        }
        if (section == "General" && trimmed
                .substringBefore(
                    '=',
                ).trim() == "name" && '=' in trimmed
        ) {
            name = trimmed.substringAfter('=').trim().removeSurrounding("\"")
        }
    }
    name =
        name.replace(Regex("\\\\x([0-9a-fA-F]{1,4})")) {
            it.groupValues[1]
                .toInt(16)
                .toChar()
                .toString()
        }
    require(name.isNotBlank() && name.length <= 80 && name.none { it.isISOControl() }) {
        "MultiMC/Prism 实例名称为空或无效"
    }
    return PackManifest(
        null,
        InstallRequest(
            name,
            gameVersion,
            loader?.let { loaders.getValue(it.string("uid")) } ?: LoaderType.Vanilla,
            loaderVersion,
        ),
        emptyList(),
        paths.size,
        summary = "MultiMC/Prism 实例：保留游戏内容，游戏依赖按官方元数据重建；不迁移原启动器的命令、Java 路径或账户。",
        payloadPrefix = folder,
        excludedRoots = excluded,
    )
}
