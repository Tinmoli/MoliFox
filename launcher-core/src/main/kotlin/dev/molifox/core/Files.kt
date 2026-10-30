// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责文件路径、原子写入、校验与 JSON 访问辅助。

package dev.molifox.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.name
import kotlin.io.path.walk
import kotlin.io.path.writeText

internal val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

internal fun JsonObject.string(
    key: String,
    default: String = "",
) = get(key)?.takeUnless { it.isJsonNull }?.asString ?: default

internal fun JsonObject.int(
    key: String,
    default: Int = 0,
) = get(key)?.takeUnless { it.isJsonNull }?.asInt ?: default

internal fun JsonObject.obj(key: String): JsonObject = getAsJsonObject(key) ?: JsonObject()

internal fun JsonObject.array(key: String): JsonArray = getAsJsonArray(key) ?: JsonArray()

internal fun JsonObject.boolean(
    key: String,
    default: Boolean = false,
) = get(key)?.takeUnless { it.isJsonNull }?.asBoolean ?: default

internal fun readObject(path: Path): JsonObject =
    Files.newBufferedReader(path).use { JsonParser.parseReader(it).asJsonObject }

internal fun writeJson(
    path: Path,
    value: Any,
) {
    path.parent.createDirectories()
    val temporary = path.resolveSibling("${path.fileName}.${UUID.randomUUID()}.tmp")
    try {
        temporary.writeText(gson.toJson(value))
        try {
            Files.move(
                temporary,
                path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        temporary.deleteIfExists()
    }
}

/** Reject traversal, drive prefixes, ADS, and Windows reserved names on every OS. */
fun safePath(
    root: Path,
    relative: String,
): Path {
    require(
        relative.isNotBlank() &&
            relative.length < 4096 &&
            '\u0000' !in relative &&
            ':' !in relative &&
            !relative.startsWith('/') &&
            !relative.startsWith('\\'),
    ) {
        "不安全的文件路径"
    }
    val segments = relative.replace('\\', '/').split('/')
    require(
        segments.none {
            it == ".." ||
                it.endsWith('.') ||
                it.endsWith(' ') ||
                Regex("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?").matches(it)
        },
    ) {
        "不安全的文件路径"
    }
    val absoluteRoot = root.toAbsolutePath().normalize()
    val target = absoluteRoot.resolve(segments.joinToString("/")).normalize()
    require(target != absoluteRoot && target.startsWith(absoluteRoot)) { "路径超出指定目录" }
    var parent: Path? = target
    while (parent != null && parent != absoluteRoot.parent) {
        require(!Files.isSymbolicLink(parent)) { "目标路径包含符号链接" }
        parent = parent.parent
    }
    return target
}

internal fun safeFilename(name: String): String {
    require('/' !in name && '\\' !in name) { "文件名不能包含目录" }
    safePath(Path.of(System.getProperty("java.io.tmpdir")), name)
    return name
}

internal fun digest(
    path: Path,
    algorithm: String = "SHA-1",
): String {
    val digest = MessageDigest.getInstance(algorithm)
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(65536)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun offlineUuid(name: String): String =
    UUID
        .nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8))
        .toString()
        .replace("-", "")

fun splitArguments(input: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    input.trim().forEach { character ->
        when {
            quote != null -> if (quote == character) quote = null else current.append(character)
            character == '"' || character == '\'' -> quote = character
            character.isWhitespace() ->
                if (current.isNotEmpty()) {
                    result.add(current.toString())
                    current.clear()
                }
            else -> current.append(character)
        }
    }
    require(quote == null) { "JVM 参数引号未闭合" }
    if (current.isNotEmpty()) result.add(current.toString())
    return result
}

fun redactSecrets(text: String): String =
    text
        .replace(
            Regex(
                "(?i)((?:access[_-]?token|refresh[_-]?token|authorization|--accessToken)[\\\"'\\s:=]+)(?:Bearer\\s+)?[^\\s,\\\"'}]+",
            ),
            "$1[已隐藏]",
        ).replace(Regex("eyJ[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"), "[已隐藏]")

internal fun validateArchive(
    zip: ZipFile,
    prefix: String,
    root: Path,
    maxBytes: Long = 4L * 1024 * 1024 * 1024,
): List<Pair<java.util.zip.ZipEntry, Path>> {
    val entries =
        zip
            .entries()
            .asSequence()
            .filter { it.name.startsWith(prefix) && !it.isDirectory }
            .toList()
    require(entries.size <= 50000) { "压缩包文件过多" }
    var size = 0L
    return entries.map { entry ->
        require(entry.size >= 0) { "压缩包文件长度无效" }
        size = Math.addExact(size, entry.size)
        require(size <= maxBytes) { "压缩包解压后体积超出限制" }
        entry to safePath(root, entry.name.removePrefix(prefix))
    }
}

internal fun copyTree(
    source: Path,
    destination: Path,
    excluded: Set<String> = emptySet(),
) {
    require(!Files.isSymbolicLink(source)) { "不支持导入符号链接" }
    if (!source.exists(LinkOption.NOFOLLOW_LINKS)) return
    if (source.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
        destination.parent.createDirectories()
        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
        return
    }
    Files.walk(source).use { paths ->
        paths.forEach { file ->
            if (file == source) return@forEach
            val relative = source.relativize(file)
            if (relative.any { it.toString() in excluded } || Files.isSymbolicLink(file)) {
                return@forEach
            }
            val target = safePath(destination, relative.toString())
            if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                target.createDirectories()
            } else {
                target.parent.createDirectories()
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
