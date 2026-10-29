// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责游戏版本继承链、依赖库和启动主类解析。

package dev.molifox.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.nio.file.Path
import java.util.zip.ZipFile

data class ResolvedProfile(val id: String, val jarId: String, val json: JsonObject) {
    val requiredJava: Int
        get() = json.obj("javaVersion").int("majorVersion", 8)

    val mainClass: String
        get() = json.string("mainClass")
}

class VersionResolver(private val root: Path, private val platform: PlatformServices) {
    /** Launcher profile IDs may be pack names; resource APIs require the actual game version. */
    fun minecraftVersion(profile: ResolvedProfile): String {
        val json = profile.json
        val declared =
            listOf(
                    json.string("_minecraftVersion"),
                    json.string("clientVersion"),
                    json
                        .array("patches")
                        .firstOrNull {
                            it.isJsonObject &&
                                it.asJsonObject.string("id") in setOf("game", "minecraft")
                        }
                        ?.asJsonObject
                        ?.string("version")
                        .orEmpty(),
                )
                .firstOrNull { it.isNotBlank() }
        if (declared != null) return safeFilename(declared)
        val jar = safePath(root, "versions/${safeFilename(profile.jarId)}/${profile.jarId}.jar")
        val embedded = runCatching {
            ZipFile(jar.toFile()).use { zip ->
                packJson(zip, "version.json")?.string("id")?.takeIf { it.isNotBlank() }
            }
        }
            .getOrNull()
        return embedded?.let(::safeFilename) ?: profile.jarId
    }

    fun resolve(id: String, chain: Set<String> = emptySet()): ResolvedProfile {
        safeFilename(id)
        require(id !in chain && chain.size < 20) { "游戏版本继承出现循环" }
        val child = readObject(root.resolve("versions").resolve(id).resolve("$id.json"))
        val parentId = child.string("inheritsFrom")
        if (parentId.isBlank()) return ResolvedProfile(id, child.string("jar", id), child)
        val parent = resolve(parentId, chain + id)
        val merged = mergeVersionProfiles(parent.json, child)
        val jar =
            child.string("jar").ifBlank {
                if (child.obj("downloads").has("client")) id else parent.jarId
            }
        return ResolvedProfile(id, jar, merged)
    }

    fun allowed(element: JsonObject, features: Map<String, Boolean> = emptyMap()): Boolean =
        rulesAllow(
            element.getAsJsonArray("rules"),
            platform.osName,
            System.getProperty("os.arch"),
            System.getProperty("os.version"),
            features,
        )

    fun libraries(profile: ResolvedProfile): List<JsonObject> =
        profile.json.array("libraries").map { it.asJsonObject }.filter { allowed(it) }

    fun libraryArtifacts(library: JsonObject): List<Pair<DownloadArtifact, Boolean>> {
        val artifacts = mutableListOf<Pair<DownloadArtifact, Boolean>>()
        val downloads = library.obj("downloads")
        if (downloads.has("artifact")) {
            artifacts +=
                libraryArtifact(
                    library,
                    downloads.obj("artifact"),
                    mavenPath(library.string("name")),
                    native = false,
                )
        } else if (
            hasClasspathArtifact(library) && !library.string("name").startsWith("net.minecraft:")
        ) {
            val relative = mavenPath(library.string("name"))
            artifacts +=
                DownloadArtifact(
                    mavenUrl(library, relative),
                    safePath(root.resolve("libraries"), relative),
                ) to false
        }
        val nativeClassifier =
            library
                .obj("natives")
                .string(platform.osName)
                .replace("\${arch}", if (platform.architecture == "x86") "32" else "64")
        if (nativeClassifier.isNotBlank() && downloads.obj("classifiers").has(nativeClassifier)) {
            artifacts +=
                libraryArtifact(
                    library,
                    downloads.obj("classifiers").obj(nativeClassifier),
                    mavenPath(library.string("name"), nativeClassifier),
                    native = true,
                )
        }
        return artifacts
    }

    fun hasClasspathArtifact(library: JsonObject): Boolean =
        library.obj("downloads").has("artifact") || !library.obj("downloads").has("classifiers")

    /**
     * 依赖库只从 Maven 仓库下载：JSON 没有给出 `downloads.*.url`，或给出的地址属于对象存储
     * （`piston-data` 只服务版本 JSON 明确列出的文件）时，按坐标回落到
     * `libraries.minecraft.net` 或该库声明的仓库，再由下载源换成镜像。
     *
     * 规则：raw url 优先；没有时用库声明仓库（或默认库根 `https://libraries.minecraft.net`）
     * + 坐标路径拼出地址，再由下载源换成镜像。
     */
    private fun libraryArtifact(
        library: JsonObject,
        artifact: JsonObject,
        fallbackPath: String,
        native: Boolean,
    ): Pair<DownloadArtifact, Boolean> {
        val relative = artifact.string("path", fallbackPath)
        val declared = artifact.string("url")
        val url =
            if (declared.isBlank() || isObjectStorageUrl(declared)) mavenUrl(library, relative)
            else declared
        return DownloadArtifact(
            url,
            safePath(root.resolve("libraries"), relative),
            artifact.string("sha1"),
            artifact.get("size")?.asLong ?: 0,
        ) to native
    }

    /** 库地址：库自己声明的 Maven 仓库优先，否则用官方 libraries 仓库。 */
    private fun mavenUrl(library: JsonObject, relative: String): String =
        library.string("url").trimEnd('/').ifBlank { "https://libraries.minecraft.net" } +
            "/$relative"

    /** 对象存储只放版本 JSON 明确给出的文件；依赖库不能从那里按路径猜地址。 */
    private fun isObjectStorageUrl(url: String): Boolean =
        runCatching { java.net.URI(url).host }.getOrNull() in
            setOf("piston-data.mojang.com", "launcher.mojang.com")

    fun libraryPath(library: JsonObject): Path =
        safePath(
            root.resolve("libraries"),
            library
                .obj("downloads")
                .obj("artifact")
                .string("path", mavenPath(library.string("name"))),
        )
}

/**
 * 把子版本配置叠加到已解析的父配置上：参数数组按顺序追加，库按坐标合并，其余字段以子配置为准。
 *
 * 与 [VersionResolver.resolve] 同一套规则。安装加载器时用它把基础版本和加载器配置直接合成目标实例的
 * 完整配置，而不是让目标目录长期只放一个 `inheritsFrom` 存根、再额外保留两个版本目录。
 */
internal fun mergeVersionProfiles(parent: JsonObject, child: JsonObject): JsonObject {
    val merged = parent.deepCopy()
    child
        .entrySet()
        .filter { it.key !in setOf("arguments", "libraries") }
        .forEach { (key, value) -> merged.add(key, value.deepCopy()) }
    val arguments = JsonObject()
    for (kind in listOf("game", "jvm")) {
        val combined = JsonArray()
        parent.obj("arguments").array(kind).forEach { combined.add(it.deepCopy()) }
        child.obj("arguments").array(kind).forEach { combined.add(it.deepCopy()) }
        arguments.add(kind, combined)
    }
    merged.add("arguments", arguments)
    val libraries = linkedMapOf<String, JsonElement>()
    (parent.array("libraries") + child.array("libraries")).forEach { element ->
        val library = element.asJsonObject
        val parts = library.string("name").split(':')
        val key =
            if (parts.size >= 3) "${parts[0]}:${parts[1]}:${parts.getOrElse(3) { "" }}"
            else library.string("name")
        libraries[key] = library.deepCopy()
    }
    merged.add("libraries", JsonArray().apply { libraries.values.forEach { add(it) } })
    return merged
}

fun rulesAllow(
    rules: JsonArray?,
    os: String,
    architecture: String,
    osVersion: String,
    features: Map<String, Boolean> = emptyMap(),
): Boolean {
    if (rules == null || rules.isEmpty) return true
    var result = false
    for (entry in rules) {
        val rule = entry.asJsonObject
        val restriction = rule.obj("os")
        if (restriction.has("name") && restriction.string("name") != os) continue
        if (
            restriction.has("arch") &&
                !Regex(restriction.string("arch")).containsMatchIn(architecture)
        )
            continue
        if (
            restriction.has("version") &&
                !Regex(restriction.string("version")).containsMatchIn(osVersion)
        )
            continue
        if (
            rule.obj("features").entrySet().any { (key, value) ->
                (features[key] ?: false) != value.asBoolean
            }
        )
            continue
        result = rule.string("action") == "allow"
    }
    return result
}

fun mavenPath(coordinate: String, classifierOverride: String? = null): String {
    val extension = coordinate.substringAfter('@', "jar")
    val parts = coordinate.substringBefore('@').split(':')
    require(
        parts.size in 3..4 &&
            parts.none { it.isBlank() } &&
            extension.matches(Regex("[a-zA-Z0-9]+"))
    ) {
        "无效的 Maven 依赖：$coordinate"
    }
    val classifier = classifierOverride ?: parts.getOrNull(3)
    val filename = "${parts[1]}-${parts[2]}${classifier?.let { "-$it" } ?: ""}.$extension"
    val relative = "${parts[0].replace('.', '/')}/${parts[1]}/${parts[2]}/$filename"
    safePath(Path.of(System.getProperty("java.io.tmpdir")), relative)
    return relative
}

internal fun expandArguments(
    arguments: JsonArray,
    values: Map<String, String>,
    platform: PlatformServices,
    features: Map<String, Boolean>,
): List<String> {
    val result = mutableListOf<String>()
    for (argument in arguments) {
        if (argument.isJsonPrimitive) result += substitute(argument.asString, values)
        else {
            val objectArgument = argument.asJsonObject
            if (
                !rulesAllow(
                    objectArgument.getAsJsonArray("rules"),
                    platform.osName,
                    System.getProperty("os.arch"),
                    System.getProperty("os.version"),
                    features,
                )
            )
                continue
            val value = objectArgument.get("value") ?: continue
            if (value.isJsonArray)
                value.asJsonArray.forEach { result += substitute(it.asString, values) }
            else result += substitute(value.asString, values)
        }
    }
    return result
}

internal fun substitute(argument: String, values: Map<String, String>): String =
    Regex("\\$\\{([^}]+)}").replace(argument) { match ->
        values[match.groupValues[1]] ?: error("不支持的游戏启动参数：${match.value}")
    }
