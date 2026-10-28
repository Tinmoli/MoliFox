package dev.molifox.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlin.io.path.*
import org.tomlj.Toml

/** 模组图标上限 1 MiB：超过上限的图片不当图标，避免把整包资源读进内存。 */
private const val modIconMaxBytes = 1024 * 1024

/** 声明文件（fabric.mod.json / mods.toml / mcmod.info）的读取上限。 */
private const val modIconDeclarationMaxBytes = 2 * 1024 * 1024

/** 兜底候选：assets/<命名空间>/icon.png → logo.png → pack.png → 根目录 icon.png。 */
private val modIconFallbacks =
    listOf(
        Regex("(?i)assets/[^/]+/icon\\.png"),
        Regex("(?i)assets/[^/]+/logo\\.png"),
        Regex("(?i)pack\\.png"),
        Regex("(?i)icon\\.png"),
    )

/** 读进内存的一张候选图标：原始字节 + 是否本来就是 PNG（是则原样缓存，否则重新编码）。 */
private class ModIconPayload(val bytes: ByteArray, val png: Boolean)

/**
 * 读取模组 JAR 自带的图标，缓存成 PNG 后返回缓存文件路径；没有可用图标时返回 null。
 *
 * 解析顺序（都在 JAR 内）：
 * 1. `fabric.mod.json` 的 `icon`：字符串，或「尺寸 → 路径」映射（取尺寸最大的一项）；
 * 2. `META-INF/mods.toml` / `META-INF/neoforge.mods.toml` 的 `logoFile`（遍历 `[[mods]]`，取第一个非空）；
 * 3. `mcmod.info` 的 `logoFile`；
 * 4. 兜底：`assets/<命名空间>/icon.png`、`assets/<命名空间>/logo.png`、`pack.png`、根目录 `icon.png`。
 *
 * 缓存位置：`<cacheRoot>/mod-icons/<sha256(JAR 绝对路径 + 长度 + 修改时间)>.png`；命中时只看缓存文件，
 * 不再打开 JAR。只读取声明与图片字节，从不加载类文件；任何异常都当作「没有图标」，不向调用方抛出。
 */
fun modIcon(jar: Path, cacheRoot: Path): Path? {
    return try {
        if (!jar.isRegularFile()) null
        else {
            val size = jar.fileSize()
            val modified = jar.getLastModifiedTime().toMillis()
            val directory = cacheRoot.resolve("mod-icons")
            val target = directory.resolve(modIconCacheKey(jar, size, modified) + ".png")
            if (
                target.isRegularFile() &&
                    !Files.isSymbolicLink(target) &&
                    target.fileSize() in 1L..modIconMaxBytes.toLong()
            )
                target
            else {
                val payload =
                    ZipFile(jar.toFile()).use { zip -> modIconPayload(zip) } ?: return null
                val image = ImageIO.read(ByteArrayInputStream(payload.bytes)) ?: return null
                // 1 MiB 的 PNG 也可能解出超大画布，尺寸异常时直接放弃。
                if (image.width !in 1..4096 || image.height !in 1..4096) return null
                directory.createDirectories()
                val staging = directory.resolve(".${UUID.randomUUID()}.tmp")
                try {
                    if (payload.png) staging.writeBytes(payload.bytes)
                    else if (!ImageIO.write(image, "png", staging.toFile())) return null
                    Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    staging.deleteIfExists()
                }
                target
            }
        }
    } catch (_: Exception) {
        null
    }
}

/** 缓存名只取决于 JAR 身份，替换同名文件（长度或修改时间变化）后自动换新缓存。 */
private fun modIconCacheKey(jar: Path, size: Long, modified: Long): String =
    MessageDigest.getInstance("SHA-256")
        .digest("${jar.toAbsolutePath().normalize()}\n$size\n$modified".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

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
    for (name in declared) iconPayload(zip, name)?.let { return it }
    for (pattern in modIconFallbacks) {
        for (entry in zip.entries().asSequence()) {
            if (entry.isDirectory || !pattern.matches(entry.name)) continue
            iconPayload(zip, entry.name)?.let { return it }
        }
    }
    return null
}

/** 读取声明文件文本；目录、空文件和超大文件都视为没有声明。 */
private fun textEntry(zip: ZipFile, name: String): String? {
    val entry = zip.getEntry(name) ?: return null
    if (entry.isDirectory || entry.size !in 1L..modIconDeclarationMaxBytes.toLong()) return null
    val bytes = zip.getInputStream(entry).use { it.readNBytes(modIconDeclarationMaxBytes + 1) }
    if (bytes.size !in 1..modIconDeclarationMaxBytes) return null
    return bytes.toString(Charsets.UTF_8).takeIf { it.isNotBlank() }
}

/** 声明里的相对路径先做安全裁剪；绝对路径、协议、`..` 一律不认。 */
private fun declaredIconName(value: String): String? {
    val name =
        value.trim().replace('\\', '/').removePrefix("./").removePrefix("/").takeIf { it.isNotBlank() }
            ?: return null
    if (name.length > 240 || "://" in name) return null
    if (name.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
    return name
}

/** JAR 里的路径大小写不总是与声明一致，精确匹配失败后按忽略大小写再找一次。 */
private fun iconEntry(zip: ZipFile, name: String): ZipEntry? =
    zip.getEntry(name)
        ?: zip.entries().asSequence().firstOrNull {
            !it.isDirectory && it.name.equals(name, ignoreCase = true)
        }

/** 读取单张候选图片：条目必须存在、非空、不超过 1 MiB；超过上限或读不出来都返回 null。 */
private fun iconPayload(zip: ZipFile, name: String): ModIconPayload? {
    val entry = iconEntry(zip, name) ?: return null
    if (entry.isDirectory || entry.size !in 1L..modIconMaxBytes.toLong()) return null
    val bytes = zip.getInputStream(entry).use { it.readNBytes(modIconMaxBytes + 1) }
    if (bytes.size !in 1..modIconMaxBytes) return null
    return ModIconPayload(bytes, entry.name.endsWith(".png", ignoreCase = true))
}

/** Fabric：`icon` 可以是字符串，也可以是「尺寸 → 路径」映射（取尺寸最大的一项）。 */
private fun fabricIcon(info: JsonObject?): String? {
    val icon: JsonElement = info?.get("icon")?.takeUnless { it.isJsonNull } ?: return null
    if (icon.isJsonPrimitive)
        return icon.takeIf { it.asJsonPrimitive.isString }?.asString?.takeIf { it.isNotBlank() }
    if (!icon.isJsonObject) return null
    return icon.asJsonObject
        .entrySet()
        .mapNotNull { (size, value) ->
            val path =
                value.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                    ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val rank = size.takeWhile { character -> character.isDigit() }.toIntOrNull() ?: 0
            rank to path
        }
        .maxByOrNull { it.first }
        ?.second
}

/** Forge / NeoForge：`logoFile` 在 `[[mods]]` 里，个别 JAR 放在顶层；取第一个非空值。 */
private val tomlLogoPattern =
    Regex("""logoFile\s*=\s*(?:"([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)

private fun tomlLogo(source: String): String? =
    tomlLogoPattern
        .findAll(source)
        .mapNotNull { match -> (match.groupValues[1].ifBlank { match.groupValues[2] }).takeIf { it.isNotBlank() } }
        .firstOrNull()

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
                            ?.toList() ?: listOf(json)
                    else -> emptyList()
                }
            entries
                .mapNotNull { value ->
                    value.takeIf { it.isJsonObject }
                        ?.asJsonObject
                        ?.get("logoFile")
                        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                        ?.asString
                }
                .firstOrNull { it.isNotBlank() }
        }
        .getOrNull()