// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责以 versions/ 目录为唯一事实来源扫描实例（不再保存实例列表）。

package dev.molifox.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.*

/** 一个已登记的游戏根及其界面目录标识；null 表示默认游戏根（`settings.gameRoot`）。 */
internal data class InstanceRoot(val path: Path, val directoryId: String?)

/**
 * `versions` 目录扫描器。目录名就是实例身份，`<name>/<name>.json`
 * 是版本配置；配置缺失、JSON 损坏或 `inheritsFrom` 断链时仍生成一个「无法识别」实例，
 * 由界面显示但禁止启动，而不是静默隐藏。
 */
internal object InstanceCatalog {
    private const val MAX_JSON_BYTES = 8L * 1024 * 1024

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
        fun add(path: Path, directoryId: String?) {
            val key = runCatching { path.toRealPath().toString() }.getOrElse {
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
            text.append(root.directoryId).append('|').append(root.path).append('\n')
            val versions = root.path.resolve("versions")
            if (!versions.isDirectory()) {
                text.append("<missing>\n")
                continue
            }
            val children =
                runCatching { versions.listDirectoryEntries().sortedBy { it.name } }
                    .getOrElse { return "unreadable:${root.path}" }
            for (child in children) {
                val name = child.name
                text.append(name)
                if (child.isDirectory()) {
                    val json = child.resolve("$name.json")
                    text.append('|')
                    if (json.isRegularFile()) {
                        val modified =
                            runCatching { Files.getLastModifiedTime(json).toMillis() }.getOrDefault(0)
                        val size = runCatching { json.fileSize() }.getOrDefault(0)
                        text.append(modified).append(':').append(size)
                    } else text.append("no-json")
                    // 手动补上/移除同名 JAR 也要在下一次可见时反映为已安装/待修复。
                    val jar = child.resolve("$name.jar")
                    text.append('|')
                    if (jar.isRegularFile()) {
                        val modified =
                            runCatching { Files.getLastModifiedTime(jar).toMillis() }.getOrDefault(0)
                        val size = runCatching { jar.fileSize() }.getOrDefault(0)
                        text.append(modified).append(':').append(size)
                    } else text.append("no-jar")
                }
                text.append('\n')
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
            // 完全空白的目录不算版本：安装器会先建目录再写 JSON。
            val empty =
                runCatching { folder.listDirectoryEntries().isEmpty() }.getOrDefault(false)
            if (empty && !folder.resolve("$name.json").exists()) return@mapNotNull null
            runCatching { readInstance(root, name) }
                .getOrElse { error ->
                    brokenInstance(
                        root,
                        name,
                        error.message ?: "无法识别版本配置",
                        folder.resolve("$name.json"),
                    )
                }
        }
    }

    /** 记忆化读取单个版本目录；`inheritsFrom` 链只在目标真实存在且可解析时才算有效。 */
    private fun readInstance(root: InstanceRoot, name: String): GameInstance {
        val cache = mutableMapOf<String, Profile>()
        val profile = readProfile(root.path, name, cache, mutableSetOf())
        val jar = root.path.resolve("versions").resolve(name).resolve("$name.jar")
        val installed = jar.isRegularFile() || profile.parentJar
        return GameInstance(
            id = instanceId(root.directoryId, name),
            name = name,
            gameVersion = profile.gameVersion,
            versionId = name,
            gameDirectoryName = name,
            loader = profile.loader,
            loaderVersion = profile.loaderVersion,
            gameDirectoryId = root.directoryId,
            createdAt = profile.time.ifBlank { directoryTime(root, name) },
            installed = installed,
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
    )

    private fun readProfile(
        root: Path,
        name: String,
        cache: MutableMap<String, Profile>,
        chain: MutableSet<String>,
    ): Profile {
        cache[name]?.let { return it }
        require(chain.add(name)) { "版本配置存在循环继承：$name" }
        val folder = root.resolve("versions").resolve(name)
        require(folder.isDirectory()) { "版本目录不存在：versions/$name" }
        val file = folder.resolve("$name.json")
        require(file.isRegularFile()) { "缺少版本配置：versions/$name/$name.json" }
        require(file.fileSize() <= MAX_JSON_BYTES) { "版本配置过大：versions/$name/$name.json" }
        val parsed =
            try {
                Files.newBufferedReader(file).use { reader -> JsonParser.parseReader(reader) }
            } catch (failure: Exception) {
                throw IllegalArgumentException(
                    "版本配置无法解析：versions/$name/$name.json",
                    failure,
                )
            }
        require(parsed.isJsonObject) { "版本配置不是 JSON 对象：versions/$name/$name.json" }
        val json: JsonObject = parsed.asJsonObject
        val id = json.string("id").ifBlank { name }
        val parent = json.string("inheritsFrom").ifBlank { json.string("jar") }
        val parentProfile =
            if (parent.isNotBlank() && parent != name)
                readProfile(root, parent, cache, chain)
            else null
        require(
            json.string("mainClass").isNotBlank() ||
                parentProfile != null ||
                json.array("libraries").size() > 0
        ) { "版本配置缺少 mainClass：versions/$name/$name.json" }
        val libraries =
            json.array("libraries").mapNotNull {
                runCatching { it.asJsonObject.string("name") }.getOrNull()?.ifBlank { null }
            }
        val ownLoader = loaderOf(id, libraries)
        val loader = if (ownLoader.first != LoaderType.Vanilla) ownLoader
        else parentProfile?.let { it.loader to it.loaderVersion } ?: ownLoader
        val parentJar =
            parentProfile != null &&
                (root.resolve("versions").resolve(parent).resolve("$parent.jar").isRegularFile() ||
                    parentProfile.parentJar)
        val profile =
            Profile(
                id = id,
                type = json.string("type").ifBlank { parentProfile?.type.orEmpty() },
                time =
                    json.string("time").takeIf { it.isNotBlank() }
                        ?: json.string("releaseTime").ifBlank { parentProfile?.time.orEmpty() },
                // 真实版本号优先；目录名/整合包标识（TwilightMC-26.3v1.0）解析成 26.3，
                // 否则按版本筛选会发给服务端一个不存在的版本、静默得到 0 条结果。
                gameVersion =
                    json.string("gameVersion").ifBlank {
                        parentProfile?.gameVersion
                            ?: if (parent.isNotBlank()) minecraftVersionTag(parent) ?: parent
                            else minecraftVersionTag(id) ?: id
                    },
                parent = parent,
                parentJar = parentJar,
                loader = loader.first,
                loaderVersion = loader.second,
                // 只认本目录自己的标记；外部版本即使继承自有标记的基础目录也保持 external。
                managed = json.boolean("molifoxManaged"),
            )
        chain.remove(name)
        cache[name] = profile
        return profile
    }

    private fun loaderOf(id: String, libraries: List<String>): Pair<LoaderType, String> {
        fun coordinate(prefix: String): String? =
            libraries.firstOrNull { it.startsWith(prefix) }
        coordinate("net.fabricmc:fabric-loader:")?.let {
            return LoaderType.Fabric to it.split(':').getOrElse(2) { "" }
        }
        coordinate("org.quiltmc:quilt-loader:")?.let {
            return LoaderType.Quilt to it.split(':').getOrElse(2) { "" }
        }
        coordinate("net.neoforged:neoforge:")?.let {
            return LoaderType.NeoForge to it.split(':').getOrElse(2) { "" }
        }
        coordinate("net.minecraftforge:forge:")?.let {
            return LoaderType.Forge to it.split(':').getOrElse(2) { "" }
        }
        val lower = id.lowercase()
        return when {
            "neoforge" in lower -> LoaderType.NeoForge to ""
            "fabric" in lower -> LoaderType.Fabric to ""
            "quilt" in lower -> LoaderType.Quilt to ""
            "forge" in lower -> LoaderType.Forge to ""
            else -> LoaderType.Vanilla to ""
        }
    }

    private fun brokenInstance(
        root: InstanceRoot,
        name: String,
        reason: String,
        json: Path,
    ): GameInstance =
        GameInstance(
            id = instanceId(root.directoryId, name),
            name = name,
            versionId = name,
            gameDirectoryName = name,
            gameDirectoryId = root.directoryId,
            createdAt = runCatching {
                    Files.getLastModifiedTime(json).toInstant().toString()
                }
                .getOrDefault(""),
            installed = false,
            externalProfile = true,
            broken = true,
            brokenReason = reason,
        )

    private fun directoryTime(root: InstanceRoot, name: String): String =
        runCatching {
                Files.getLastModifiedTime(root.path.resolve("versions").resolve(name))
                    .toInstant()
                    .toString()
            }
            .getOrDefault("")

    /** 稳定 id：同一游戏目录下同一文件夹始终得到同一 UUID（运行目录等仍要求 UUID）。 */
    fun instanceId(directoryId: String?, folder: String): String =
        UUID.nameUUIDFromBytes("MoliFoxInstance:${directoryId.orEmpty()}:$folder".toByteArray())
            .toString()

    /** 读取版本 JSON 的 `inheritsFrom`，用于重命名后同步兄弟配置；读不到时返回空。 */
    fun inheritsFrom(file: Path): String =
        runCatching { readObject(file).string("inheritsFrom") }.getOrDefault("")
}