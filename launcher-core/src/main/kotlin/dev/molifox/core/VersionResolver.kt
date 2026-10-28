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
        val merged = parent.json.deepCopy()
        child
            .entrySet()
            .filter { it.key !in setOf("arguments", "libraries") }
            .forEach { (key, value) -> merged.add(key, value.deepCopy()) }
        val arguments = JsonObject()
        for (kind in listOf("game", "jvm")) {
            val combined = JsonArray()
            parent.json.obj("arguments").array(kind).forEach { combined.add(it.deepCopy()) }
            child.obj("arguments").array(kind).forEach { combined.add(it.deepCopy()) }
            arguments.add(kind, combined)
        }
        merged.add("arguments", arguments)
        val libraries = linkedMapOf<String, JsonElement>()
        (parent.json.array("libraries") + child.array("libraries")).forEach { element ->
            val library = element.asJsonObject
            val parts = library.string("name").split(':')
            val key =
                if (parts.size >= 3) "${parts[0]}:${parts[1]}:${parts.getOrElse(3) { "" }}"
                else library.string("name")
            libraries[key] = library.deepCopy()
        }
        merged.add("libraries", JsonArray().apply { libraries.values.forEach { add(it) } })
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
            val artifact = downloads.obj("artifact")
            if (artifact.string("url").isNotBlank())
                artifacts += parseArtifact(artifact, mavenPath(library.string("name"))) to false
        } else if (
            hasClasspathArtifact(library) && !library.string("name").startsWith("net.minecraft:")
        ) {
            val relative = mavenPath(library.string("name"))
            val url =
                library.string("url", "https://libraries.minecraft.net/").trimEnd('/') +
                    "/$relative"
            artifacts +=
                DownloadArtifact(url, safePath(root.resolve("libraries"), relative)) to false
        }
        val nativeClassifier =
            library
                .obj("natives")
                .string(platform.osName)
                .replace("\${arch}", if (platform.architecture == "x86") "32" else "64")
        if (nativeClassifier.isNotBlank() && downloads.obj("classifiers").has(nativeClassifier)) {
            val artifact = downloads.obj("classifiers").obj(nativeClassifier)
            artifacts +=
                parseArtifact(artifact, mavenPath(library.string("name"), nativeClassifier)) to true
        }
        return artifacts
    }

    fun hasClasspathArtifact(library: JsonObject): Boolean =
        library.obj("downloads").has("artifact") || !library.obj("downloads").has("classifiers")

    private fun parseArtifact(artifact: JsonObject, fallback: String): DownloadArtifact =
        DownloadArtifact(
            artifact.string("url"),
            safePath(root.resolve("libraries"), artifact.string("path", fallback)),
            artifact.string("sha1"),
            artifact.get("size")?.asLong ?: 0,
        )

    fun libraryPath(library: JsonObject): Path =
        safePath(
            root.resolve("libraries"),
            library
                .obj("downloads")
                .obj("artifact")
                .string("path", mavenPath(library.string("name"))),
        )
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
