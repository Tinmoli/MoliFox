// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责MCBBS 整合包格式的读取与导出。

package dev.molifox.core

import com.google.gson.JsonObject
import java.net.URI
import java.nio.file.Path
import java.util.zip.ZipFile

private val mcbbsLoaders =
    mapOf(
        "fabric" to LoaderType.Fabric,
        "quilt" to LoaderType.Quilt,
        "forge" to LoaderType.Forge,
        "neoforge" to LoaderType.NeoForge,
    )

private fun mcbbsRequest(info: JsonObject): InstallRequest {
    require(
        info.string("manifestType", "minecraftModpack") == "minecraftModpack" &&
            info.string("manifestVersion", "1") in setOf("1", "2"),
    ) {
        "不支持的 MCBBS 清单版本"
    }
    require(info.get("addons")?.isJsonArray == true) { "MCBBS 清单缺少 addons" }
    val addons = info.array("addons").map { it.asJsonObject }
    require(addons.size in 1..16 && addons.map { it.string("id") }.distinct().size == addons.size) {
        "MCBBS 组件为空、重复或过多"
    }
    val unknown = addons.filter { it.string("id") !in mcbbsLoaders && it.string("id") != "game" }
    require(unknown.isEmpty()) {
        "不支持的 MCBBS 组件：${unknown.joinToString { it.string("id") }}，请更换整合包格式或组件"
    }
    val game = addons.singleOrNull { it.string("id") == "game" }?.string("version").orEmpty()
    require(game.isNotBlank()) { "MCBBS 清单缺少明确的游戏版本" }
    safeFilename(game)
    val loaders = addons.filter { it.string("id") in mcbbsLoaders }
    require(loaders.size <= 1) { "暂不支持多个加载器组合的 MCBBS 包" }
    val loader = loaders.singleOrNull()
    val version = loader?.string("version")?.removePrefix("$game-").orEmpty()
    require(loader == null || version.isNotBlank()) { "MCBBS 加载器缺少版本" }
    if (version.isNotBlank()) safeFilename(version)
    return InstallRequest(
        info.string("name"),
        game,
        loader?.let { mcbbsLoaders.getValue(it.string("id")) } ?: LoaderType.Vanilla,
        version,
    )
}

internal fun readMcbbsManifest(
    source: JsonObject,
    zip: ZipFile,
    prefix: String,
    root: Path,
    os: String,
    companion: JsonObject?,
): PackManifest {
    val info =
        source.deepCopy().apply {
            for (field in listOf("libraries", "launchInfo", "settings", "serverInfo")) {
                if (
                    get(field)?.isJsonNull == true
                ) {
                    remove(field)
                }
            }
        }
    val request = mcbbsRequest(info)
    require(info.array("libraries").isEmpty) { "暂不支持 MCBBS 自定义 libraries，请导出不含自定义启动依赖的包" }
    val launch = info.obj("launchInfo")
    val minimumMemory = launch.int("minMemory")
    require(minimumMemory >= 0) { "MCBBS 建议内存无效" }

    fun arguments(field: String): List<String> {
        val value = launch.get(field) ?: return emptyList()
        if (value.isJsonNull) return emptyList()
        require(
            value.isJsonArray &&
                value.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isString },
        ) {
            "MCBBS $field 必须是文本参数数组"
        }
        return value.asJsonArray.map { it.asString }
    }
    val options = PackLaunchOptions(arguments("javaArgument"), arguments("launchArgument"))
    options.resolve(LauncherSettings(), os)
    val settings = info.obj("settings")
    require(
        settings.boolean("install_mods", true) && settings.boolean("install_resourcepack", true),
    ) {
        "暂不支持关闭模组/资源包安装的 MCBBS 设置"
    }
    require(info.obj("serverInfo").string("authlibInjectorServer").isBlank()) {
        "此包要求第三方认证，茉狐暂不支持该认证方式"
    }
    if (companion != null) {
        if (companion.has("addons")) {
            val other = mcbbsRequest(companion)
            require(
                other.gameVersion == request.gameVersion &&
                    other.loader == request.loader &&
                    other.loaderVersion == request.loaderVersion,
            ) {
                "两份 MCBBS 清单的游戏或加载器不一致"
            }
        } else {
            require(
                companion.string("manifestType") == "minecraftModpack" &&
                    companion.obj("minecraft").string("version") == request.gameVersion,
            ) {
                "MCBBS 与附带清单的游戏版本不一致"
            }
            val otherLoaders =
                companion.obj("minecraft").array("modLoaders").map { it.asJsonObject.string("id") }
            val expected = "${request.loader.name.lowercase()}-${request.loaderVersion}"
            require(otherLoaders.isEmpty() || otherLoaders == listOf(expected)) {
                "MCBBS 与附带清单的加载器不一致"
            }
        }
    }
    val payloadPrefix = "${prefix}overrides/"
    val payload = validatePackPayload(zip, payloadPrefix, root, os)

    fun key(path: String) = if (os == "windows") path.lowercase() else path
    val entries = payload.associate { key(it.second) to it }
    require(info.get("files")?.isJsonArray == true && info.array("files").size() <= 20000) {
        "MCBBS 文件清单缺失或过多"
    }
    val checked = mutableListOf<PackCheckedFile>()
    val curseFiles = mutableListOf<JsonObject>()
    val declared = hashSetOf<String>()
    for (element in info.array("files")) {
        val file = element.asJsonObject
        when (file.string("type")) {
            "addon" -> {
                val path = file.string("path")
                require(
                    '\\' !in path &&
                        path.split('/').none { it.isBlank() || it == "." || it == ".." },
                ) {
                    "MCBBS 文件路径无效"
                }
                packPath(root, path)
                require(declared.add(key(path))) { "MCBBS 文件清单包含重复路径：$path" }
                val hash = file.string("hash")
                require(hash.matches(Regex("[a-fA-F0-9]{40}"))) { "MCBBS 文件缺少有效 SHA-1：$path" }
                if (path.substringBefore('/').lowercase() in importedPackExclusions) continue
                val entry = entries[key(path)]
                if (entry != null) {
                    require(zipEntryHash(zip, entry.first).equals(hash, true)) {
                        "MCBBS 内置文件校验失败：$path"
                    }
                    checked += PackCheckedFile(entry.second, hash)
                } else {
                    val api = info.string("fileApi").trimEnd('/')
                    require(api.isNotBlank()) { "整合包缺少文件 $path，且未提供 fileApi；请取得完整 ZIP 后重试" }
                    val uri = URI(api)
                    require(
                        uri.rawQuery == null && uri.rawFragment == null && uri.userInfo == null,
                    ) {
                        "MCBBS fileApi 不能含查询参数、片段或凭据"
                    }
                    val url =
                        validatePackUrl(
                            "$api/overrides/${path.split('/').joinToString("/") { encoded(it) }}",
                        )
                    checked += PackCheckedFile(path, hash, url)
                }
            }
            "curse" -> {
                curseForgeVersionId(
                    "curseforge:${file.string("projectID")}:${file.string("fileID")}",
                )
                curseFiles +=
                    JsonObject().apply {
                        addProperty("projectID", file.string("projectID").toLong())
                        addProperty("fileID", file.string("fileID").toLong())
                        addProperty("required", true)
                    }
            }
            else -> error("未知的 MCBBS 文件类型：${file.string("type")}")
        }
    }
    require(curseFiles.map { it.string("projectID") }.distinct().size == curseFiles.size) {
        "MCBBS 包引用同一项目的多个文件"
    }
    val allPaths =
        (
            payload.map { key(it.second) } +
                checked.filter { it.url.isNotBlank() }.map { key(it.path) }
        ).toSet()
    for (path in allPaths) {
        var parent = path.substringBeforeLast('/', "")
        while (parent.isNotBlank()) {
            require(parent !in allPaths) { "MCBBS 文件和目录冲突" }
            parent = parent.substringBeforeLast('/', "")
        }
    }
    val curse =
        if (curseFiles.isEmpty()) {
            null
        } else {
            JsonObject().apply { add("files", gson.toJsonTree(curseFiles)) }
        }
    val count =
        payload.count { it.second.substringBefore('/').lowercase() !in importedPackExclusions } +
            checked.count { it.url.isNotBlank() } +
            curseFiles.size
    return PackManifest(
        null,
        request,
        emptyList(),
        count,
        info.string("version"),
        listOf(
            "MCBBS / HMCL 导出包",
            info.string("description").take(2000),
            "按当前归档导入，不执行服务器自动更新，不迁移原启动器设置或命令。",
            if (minimumMemory > 0) "作者建议至少 $minimumMemory MB 内存，请在实例设置中确认。" else "",
            if (options.jvm.isNotEmpty()) {
                "整合包 JVM 参数：${packArgumentsText(options.jvm)}"
            } else {
                ""
            },
            if (options.game.isNotEmpty()) "整合包游戏参数：${packArgumentsText(options.game)}" else "",
        ).filter { it.isNotBlank() }
            .joinToString("\n"),
        curseForge = curse,
        payloadPrefix = payloadPrefix,
        excludedRoots = importedPackExclusions,
        checkedFiles = checked,
        launchOptions = options.takeIf { it.jvm.isNotEmpty() || it.game.isNotEmpty() },
    )
}

/**
 * Legacy plain archives have no standard component manifest; never discard a custom launch profile.
 */
internal fun readLegacyPackManifest(
    info: JsonObject,
    zip: ZipFile,
    prefix: String,
    root: Path,
    os: String,
): PackManifest {
    val version = info.string("gameVersion")
    require(version.isNotBlank()) { "旧 HMCL 包缺少 gameVersion" }
    safeFilename(version)
    for (
    field in
    listOf(
        "addons",
        "libraries",
        "patches",
        "launchInfo",
        "arguments",
        "minecraftArguments",
        "mainClass",
        "inheritsFrom",
        "loader",
    )
    ) {
        val value = info.get(field)
        require(
            value == null ||
                value.isJsonNull ||
                value.isJsonArray && value.asJsonArray.isEmpty ||
                value.isJsonObject && value.asJsonObject.size() == 0,
        ) {
            "旧 HMCL 包包含自定义启动配置，请由 HMCL 重新导出为 MCBBS 或 mrpack"
        }
    }
    val payloadPrefix = "${prefix}minecraft/"
    val payload = validatePackPayload(zip, payloadPrefix, root, os)
    require(
        payload.none { (_, path) ->
            path.substringBefore('/').lowercase() == "versions" ||
                path.substringAfterLast('/').lowercase() == "pack.json" ||
                '/' !in path && path.endsWith(".jar", true)
        },
    ) {
        "旧 HMCL 包带有版本启动配置或自定义客户端，请重新导出为 MCBBS 或 mrpack"
    }
    for (
    (entry, path) in
    payload.filter { '/' !in it.second && it.second.endsWith(".json", true) }
    ) {
        if (path.lowercase() in importedPackExclusions) continue
        val profile = packJson(zip, entry.name) ?: continue
        require(
            listOf("mainClass", "libraries", "inheritsFrom", "patches").none { profile.has(it) },
        ) {
            "旧 HMCL 包带有版本启动配置，请重新导出为 MCBBS 或 mrpack"
        }
    }
    require(zip.entries().asSequence().any { it.name.startsWith(payloadPrefix) }) {
        "旧 HMCL 包缺少 minecraft 内容目录"
    }
    return PackManifest(
        null,
        InstallRequest(info.string("name"), version),
        emptyList(),
        payload.count { it.second.substringBefore('/').lowercase() !in importedPackExclusions },
        info.string("version"),
        "旧 HMCL 原版归档；导入游戏内容并重建官方依赖，不迁移启动器命令或账户。",
        payloadPrefix = payloadPrefix,
        excludedRoots = importedPackExclusions,
    )
}
