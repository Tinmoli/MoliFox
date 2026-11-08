// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责 JSON 访问、原子写入、文件摘要与命令行/日志文本辅助。

package dev.molifox.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.molifox.core.i18n.I18n
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
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
    require(quote == null) { I18n.t("coreC.args.unclosedQuote") }
    if (current.isNotEmpty()) result.add(current.toString())
    return result
}

fun redactSecrets(text: String): String =
    text
        .replace(
            Regex(
                "(?i)((?:access[_-]?token|refresh[_-]?token|authorization|--accessToken)" +
                    "[\\\"'\\s:=]+)(?:Bearer\\s+)?[^\\s,\\\"'}]+",
            ),
            "$1[" + I18n.t("miscA1.files.redacted") + "]",
        ).replace(
            Regex("eyJ[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"),
            "[" + I18n.t("miscA1.files.redacted") + "]",
        )
