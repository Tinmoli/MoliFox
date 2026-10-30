// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责MCBBS 整合包格式的读取与导出。

package dev.molifox.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.net.URI
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

private const val MAX_MCBBS_ADDONS = 16
private const val MAX_MCBBS_FILES = 20000
private const val MAX_MCBBS_DESCRIPTION_CHARS = 2000

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
    require(
        addons.size in 1..MAX_MCBBS_ADDONS &&
            addons.map { it.string("id") }.distinct().size == addons.size,
    ) {
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

/**
 * 解析 MCBBS 清单头（版本、组件、启动参数、设置），随后交由 [McbbsScan] 核对 `files` 与 overrides。
 */
private class McbbsManifestReader(
    source: JsonObject,
    private val zip: ZipFile,
    prefix: String,
    private val root: Path,
    private val os: String,
) {
    private val payloadPrefix = "${prefix}overrides/"

    private val info: JsonObject =
        source.deepCopy().apply {
            for (field in listOf("libraries", "launchInfo", "settings", "serverInfo")) {
                if (get(field)?.isJsonNull == true) {
                    remove(field)
                }
            }
        }

    private val request = mcbbsRequest(info)
    private val minimumMemory: Int
    private val options: PackLaunchOptions

    init {
        require(info.array("libraries").isEmpty) { "暂不支持 MCBBS 自定义 libraries，请导出不含自定义启动依赖的包" }
        val launch = info.obj("launchInfo")
        minimumMemory = launch.int("minMemory")
        require(minimumMemory >= 0) { "MCBBS 建议内存无效" }
        options =
            PackLaunchOptions(
                mcbbsArguments(launch, "javaArgument"),
                mcbbsArguments(launch, "launchArgument"),
            )
        options.resolve(LauncherSettings(), os)
        val settings = info.obj("settings")
        val installMods = settings.boolean("install_mods", true)
        val installResourcepack = settings.boolean("install_resourcepack", true)
        require(installMods && installResourcepack) {
            "暂不支持关闭模组/资源包安装的 MCBBS 设置"
        }
        require(info.obj("serverInfo").string("authlibInjectorServer").isBlank()) {
            "此包要求第三方认证，茉狐暂不支持该认证方式"
        }
    }

    fun read(companion: JsonObject?): PackManifest {
        if (companion != null) {
            requireMcbbsCompanion(companion, request)
        }
        val payload = validatePackPayload(zip, payloadPrefix, root, os)
        val scan = McbbsScan(info, zip, root, os, payload)
        for (element in mcbbsDeclaredFiles(info)) {
            scan.collect(element.asJsonObject)
        }
        val curseProjects = scan.curseFiles.map { it.string("projectID") }
        require(curseProjects.distinct().size == curseProjects.size) {
            "MCBBS 包引用同一项目的多个文件"
        }
        requireNoPathConflicts(scan.allPaths())
        return PackManifest(
            null,
            request,
            emptyList(),
            scan.fileCount(),
            info.string("version"),
            mcbbsManifestText(info, minimumMemory, options),
            curseForge = scan.curseForgeJson(),
            payloadPrefix = payloadPrefix,
            excludedRoots = importedPackExclusions,
            checkedFiles = scan.checked,
            launchOptions = options.takeIf { it.jvm.isNotEmpty() || it.game.isNotEmpty() },
        )
    }
}

/**
 * MCBBS `files` 清单扫描：内置条目核对 SHA-1，缺失条目走 fileApi，curse 条目转成 CurseForge 引用。
 */
private class McbbsScan(
    private val info: JsonObject,
    private val zip: ZipFile,
    private val root: Path,
    private val os: String,
    private val payload: List<Pair<ZipEntry, String>>,
) {
    private val entries = payload.associate { key(it.second) to it }
    private val declared = hashSetOf<String>()
    val checked = mutableListOf<PackCheckedFile>()
    val curseFiles = mutableListOf<JsonObject>()

    fun collect(file: JsonObject) {
        when (file.string("type")) {
            "addon" -> collectAddon(file)
            "curse" -> collectCurse(file)
            else -> error("未知的 MCBBS 文件类型：${file.string("type")}")
        }
    }

    /** 全部占用路径（内置文件 + 需要下载的文件），用于检查文件/目录冲突。 */
    fun allPaths(): Set<String> =
        (
            payload.map { key(it.second) } +
                checked.filter { it.url.isNotBlank() }.map { key(it.path) }
        ).toSet()

    fun fileCount(): Int =
        payload.count { it.second.substringBefore('/').lowercase() !in importedPackExclusions } +
            checked.count { it.url.isNotBlank() } +
            curseFiles.size

    fun curseForgeJson(): JsonObject? =
        if (curseFiles.isEmpty()) {
            null
        } else {
            JsonObject().apply { add("files", gson.toJsonTree(curseFiles)) }
        }

    private fun collectAddon(file: JsonObject) {
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
        if (path.substringBefore('/').lowercase() in importedPackExclusions) return
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

    private fun collectCurse(file: JsonObject) {
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

    private fun key(path: String) = if (os == "windows") path.lowercase() else path
}

/** 读取 MCBBS 清单（含可选附带清单）；校验顺序与归档格式保持一致。 */
internal fun readMcbbsManifest(
    source: JsonObject,
    zip: ZipFile,
    prefix: String,
    root: Path,
    os: String,
    companion: JsonObject?,
): PackManifest = McbbsManifestReader(source, zip, prefix, root, os).read(companion)

private fun mcbbsArguments(
    launch: JsonObject,
    field: String,
): List<String> {
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

private fun requireMcbbsCompanion(
    companion: JsonObject,
    request: InstallRequest,
) {
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

private fun mcbbsDeclaredFiles(info: JsonObject): JsonArray {
    val declared = info.get("files")
    require(
        declared != null && declared.isJsonArray && declared.asJsonArray.size() <= MAX_MCBBS_FILES,
    ) {
        "MCBBS 文件清单缺失或过多"
    }
    return info.array("files")
}

private fun requireNoPathConflicts(paths: Set<String>) {
    for (path in paths) {
        var parent = path.substringBeforeLast('/', "")
        while (parent.isNotBlank()) {
            require(parent !in paths) { "MCBBS 文件和目录冲突" }
            parent = parent.substringBeforeLast('/', "")
        }
    }
}

private fun mcbbsManifestText(
    info: JsonObject,
    minimumMemory: Int,
    options: PackLaunchOptions,
): String =
    listOf(
        "MCBBS / HMCL 导出包",
        info.string("description").take(MAX_MCBBS_DESCRIPTION_CHARS),
        "按当前归档导入，不执行服务器自动更新，不迁移原启动器设置或命令。",
        if (minimumMemory > 0) "作者建议至少 $minimumMemory MB 内存，请在实例设置中确认。" else "",
        if (options.jvm.isNotEmpty()) {
            "整合包 JVM 参数：${packArgumentsText(options.jvm)}"
        } else {
            ""
        },
        if (options.game.isNotEmpty()) "整合包游戏参数：${packArgumentsText(options.game)}" else "",
    ).filter { it.isNotBlank() }
        .joinToString("\n")

/** 旧 HMCL 归档没有标准组件清单，但也不允许夹带自定义启动配置。 */
private object LegacyPackChecks {
    fun requirePlainManifest(info: JsonObject) {
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
    }

    fun requirePlainPayload(payload: List<Pair<ZipEntry, String>>) {
        require(
            payload.none { (_, path) ->
                path.substringBefore('/').lowercase() == "versions" ||
                    path.substringAfterLast('/').lowercase() == "pack.json" ||
                    '/' !in path && path.endsWith(".jar", true)
            },
        ) {
            "旧 HMCL 包带有版本启动配置或自定义客户端，请重新导出为 MCBBS 或 mrpack"
        }
    }

    fun requireNoProfileJson(
        zip: ZipFile,
        payload: List<Pair<ZipEntry, String>>,
    ) {
        val jsonEntries =
            payload.filter { '/' !in it.second && it.second.endsWith(".json", true) }
        for ((entry, path) in jsonEntries) {
            val profile =
                if (path.lowercase() in importedPackExclusions) null else packJson(zip, entry.name)
            if (profile != null) {
                require(
                    listOf("mainClass", "libraries", "inheritsFrom", "patches")
                        .none { profile.has(it) },
                ) {
                    "旧 HMCL 包带有版本启动配置，请重新导出为 MCBBS 或 mrpack"
                }
            }
        }
    }
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
    LegacyPackChecks.requirePlainManifest(info)
    val payloadPrefix = "${prefix}minecraft/"
    val payload = validatePackPayload(zip, payloadPrefix, root, os)
    LegacyPackChecks.requirePlainPayload(payload)
    LegacyPackChecks.requireNoProfileJson(zip, payload)
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
