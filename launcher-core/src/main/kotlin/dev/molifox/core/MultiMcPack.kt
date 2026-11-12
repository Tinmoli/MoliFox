// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责 实例组件格式 整合包的识别与导入。

package dev.molifox.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.molifox.core.i18n.I18n
import java.nio.file.Path
import java.util.zip.ZipFile

private const val MAX_MULTIMC_COMPONENTS = 32

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
    require(candidates.size <= 1) { I18n.t("packsA1.packImport.multipleInstances") }
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
    validateMultiMcArchive(zip, prefix, all)
    val enabled = all.filter { !it.boolean("disabled") }
    val game = readMultiMcGame(enabled)
    val folder = findMultiMcContentFolder(zip, prefix)
    val paths = collectMultiMcPaths(zip, folder, root, os)
    val settings = readMultiMcInstanceConfig(zip, prefix, multiMcFallbackName(prefix, zip.name))
    return PackManifest(
        null,
        InstallRequest(settings.name, game.version, game.loader, game.loaderVersion),
        emptyList(),
        paths.size,
        summary = settings.summary,
        payloadPrefix = folder,
        excludedRoots = MULTIMC_EXCLUDED_ROOTS,
        launchOptions = settings.launchOptions,
        instanceDefaults = settings.instanceDefaults,
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
        I18n.t("packsA1.packImport.unsupportedFormatVersion")
    }
    val all = info.array("components").map { it.asJsonObject }
    require(
        all.size in 1..MAX_MULTIMC_COMPONENTS &&
            all.map { it.string("uid") }.distinct().size == all.size,
    ) {
        I18n.t("packsA1.packImport.componentsInvalid")
    }
    return all
}

/** 拒绝不支持的组件、自定义组件、实例自带 libraries 以及与自定义补丁并存的整合包。 */
private fun validateMultiMcArchive(
    zip: ZipFile,
    prefix: String,
    all: List<JsonObject>,
) {
    require(all.all { it.string("uid") in MULTIMC_SUPPORTED_UIDS }) {
        val unsupported = all.filter { it.string("uid") !in MULTIMC_SUPPORTED_UIDS }
        I18n.t("packsA1.packImport.unsupportedComponent", unsupported.joinToString { it.string("uid") })
    }
    require(all.none { it.boolean("custom") }) { I18n.t("packsA1.packImport.customComponentUnsupported") }
    require(
        zip.entries().asSequence().none {
            !it.isDirectory && unsupportedMultiMcEntry(it.name, prefix)
        },
    ) {
        I18n.t("packsA1.packImport.patchesUnsupported")
    }
}

/** 归档的补丁目录、jarmods 与实例自带 libraries 都不在支持范围内。 */
private fun unsupportedMultiMcEntry(
    name: String,
    prefix: String,
): Boolean =
    name.startsWith("${prefix}patches/") ||
        name.startsWith("${prefix}jarmods/") ||
        name.startsWith("${prefix}libraries/")

/** 解析启用的 Minecraft 与唯一加载器版本。 */
private fun readMultiMcGame(enabled: List<JsonObject>): MultiMcGame {
    val minecraft =
        enabled.singleOrNull { it.string("uid") == "net.minecraft" }
            ?: error(I18n.t("packsA1.packImport.minecraftComponentMissing"))
    val gameVersion =
        minecraft.string("version").also {
            require(it.isNotBlank()) { I18n.t("packsA1.packImport.minecraftVersionRequired") }
            safeFilename(it)
        }
    val selected = enabled.filter { it.string("uid") in MULTIMC_LOADERS }
    require(selected.size <= 1) { I18n.t("packsA1.packImport.multipleLoaders") }
    for (component in enabled.filter { it.string("uid") in MULTIMC_LWJGL_UIDS }) {
        require(component.boolean("dependencyOnly")) { I18n.t("packsA1.packImport.lwjglOverrideUnsupported") }
    }
    val loader = selected.singleOrNull()
    val loaderType = loader?.let { MULTIMC_LOADERS.getValue(it.string("uid")) }
    val loaderVersion =
        loader?.let { multiMcLoaderVersion(it.string("version"), gameVersion) }.orEmpty()
    require(loader == null || loaderVersion.isNotBlank()) {
        I18n.t("packsA1.packImport.loaderVersionRequired")
    }
    require(!(loaderType == LoaderType.Forge && loaderVersion.startsWith("0."))) {
        I18n.t("packsA1.packImport.cleanroomUnsupported")
    }
    if (loaderVersion.isNotBlank()) safeFilename(loaderVersion)
    return MultiMcGame(
        gameVersion,
        loaderType ?: LoaderType.Vanilla,
        loaderVersion,
    )
}

/** 组件清单的版本字段可能带 `<游戏版本>-` 或加载器前缀；其它写法不猜，交给后续校验拒绝。 */
private fun multiMcLoaderVersion(
    version: String,
    gameVersion: String,
): String =
    listOf("$gameVersion-", "forge-", "neoforge-", "fabric-loader-", "quilt-loader-")
        .fold(version.trim()) { value, prefix -> value.removePrefix(prefix) }

/** 内容目录必须唯一，避免猜测哪一份才是实例根目录。 */
private fun findMultiMcContentFolder(
    zip: ZipFile,
    prefix: String,
): String {
    val folders =
        listOf("$prefix.minecraft/", "${prefix}minecraft/").filter { candidate ->
            zip.entries().asSequence().any { it.name.startsWith(candidate) }
        }
    require(folders.size == 1) { I18n.t("packsA1.packImport.contentFolderMissing") }
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
    require(seen.size == paths.size) { I18n.t("packsA1.packImport.duplicateFiles") }
    for (path in paths) {
        var parent = path.substringBeforeLast('/', "")
        while (parent.isNotBlank()) {
            require(parent !in seen) { I18n.t("packsA1.packImport.fileDirectoryConflict") }
            parent = parent.substringBeforeLast('/', "")
        }
    }
    return paths
}
