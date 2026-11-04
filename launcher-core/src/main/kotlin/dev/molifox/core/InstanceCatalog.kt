// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责以 versions/ 目录为唯一事实来源扫描实例（不再保存实例列表）。

package dev.molifox.core

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import dev.molifox.core.i18n.I18n
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.reader

private const val MAX_JSON_BYTES = 8L * 1024 * 1024

/** 客户端 JAR 内嵌的版本描述文件；vanilla jar 一直带有，id 字段即真实游戏版本。 */
private const val JAR_VERSION_ENTRY = "version.json"

/** 一个已登记的游戏根及其界面目录标识；null 表示默认游戏根（`settings.gameRoot`）。 */
internal data class InstanceRoot(
    val path: Path,
    val directoryId: String?,
)

/**
 * `versions` 目录扫描器。目录名就是实例身份，`<name>/<name>.json`
 * 是版本配置；配置缺失、JSON 损坏或 `inheritsFrom` 断链时仍生成一个「无法识别」实例，
 * 由界面显示但禁止启动，而不是静默隐藏。
 */
internal object InstanceCatalog {
    /**
     * 版本目录内「游戏数据写在自己目录里」的迹象，即隔离版本的目录布局：
     * 这些条目只会在该版本以独立工作目录运行时出现。只读判断，绝不写外部启动器的目录。
     */
    private val GAME_DATA_MARKERS =
        listOf(
            "saves",
            "mods",
            "config",
            "logs",
            "options.txt",
            "resourcepacks",
            "crash-reports",
        )

    /**
     * 返回版本目录里命中的第一个数据迹象（用于推断隔离与日志），没有命中时返回 null。
     * 外部启动器的配置读不到，所以只依据目录内容判断。
     */
    fun gameDataMarker(folder: Path): String? =
        GAME_DATA_MARKERS.firstOrNull { marker ->
            runCatching { folder.resolve(marker).exists() }.getOrDefault(false)
        }

    /** 当前配置对应的扫描根：默认游戏根 + 已登记的外部目录，去重并保持稳定顺序。 */
    fun roots(data: LauncherData): List<InstanceRoot> {
        val result = mutableListOf<InstanceRoot>()
        val seen = mutableSetOf<String>()

        fun add(
            path: Path,
            directoryId: String?,
        ) {
            val key =
                runCatching { path.toRealPath().toString() }.getOrElse {
                    path.toAbsolutePath().normalize().toString()
                }
            if (seen.add(key)) result += InstanceRoot(path, directoryId)
        }
        add(Path.of(data.settings.gameRoot), null)
        for (directory in data.gameDirectories) {
            if (directory.path.isBlank()) continue
            runCatching { Path.of(directory.path) }.getOrNull()?.let { add(it, directory.id) }
        }
        return result
    }

    /** 目录新增/删除/版本 JSON 变化都会改变这个签名；界面重新可见时只用它决定是否重扫。 */
    fun signature(roots: List<InstanceRoot>): String {
        val text = StringBuilder()
        for (root in roots) {
            text
                .append(root.directoryId)
                .append('|')
                .append(root.path)
                .append('\n')
            val versions = root.path.resolve("versions")
            if (!versions.isDirectory()) {
                text.append("<missing>\n")
                continue
            }
            val children =
                runCatching { versions.listDirectoryEntries().sortedBy { it.name } }
                    .getOrElse { return "unreadable:${root.path}" }
            for (child in children) {
                appendVersionSignature(text, child)
            }
        }
        return text.toString()
    }

    /** 扫描全部根。返回的列表已按目录名排序，损坏项包含在列表里。 */
    fun scan(data: LauncherData): List<GameInstance> =
        roots(data).flatMap { root -> scan(root) }.sortedBy { it.name.lowercase() }

    fun scan(root: InstanceRoot): List<GameInstance> {
        val versions = root.path.resolve("versions")
        if (!versions.isDirectory()) return emptyList()
        val children =
            runCatching { versions.listDirectoryEntries().sortedBy { it.name } }.getOrNull()
                ?: return emptyList()
        return children.mapNotNull { folder ->
            if (!folder.isDirectory()) return@mapNotNull null
            // 目录名就是实例 id；不跟随 ../ 或绝对路径。
            val name = folder.name
            if (name.isBlank() || name == "." || name == "..") return@mapNotNull null
            // 目录里没有任何版本 JSON 就不是版本目录：可能是安装器先建的空目录，
            // 或升级后残留的旧版 jar（旧版没有 JSON 本来就无法启动），按约定不识别。
            if (versionJsonFile(folder, name) == null) return@mapNotNull null
            runCatching { readInstance(root, name) }
                .getOrElse { error ->
                    brokenInstance(
                        root,
                        name,
                        error.message ?: I18n.t("instA1.catalog.unrecognized"),
                        folder.resolve("$name.json"),
                    )
                }
        }
    }

    /** 稳定 id：同一游戏目录下同一文件夹始终得到同一 UUID（运行目录等仍要求 UUID）。 */
    fun instanceId(
        directoryId: String?,
        folder: String,
    ): String =
        UUID
            .nameUUIDFromBytes("MoliFoxInstance:${directoryId.orEmpty()}:$folder".toByteArray())
            .toString()

    /** 读取版本 JSON 的 `inheritsFrom`，用于重命名后同步兄弟配置；读不到时返回空。 */
    fun inheritsFrom(file: Path): String = runCatching { readObject(file).string("inheritsFrom") }.getOrDefault("")
}

/** 记忆化读取单个版本目录；`inheritsFrom` 链只在目标真实存在且可解析时才算有效。 */
private fun readInstance(
    root: InstanceRoot,
    name: String,
): GameInstance {
    val cache = mutableMapOf<String, Profile>()
    val profile = readProfile(root.path, name, cache, mutableSetOf())
    val versionFolder = root.path.resolve("versions").resolve(name)
    // JAR 优先与选中的版本 JSON 同名（HMCL 规则）：文件夹被单独改名后 JSON/JAR 仍保持
    // 原名时靠它找到；同名 JAR 不存在再按目录名兜底。
    val jarNames = listOf(profile.jsonName, name).distinct()
    val jar =
        jarNames
            .asSequence()
            .map { versionFolder.resolve("$it.jar") }
            .firstOrNull { it.isRegularFile() }
    val installed = jar != null || profile.parentJar
    val gamePath = if (versionFolder.resolve("mods").isDirectory()) versionFolder else root.path
    return GameInstance(
        id = InstanceCatalog.instanceId(root.directoryId, name),
        name = name,
        gameVersion = profile.gameVersion,
        versionId = name,
        gameDirectoryName = name,
        loader = profile.loader,
        loaderVersion = profile.loaderVersion,
        gameDirectoryId = root.directoryId,
        createdAt = profile.time.ifBlank { directoryTime(root, name) },
        installed = installed,
        modCount = countInstanceMods(gamePath),
        // 我们安装器写出的合并配置带 "molifoxManaged": true；没有标记的才是外部/其它启动器的版本，
        // 修复与升级时不能按自有格式改写它。
        externalProfile = !profile.managed,
        managedGameDirectory = profile.managed,
    )
}

private data class Profile(
    val id: String,
    val type: String,
    val time: String,
    val gameVersion: String,
    val parent: String,
    val parentJar: Boolean,
    val loader: LoaderType,
    val loaderVersion: String,
    val managed: Boolean,
    /** 选中的版本 JSON 文件名（不含扩展名）；JAR 优先按它配对，目录名只作兜底。 */
    val jsonName: String,
)

/**
 * 读取单个版本目录的配置。外部启动器写出的目录常有以下差异，这里都按「尽力识别」处理，
 * 只有 JSON 完全读不出来时才交给上层标成无法识别：
 * - JSON 文件名不一定等于目录名（有的启动器用配置里的 id 命名）；
 * - `inheritsFrom` 指向的父版本可能装在别的游戏目录里，缺失时不得让整条版本不可用；
 * - 精简的补丁配置可能没有 mainClass / libraries，但 id 里仍有可识别的游戏版本。
 */
private fun readProfile(
    root: Path,
    name: String,
    cache: MutableMap<String, Profile>,
    chain: MutableSet<String>,
    /** 合并布局的父版本目录：父版本 JSON 位于子版本目录内时传入该目录。 */
    localFolder: Path? = null,
): Profile {
    cache[name]?.let { return it }
    require(chain.add(name)) { I18n.t("instA1.catalog.cyclicInheritance", name) }
    try {
        val folder = localFolder ?: root.resolve("versions").resolve(name)
        require(folder.isDirectory()) { I18n.t("instA1.catalog.versionFolderMissing", name) }
        val file = versionJsonFile(folder, name)
        require(file != null) { I18n.t("instA1.catalog.versionFileMissing", name) }
        require(file.fileSize() <= MAX_JSON_BYTES) {
            I18n.t("instA1.catalog.versionFileTooLarge", name, file.fileName)
        }
        val json = readProfileJson(file, name)
        val id = json.string("id").ifBlank { name }
        val parent = json.string("inheritsFrom").ifBlank { json.string("jar") }
        val parentProfile =
            if (parent.isNotBlank() && parent != name && parent != id) {
                resolveParentProfile(root, folder, parent, cache, chain)
            } else {
                null
            }
        val inheritance = resolveProfileInheritance(root, folder, id, parent, parentProfile, json)
        var profile = buildProfile(json, inheritance, file.name.substringBeforeLast('.'))
        // JSON 继承链提不出真实版本时，从客户端 JAR 内嵌的 version.json 兜底；修正后的
        // 结果一并进缓存，继承本版本作父版本的子版本同样受益。
        if (profile.gameVersion.isBlank()) {
            val fromJar = minecraftVersionFromJar(folder, profile.jsonName, name)
            if (fromJar.isNotBlank()) profile = profile.copy(gameVersion = fromJar)
        }
        cache[name] = profile
        return profile
    } finally {
        chain.remove(name)
    }
}

/**
 * 解析 `inheritsFrom` 父版本：标准布局 `versions/<parent>/` 优先；缺失时在子版本目录里
 * 找 `<parent>.json` 兜底——部分整合包/启动器把原版与加载器版本写进同一个目录
 * （如 `1.21.4.json` 与 `1.21.4-Fabric x.json` 同层）。两边都没有才允许继承断链。
 */
private fun resolveParentProfile(
    root: Path,
    folder: Path,
    parent: String,
    cache: MutableMap<String, Profile>,
    chain: MutableSet<String>,
): Profile? =
    runCatching { readProfile(root, parent, cache, chain) }.getOrElse {
        val localJson = folder.resolve("$parent.json")
        if (localJson.isRegularFile()) {
            runCatching { readProfile(root, parent, cache, chain, folder) }.getOrNull()
        } else {
            null
        }
    }

/**
 * 从客户端 JAR 内嵌的 version.json 读取游戏版本（HMCL 同款兜底）：整合包把 JSON 的 id 改成
 * 自定义名且没有继承链时，JAR 本身仍带着真实版本号。只读取单个条目，不扫描整个 JAR。
 */
private fun minecraftVersionFromJar(
    folder: Path,
    jsonName: String,
    name: String,
): String =
    listOf(jsonName, name)
        .distinct()
        .asSequence()
        .map { folder.resolve("$it.jar") }
        .filter { it.isRegularFile() }
        .mapNotNull { jar ->
            runCatching {
                ZipFile(jar.toFile()).use { archive ->
                    val entry = archive.getEntry(JAR_VERSION_ENTRY) ?: return@runCatching null
                    val parsed = archive.getInputStream(entry).use { stream ->
                        JsonParser.parseReader(stream.reader())
                    }
                    if (!parsed.isJsonObject) return@runCatching null
                    minecraftVersionTag(parsed.asJsonObject.string("id"))
                }
            }.getOrNull()
        }.firstOrNull().orEmpty()

/**
 * 版本 JSON 的位置：优先同名文件；缺失时目录里只有一个 JSON 就用它；有多个时按文件名序
 * 挑第一个像版本清单的（HMCL 同款规则）。这样文件夹改名、JSON 以版本 id 命名、目录里混入
 * 普通 JSON 都不会让识别失效。
 */
private fun versionJsonFile(
    folder: Path,
    name: String,
): Path? {
    val exact = folder.resolve("$name.json")
    if (exact.isRegularFile()) return exact
    val jsonFiles = jsonFilesIn(folder)
    return when (jsonFiles.size) {
        0 -> null
        1 -> jsonFiles.first()
        else -> jsonFiles.firstOrNull { looksLikeVersionManifest(it) }
    }
}

private fun jsonFilesIn(folder: Path): List<Path> =
    runCatching {
        folder
            .listDirectoryEntries()
            .filter { it.isRegularFile() && it.name.endsWith(".json", true) }
            .sortedBy { it.name }
    }.getOrDefault(emptyList())

/**
 * 版本清单特征：主类、继承声明、jar 指向或库列表；模组写出的普通配置 JSON 不满足这些，
 * 超过大小上限的文件也不参与挑选（正式读取时会以相同上限拒绝）。
 */
private fun looksLikeVersionManifest(file: Path): Boolean =
    runCatching {
        if (file.fileSize() > MAX_JSON_BYTES) return@runCatching false
        val parsed = Files.newBufferedReader(file).use { reader -> JsonParser.parseReader(reader) }
        if (!parsed.isJsonObject) return@runCatching false
        val json = parsed.asJsonObject
        json.string("mainClass").isNotBlank() ||
            json.string("inheritsFrom").isNotBlank() ||
            json.string("jar").isNotBlank() ||
            (json.has("libraries") && json.get("libraries").isJsonArray)
    }.getOrDefault(false)

private fun brokenInstance(
    root: InstanceRoot,
    name: String,
    reason: String,
    json: Path,
): GameInstance =
    GameInstance(
        id = InstanceCatalog.instanceId(root.directoryId, name),
        name = name,
        versionId = name,
        gameDirectoryName = name,
        gameDirectoryId = root.directoryId,
        createdAt =
            runCatching {
                Files.getLastModifiedTime(json).toInstant().toString()
            }.getOrDefault(""),
        installed = false,
        externalProfile = true,
        broken = true,
        brokenReason = reason,
    )

private fun directoryTime(
    root: InstanceRoot,
    name: String,
): String =
    runCatching {
        Files
            .getLastModifiedTime(root.path.resolve("versions").resolve(name))
            .toInstant()
            .toString()
    }.getOrDefault("")

private fun readProfileJson(
    file: Path,
    name: String,
): JsonObject {
    val parsed =
        try {
            Files.newBufferedReader(file).use { reader -> JsonParser.parseReader(reader) }
        } catch (failure: IOException) {
            throw IllegalArgumentException(
                I18n.t("instA1.catalog.versionFileUnparsable", name),
                failure,
            )
        } catch (failure: JsonParseException) {
            throw IllegalArgumentException(
                I18n.t("instA1.catalog.versionFileUnparsable", name),
                failure,
            )
        }
    require(parsed.isJsonObject) { I18n.t("instA1.catalog.versionFileNotJsonObject", name) }
    return parsed.asJsonObject
}

private data class ProfileInheritance(
    val id: String,
    val parent: String,
    val parentProfile: Profile?,
    val parentJar: Boolean,
    val loader: Pair<LoaderType, String>,
)

private fun buildProfile(
    json: JsonObject,
    inheritance: ProfileInheritance,
    jsonName: String,
): Profile {
    val id = inheritance.id
    val parent = inheritance.parent
    val parentProfile = inheritance.parentProfile
    val parentJar = inheritance.parentJar
    val loader = inheritance.loader
    return Profile(
        id = id,
        type = json.string("type").ifBlank { parentProfile?.type.orEmpty() },
        time =
            json.string("time").takeIf { it.isNotBlank() }
                ?: json.string("releaseTime").ifBlank { parentProfile?.time.orEmpty() },
        // 真实版本号优先；目录名/整合包标识（TwilightMC-26.3v1.0）解析成 26.3。
        // 候选链逐个做版本号校验，全都提不出真实版本时保持为空（界面显示「版本未知」），
        // 不能把自定义 id（如整合包名「香草纪元」）原样当成游戏版本。
        gameVersion =
            listOf(json.string("gameVersion"), parentProfile?.gameVersion.orEmpty(), parent, id)
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { candidate -> minecraftVersionTag(candidate) }
                .firstOrNull { it != null }
                .orEmpty(),
        parent = parent,
        parentJar = parentJar,
        loader = loader.first,
        loaderVersion = loader.second,
        // 只认本目录自己的标记；外部版本即使继承自有标记的基础目录也保持 external。
        managed = json.boolean("molifoxManaged"),
        jsonName = jsonName,
    )
}

private fun appendVersionSignature(
    text: StringBuilder,
    child: Path,
) {
    val name = child.name
    text.append(name)
    if (child.isDirectory()) {
        // 配对规则依赖目录里全部 JSON/JAR 的文件名与内容：手动改名、混入普通 JSON、
        // 补删残留 JAR 都要改变签名，下一次可见时才会重扫。
        val entries =
            runCatching { child.listDirectoryEntries() }.getOrDefault(emptyList())
        val jsons =
            entries
                .filter { it.isRegularFile() && it.name.endsWith(".json", true) }
                .sortedBy { it.name }
        val jars =
            entries
                .filter { it.isRegularFile() && it.name.endsWith(".jar", true) }
                .sortedBy { it.name }
        text.append('|')
        appendFileSignatures(text, jsons, "no-json")
        text.append('|')
        appendFileSignatures(text, jars, "no-jar")
    }
    text.append('\n')
}

private fun appendFileSignatures(
    text: StringBuilder,
    files: List<Path>,
    missing: String,
) {
    if (files.isEmpty()) {
        text.append(missing)
        return
    }
    files.forEachIndexed { index, file ->
        if (index > 0) text.append(',')
        text.append(file.name).append(':')
        appendFileSignature(text, file, "missing")
    }
}

private fun appendFileSignature(
    text: StringBuilder,
    file: Path,
    missing: String,
) {
    if (file.isRegularFile()) {
        val modified =
            runCatching {
                Files.getLastModifiedTime(file).toMillis()
            }.getOrDefault(0)
        val size = runCatching { file.fileSize() }.getOrDefault(0)
        text.append(modified).append(':').append(size)
    } else {
        text.append(missing)
    }
}

private fun resolveProfileInheritance(
    root: Path,
    folder: Path,
    id: String,
    parent: String,
    parentProfile: Profile?,
    json: JsonObject,
): ProfileInheritance {
    val libraries =
        json.array("libraries").mapNotNull {
            runCatching { it.asJsonObject.string("name") }.getOrNull()?.ifBlank { null }
        }
    val ownLoader = detectLoader(id, libraries, json.string("mainClass"))
    val loader =
        if (ownLoader.first != LoaderType.Vanilla) {
            ownLoader
        } else {
            parentProfile?.let { it.loader to it.loaderVersion } ?: ownLoader
        }
    // 父版本 JAR：标准布局在自己的 versions/<parent>/ 里；合并布局与子版本同目录。
    val parentJar =
        parentProfile != null &&
            (
                root
                    .resolve(
                        "versions",
                    ).resolve(parent)
                    .resolve("$parent.jar")
                    .isRegularFile() ||
                    folder.resolve("$parent.jar").isRegularFile() ||
                    parentProfile.parentJar
            )
    return ProfileInheritance(id, parent, parentProfile, parentJar, loader)
}
