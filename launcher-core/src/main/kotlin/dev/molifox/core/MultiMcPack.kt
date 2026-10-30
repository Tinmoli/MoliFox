// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责MultiMC 整合包的识别和导入。

package dev.molifox.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.isDirectory
import kotlin.io.path.name

private const val MAX_MULTIMC_COMPONENTS = 32
private const val MULTIMC_HEX_RADIX = 16
private const val MAX_MULTIMC_INSTANCE_NAME_CHARS = 80
private const val MAX_MULTIMC_METADATA_BYTES = 1024 * 1024

private val MULTIMC_LOADERS =
    mapOf(
        "net.fabricmc.fabric-loader" to LoaderType.Fabric,
        "org.quiltmc.quilt-loader" to LoaderType.Quilt,
        "net.minecraftforge" to LoaderType.Forge,
        "net.neoforged" to LoaderType.NeoForge,
    )

private val MULTIMC_SUPPORTED_UIDS =
    MULTIMC_LOADERS.keys + setOf("net.minecraft", "org.lwjgl", "org.lwjgl3")

private val MULTIMC_LWJGL_UIDS = setOf("org.lwjgl", "org.lwjgl3")

private val MULTIMC_EXCLUDED_ROOTS =
    setOf("assets", "libraries", "versions", "natives", "logs", "crash-reports", ".cache")

/** 组件清单解析后的启用的游戏与加载器版本。 */
private data class MultiMcGame(
    val version: String,
    val loader: LoaderType,
    val loaderVersion: String,
)

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
    safePath(root, manifestPath)
    val prefix = manifestPath.removeSuffix("mmc-pack.json")
    val all = readMultiMcComponents(zip, manifestPath)
    validateMultiMcComponents(zip, prefix, all)
    val enabled = all.filter { !it.boolean("disabled") }
    val game = readMultiMcGame(enabled)
    val folder = findMultiMcContentFolder(zip, prefix)
    val paths = collectMultiMcPaths(zip, folder, root, os)
    val name = readMultiMcInstanceName(zip, prefix)
    return PackManifest(
        null,
        InstallRequest(name, game.version, game.loader, game.loaderVersion),
        emptyList(),
        paths.size,
        summary = "MultiMC/Prism 实例：保留游戏内容，游戏依赖按官方元数据重建；不迁移原启动器的命令、Java 路径或账户。",
        payloadPrefix = folder,
        excludedRoots = MULTIMC_EXCLUDED_ROOTS,
    )
}

/** 读取 mmc-pack.json 组件，校验版本标记与组件唯一性。 */
private fun readMultiMcComponents(
    zip: ZipFile,
    manifestPath: String,
): List<JsonObject> {
    val raw = requireNotNull(multiMcText(zip, manifestPath))
    val info = JsonParser.parseString(raw).asJsonObject
    require(info.string("formatVersion") == "1" && info.get("components")?.isJsonArray == true) {
        "不支持的 MultiMC/Prism 组件清单版本"
    }
    val all = info.array("components").map { it.asJsonObject }
    require(
        all.size in 1..MAX_MULTIMC_COMPONENTS &&
            all.map { it.string("uid") }.distinct().size == all.size,
    ) {
        "整合包组件为空、重复或过多"
    }
    return all
}

/** 拒绝不支持的组件、自定义组件以及与自定义补丁并存的整合包。 */
private fun validateMultiMcComponents(
    zip: ZipFile,
    prefix: String,
    all: List<JsonObject>,
) {
    require(all.all { it.string("uid") in MULTIMC_SUPPORTED_UIDS }) {
        val unsupported = all.filter { it.string("uid") !in MULTIMC_SUPPORTED_UIDS }
        "不支持的整合包组件：${unsupported.joinToString { it.string("uid") }}"
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
}

/** 解析启用的 Minecraft 与唯一加载器版本。 */
private fun readMultiMcGame(enabled: List<JsonObject>): MultiMcGame {
    val minecraft =
        enabled.singleOrNull { it.string("uid") == "net.minecraft" }
            ?: error("整合包缺少启用的 Minecraft 组件")
    val gameVersion =
        minecraft.string("version").also {
            require(it.isNotBlank()) { "Minecraft 组件必须指定版本" }
            safeFilename(it)
        }
    val selected = enabled.filter { it.string("uid") in MULTIMC_LOADERS }
    require(selected.size <= 1) { "整合包包含多个启用的加载器" }
    for (component in enabled.filter { it.string("uid") in MULTIMC_LWJGL_UIDS }) {
        require(component.boolean("dependencyOnly")) { "暂不支持单独覆盖 LWJGL 版本的整合包" }
    }
    val loader = selected.singleOrNull()
    val loaderVersion = loader?.string("version")?.removePrefix("$gameVersion-").orEmpty()
    require(loader == null || loaderVersion.isNotBlank()) { "加载器组件必须指定版本" }
    if (loaderVersion.isNotBlank()) safeFilename(loaderVersion)
    return MultiMcGame(
        gameVersion,
        loader?.let { MULTIMC_LOADERS.getValue(it.string("uid")) } ?: LoaderType.Vanilla,
        loaderVersion,
    )
}

/** 内容目录必须唯一，避免猜测哪一份才是实例根目录。 */
private fun findMultiMcContentFolder(
    zip: ZipFile,
    prefix: String,
): String {
    val folders =
        listOf("$prefix.minecraft/", "${prefix}minecraft/").filter { candidate ->
            zip.entries().asSequence().any { it.name.startsWith(candidate) }
        }
    require(folders.size == 1) { "整合包需要唯一的 .minecraft 或 minecraft 内容目录" }
    return folders.single()
}

/** 校验内容文件路径，丢弃游戏依赖目录并拒绝重复或目录冲突。 */
private fun collectMultiMcPaths(
    zip: ZipFile,
    folder: String,
    root: Path,
    os: String,
): List<String> {
    val paths =
        validateArchive(zip, folder, root).mapNotNull { (_, target) ->
            val relative =
                root
                    .toAbsolutePath()
                    .normalize()
                    .relativize(target)
                    .joinToString("/")
            packPath(root, relative)
            if (relative.substringBefore('/').lowercase() in MULTIMC_EXCLUDED_ROOTS) {
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
    return paths
}

/** 从 instance.cfg 读取实例名并还原 MultiMC 的 \x 转义。 */
private fun readMultiMcInstanceName(
    zip: ZipFile,
    prefix: String,
): String {
    val config =
        requireNotNull(multiMcText(zip, "${prefix}instance.cfg")) { "整合包缺少 instance.cfg" }
    var section = "General"
    var name = ""
    for (line in config.lineSequence()) {
        val trimmed = line.trim()
        if (trimmed.startsWith('[') && trimmed.endsWith(']')) {
            section = trimmed.substring(1, trimmed.length - 1)
        }
        if (section == "General" &&
            trimmed.substringBefore('=').trim() == "name" &&
            '=' in trimmed
        ) {
            name = trimmed.substringAfter('=').trim().removeSurrounding("\"")
        }
    }
    name = decodeMultiMcName(name)
    require(
        name.isNotBlank() &&
            name.length <= MAX_MULTIMC_INSTANCE_NAME_CHARS &&
            name.none { it.isISOControl() },
    ) {
        "MultiMC/Prism 实例名称为空或无效"
    }
    return name
}

/** 还原 instance.cfg 中以 \xHHHH 形式保存的 Unicode 字符。 */
private fun decodeMultiMcName(name: String): String =
    name.replace(Regex("\\\\x([0-9a-fA-F]{1,4})")) {
        it.groupValues[1]
            .toInt(MULTIMC_HEX_RADIX)
            .toChar()
            .toString()
    }

/** 读取压缩包内的小体积文本元数据，拒绝缺失或超限条目。 */
private fun multiMcText(
    zip: ZipFile,
    name: String,
): String? {
    val entry = zip.getEntry(name) ?: return null
    require(entry.size in 1..MAX_MULTIMC_METADATA_BYTES.toLong()) { "MultiMC/Prism 元数据过大或为空" }
    val bytes = zip.getInputStream(entry).use { it.readNBytes(MAX_MULTIMC_METADATA_BYTES + 1) }
    require(bytes.size <= MAX_MULTIMC_METADATA_BYTES) { "MultiMC/Prism 元数据过大" }
    return bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
}
