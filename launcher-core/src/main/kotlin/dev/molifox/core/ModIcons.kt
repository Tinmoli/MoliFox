// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 按需读取模组归档内的图标，限制大小并返回图像字节。

package dev.molifox.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

private const val MAX_PACK_ICON_PATH_CHARS = 240

/** 模组图标上限 1 MiB：超过上限的图片不当图标，避免把整包资源读进内存。 */
private const val MOD_ICON_MAX_BYTES = 1024 * 1024
private const val MAX_MOD_ICON_DIMENSION = 4096

/** 声明文件（fabric.mod.json / mods.toml / mcmod.info）的读取上限。 */
private const val MOD_ICON_DECLARATION_MAX_BYTES = 2 * 1024 * 1024

/** 兜底候选：assets/<命名空间>/icon.png → logo.png → pack.png → 根目录 icon.png。 */
private val modIconFallbacks =
    listOf(
        Regex("(?i)assets/[^/]+/icon\\.png"),
        Regex("(?i)assets/[^/]+/logo\\.png"),
        Regex("(?i)pack\\.png"),
        Regex("(?i)icon\\.png"),
    )

/** 读进内存的一张候选图标：原始字节 + 是否本来就是 PNG（是则原样返回，否则重新编码）。 */
private class ModIconPayload(
    val bytes: ByteArray,
    val png: Boolean,
)

/** 只读取归档内的声明和图像，返回受限的 PNG 字节，不生成派生文件。 */
fun readModIcon(jar: Path): Result<ByteArray?> =
    runCatching {
        if (!jar.isRegularFile()) return@runCatching null
        val payload = ZipFile(jar.toFile()).use { modIconPayload(it) } ?: return@runCatching null
        val image = ImageIO.read(ByteArrayInputStream(payload.bytes)) ?: return@runCatching null
        if (image.width !in 1..MAX_MOD_ICON_DIMENSION ||
            image.height !in 1..MAX_MOD_ICON_DIMENSION
        ) {
            return@runCatching null
        }
        if (payload.png) {
            payload.bytes
        } else {
            java.io.ByteArrayOutputStream().use { output ->
                if (ImageIO.write(image, "png", output)) output.toByteArray() else null
            }
        }
    }

/** 按优先级拿到第一张能读出来的图片；声明指向的条目损坏时继续尝试后续来源。 */
private fun modIconPayload(zip: ZipFile): ModIconPayload? {
    val declared = linkedSetOf<String>()
    textEntry(zip, "fabric.mod.json")?.let { source ->
        fabricIcon(runCatching { JsonParser.parseString(source).asJsonObject }.getOrNull())
            ?.let(::declaredIconName)
            ?.let(declared::add)
    }
    for (name in listOf("META-INF/neoforge.mods.toml", "META-INF/mods.toml")) {
        val source = textEntry(zip, name) ?: continue
        tomlLogo(source)?.let(::declaredIconName)?.let(declared::add)
    }
    textEntry(zip, "mcmod.info")?.let { source ->
        mcmodLogo(source)?.let(::declaredIconName)?.let(declared::add)
    }
    return declared.firstNotNullOfOrNull { name -> iconPayload(zip, name) }
        ?: modIconFallbacks.firstNotNullOfOrNull { pattern ->
            zip
                .entries()
                .asSequence()
                .filter { !it.isDirectory && pattern.matches(it.name) }
                .firstNotNullOfOrNull { entry -> iconPayload(zip, entry.name) }
        }
}

/** 读取声明文件文本；目录、空文件和超大文件都视为没有声明。 */
private fun textEntry(
    zip: ZipFile,
    name: String,
): String? {
    val entry = zip.getEntry(name) ?: return null
    if (entry.isDirectory || entry.size !in 1L..MOD_ICON_DECLARATION_MAX_BYTES.toLong()) return null
    val bytes = zip.getInputStream(entry).use { it.readNBytes(MOD_ICON_DECLARATION_MAX_BYTES + 1) }
    if (bytes.size !in 1..MOD_ICON_DECLARATION_MAX_BYTES) return null
    return bytes.toString(Charsets.UTF_8).takeIf { it.isNotBlank() }
}

/** 声明里的相对路径先做安全裁剪；绝对路径、协议、`..` 一律不认。 */
private fun declaredIconName(value: String): String? {
    val name =
        value.trim().replace('\\', '/').removePrefix("./").removePrefix("/").takeIf {
            it.isNotBlank()
        } ?: return null
    if (name.length > MAX_PACK_ICON_PATH_CHARS || "://" in name) return null
    if (name.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
    return name
}

/** JAR 里的路径大小写不总是与声明一致，精确匹配失败后按忽略大小写再找一次。 */
private fun iconEntry(
    zip: ZipFile,
    name: String,
): ZipEntry? =
    zip.getEntry(name)
        ?: zip.entries().asSequence().firstOrNull {
            !it.isDirectory && it.name.equals(name, ignoreCase = true)
        }

/** 读取单张候选图片：条目必须存在、非空、不超过 1 MiB；超过上限或读不出来都返回 null。 */
private fun iconPayload(
    zip: ZipFile,
    name: String,
): ModIconPayload? {
    val entry = iconEntry(zip, name) ?: return null
    if (entry.isDirectory || entry.size !in 1L..MOD_ICON_MAX_BYTES.toLong()) return null
    val bytes = zip.getInputStream(entry).use { it.readNBytes(MOD_ICON_MAX_BYTES + 1) }
    if (bytes.size !in 1..MOD_ICON_MAX_BYTES) return null
    return ModIconPayload(bytes, entry.name.endsWith(".png", ignoreCase = true))
}

/** Fabric：`icon` 可以是字符串，也可以是「尺寸 → 路径」映射（取尺寸最大的一项）。 */
private fun fabricIcon(info: JsonObject?): String? {
    val icon: JsonElement = info?.get("icon")?.takeUnless { it.isJsonNull } ?: return null
    if (icon.isJsonPrimitive) {
        return icon.takeIf { it.asJsonPrimitive.isString }?.asString?.takeIf { it.isNotBlank() }
    }
    if (!icon.isJsonObject) return null
    return icon.asJsonObject
        .entrySet()
        .mapNotNull { (size, value) ->
            val path =
                value
                    .takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString
                    ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val rank = size.takeWhile { character -> character.isDigit() }.toIntOrNull() ?: 0
            rank to path
        }.maxByOrNull { it.first }
        ?.second
}

/** Forge / NeoForge：`logoFile` 在 `[[mods]]` 里，个别 JAR 放在顶层；取第一个非空值。 */
private val tomlLogoPattern =
    Regex("""logoFile\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)

private fun tomlLogo(source: String): String? =
    tomlLogoPattern
        .findAll(source)
        .mapNotNull { match ->
            (match.groupValues[1].ifBlank { match.groupValues[2] }).takeIf { it.isNotBlank() }
        }.firstOrNull()

/** 旧版 Forge：`mcmod.info` 是数组，或带 `modList` 数组的对象；取第一个非空 `logoFile`。 */
private fun mcmodLogo(source: String): String? =
    runCatching {
        val json = JsonParser.parseString(source)
        val entries =
            when {
                json.isJsonArray -> json.asJsonArray.toList()
                json.isJsonObject ->
                    json.asJsonObject
                        .get("modList")
                        ?.takeIf { it.isJsonArray }
                        ?.asJsonArray
                        ?.toList()
                        ?: listOf(json)
                else -> emptyList()
            }
        entries
            .mapNotNull { value ->
                value
                    .takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?.get("logoFile")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString
            }.firstOrNull { it.isNotBlank() }
    }.getOrNull()
