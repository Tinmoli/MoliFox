// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责MCBBS 整合包格式的读取与导出。

package dev.molifox.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.molifox.core.i18n.I18n
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
        I18n.t("core.mcbbsPack.1")
    }
    require(info.get("addons")?.isJsonArray == true) { I18n.t("core.mcbbsPack.2") }
    val addons = info.array("addons").map { it.asJsonObject }
    require(
        addons.size in 1..MAX_MCBBS_ADDONS &&
            addons.map { it.string("id") }.distinct().size == addons.size,
    ) {
        I18n.t("core.mcbbsPack.3")
    }
    val unknown = addons.filter { it.string("id") !in mcbbsLoaders && it.string("id") != "game" }
    require(unknown.isEmpty()) {
        I18n.t("core.mcbbsPack.4", unknown.joinToString { it.string("id") })
    }
    val game = addons.singleOrNull { it.string("id") == "game" }?.string("version").orEmpty()
    require(game.isNotBlank()) { I18n.t("core.mcbbsPack.5") }
    safeFilename(game)
    val loaders = addons.filter { it.string("id") in mcbbsLoaders }
    require(loaders.size <= 1) { I18n.t("core.mcbbsPack.6") }
    val loader = loaders.singleOrNull()
    val version = loader?.string("version")?.removePrefix("$game-").orEmpty()
    require(loader == null || version.isNotBlank()) { I18n.t("core.mcbbsPack.7") }
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
        require(info.array("libraries").isEmpty) { I18n.t("core.mcbbsPack.8") }
        val launch = info.obj("launchInfo")
        minimumMemory = launch.int("minMemory")
        require(minimumMemory >= 0) { I18n.t("core.mcbbsPack.9") }
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
            I18n.t("core.mcbbsPack.10")
        }
        require(info.obj("serverInfo").string("authlibInjectorServer").isBlank()) {
            I18n.t("core.mcbbsPack.11")
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
            I18n.t("core.mcbbsPack.12")
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
            else -> error(I18n.t("core.mcbbsPack.13", file.string("type")))
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
            I18n.t("core.mcbbsPack.14")
        }
        packPath(root, path)
        require(declared.add(key(path))) { I18n.t("core.mcbbsPack.15", path) }
        val hash = file.string("hash")
        require(hash.matches(Regex("[a-fA-F0-9]{40}"))) {
            I18n.t("packsB2.mcbbsPack.invalidSha1", path)
        }
        if (path.substringBefore('/').lowercase() in importedPackExclusions) return
        val entry = entries[key(path)]
        if (entry != null) {
            require(zipEntryHash(zip, entry.first).equals(hash, true)) {
                I18n.t("core.mcbbsPack.16", path)
            }
            checked += PackCheckedFile(entry.second, hash)
        } else {
            val api = info.string("fileApi").trimEnd('/')
            require(api.isNotBlank()) { I18n.t("core.mcbbsPack.17", path) }
            val uri = URI(api)
            require(
                uri.rawQuery == null && uri.rawFragment == null && uri.userInfo == null,
            ) {
                I18n.t("core.mcbbsPack.18")
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
        I18n.t("core.mcbbsPack.19", field)
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
            I18n.t("core.mcbbsPack.20")
        }
    } else {
        require(
            companion.string("manifestType") == "minecraftModpack" &&
                companion.obj("minecraft").string("version") == request.gameVersion,
        ) {
            I18n.t("core.mcbbsPack.21")
        }
        val otherLoaders =
            companion.obj("minecraft").array("modLoaders").map { it.asJsonObject.string("id") }
        val expected = "${request.loader.name.lowercase()}-${request.loaderVersion}"
        require(otherLoaders.isEmpty() || otherLoaders == listOf(expected)) {
            I18n.t("core.mcbbsPack.22")
        }
    }
}

private fun mcbbsDeclaredFiles(info: JsonObject): JsonArray {
    val declared = info.get("files")
    require(
        declared != null && declared.isJsonArray && declared.asJsonArray.size() <= MAX_MCBBS_FILES,
    ) {
        I18n.t("core.mcbbsPack.23")
    }
    return info.array("files")
}

private fun requireNoPathConflicts(paths: Set<String>) {
    for (path in paths) {
        var parent = path.substringBeforeLast('/', "")
        while (parent.isNotBlank()) {
            require(parent !in paths) { I18n.t("core.mcbbsPack.24") }
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
        I18n.t("core.mcbbsPack.25"),
        info.string("description").take(MAX_MCBBS_DESCRIPTION_CHARS),
        I18n.t("core.mcbbsPack.26"),
        if (minimumMemory > 0) I18n.t("core.mcbbsPack.27", minimumMemory) else "",
        if (options.jvm.isNotEmpty()) {
            I18n.t("core.mcbbsPack.28", packArgumentsText(options.jvm))
        } else {
            ""
        },
        if (options.game.isNotEmpty()) I18n.t("core.mcbbsPack.29", packArgumentsText(options.game)) else "",
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
                I18n.t("core.mcbbsPack.30")
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
            I18n.t("core.mcbbsPack.31")
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
                    I18n.t("core.mcbbsPack.32")
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
    require(version.isNotBlank()) { I18n.t("core.mcbbsPack.33") }
    safeFilename(version)
    LegacyPackChecks.requirePlainManifest(info)
    val payloadPrefix = "${prefix}minecraft/"
    val payload = validatePackPayload(zip, payloadPrefix, root, os)
    LegacyPackChecks.requirePlainPayload(payload)
    LegacyPackChecks.requireNoProfileJson(zip, payload)
    require(zip.entries().asSequence().any { it.name.startsWith(payloadPrefix) }) {
        I18n.t("core.mcbbsPack.34")
    }
    return PackManifest(
        null,
        InstallRequest(info.string("name"), version),
        emptyList(),
        payload.count { it.second.substringBefore('/').lowercase() !in importedPackExclusions },
        info.string("version"),
        I18n.t("core.mcbbsPack.35"),
        payloadPrefix = payloadPrefix,
        excludedRoots = importedPackExclusions,
    )
}
