// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责用 ZipOutputStream 合成嵌入依赖测试夹具；不依赖任何真实 JAR 资源。

package dev.molifox.core

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes

internal fun jarBytes(entries: Map<String, ByteArray>): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
        entries.forEach { (name, content) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(content)
            zip.closeEntry()
        }
    }
    return output.toByteArray()
}

internal fun writeJar(
    path: Path,
    entries: Map<String, ByteArray>,
): Path {
    path.parent?.createDirectories()
    path.writeBytes(jarBytes(entries))
    return path
}

private fun jsonString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private fun jsonMap(values: Map<String, String>): String =
    values.entries.joinToString(",", "{", "}") { (key, value) ->
        jsonString(key) + ":" + jsonString(value)
    }

internal fun fabricBytes(
    id: String,
    version: String = "1.0",
    depends: Map<String, String> = emptyMap(),
    jars: List<String> = emptyList(),
    provides: List<String> = emptyList(),
    breaks: Map<String, String> = emptyMap(),
    conflicts: Map<String, String> = emptyMap(),
    withSchemaVersion: Boolean = true,
): ByteArray {
    val fields = mutableListOf<String>()
    if (withSchemaVersion) fields += "\"schemaVersion\":1"
    fields += "\"id\":" + jsonString(id)
    fields += "\"version\":" + jsonString(version)
    if (depends.isNotEmpty()) fields += "\"depends\":" + jsonMap(depends)
    if (breaks.isNotEmpty()) fields += "\"breaks\":" + jsonMap(breaks)
    if (conflicts.isNotEmpty()) fields += "\"conflicts\":" + jsonMap(conflicts)
    if (provides.isNotEmpty()) {
        fields += "\"provides\":" + provides.joinToString(",", "[", "]") { jsonString(it) }
    }
    if (jars.isNotEmpty()) {
        val entries = jars.joinToString(",", "[", "]") { "{\"file\":" + jsonString(it) + "}" }
        fields += "\"jars\":$entries"
    }
    return ("{" + fields.joinToString(",") + "}").toByteArray()
}

internal fun quiltBytes(
    id: String,
    version: String = "1.0",
    depends: List<Pair<String, String>> = emptyList(),
    jars: List<String> = emptyList(),
): ByteArray {
    val dependencyEntries =
        depends.joinToString(",", "[", "]") { (dependencyId, versions) ->
            val versionsJson =
                versions.split("||").joinToString(",", "[", "]") {
                    jsonString(it.trim())
                }
            "{\"id\":" + jsonString(dependencyId) + ",\"versions\":$versionsJson}"
        }
    val jarsJson = jars.joinToString(",", "[", "]") { jsonString(it) }
    val loader =
        "{" +
            "\"id\":" + jsonString(id) + "," +
            "\"version\":" + jsonString(version) + "," +
            "\"depends\":$dependencyEntries," +
            "\"jars\":$jarsJson}"
    return ("{\"schema_version\":1,\"quilt_loader\":$loader}").toByteArray()
}

/** Forge/NeoForge 的 JarJar 元数据条目。 */
internal data class JarJarEntry(
    val path: String,
    val group: String = "",
    val artifact: String = "",
    val range: String = "",
    val artifactVersion: String = "",
)

internal fun jarJarBytes(entries: List<JarJarEntry>): ByteArray {
    val jars =
        entries.joinToString(",", "[", "]") { entry ->
            val fields = mutableListOf<String>()
            fields += "\"path\":" + jsonString(entry.path)
            if (entry.group.isNotEmpty() && entry.artifact.isNotEmpty()) {
                fields +=
                    "\"identifier\":{\"group\":" + jsonString(entry.group) +
                    ",\"artifact\":" + jsonString(entry.artifact) + "}"
            }
            if (entry.range.isNotEmpty() || entry.artifactVersion.isNotEmpty()) {
                fields +=
                    "\"version\":{\"range\":" + jsonString(entry.range) +
                    ",\"artifactVersion\":" + jsonString(entry.artifactVersion) + "}"
            }
            "{" + fields.joinToString(",") + "}"
        }
    return ("{\"jars\":$jars}").toByteArray()
}

internal fun manifestBytes(embeddedDependency: String): ByteArray =
    "Manifest-Version: 1.0\r\nEmbedded-Dependencies-Mod: $embeddedDependency\r\n\r\n".toByteArray()

/** 按 72 字节换行规则把长值折成续行（行首空格），用于验证续行折叠。 */
internal fun foldedManifestBytes(
    embeddedDependency: String,
    foldAt: Int,
): ByteArray {
    val value = embeddedDependency
    val first = value.take(foldAt)
    val rest = value.drop(foldAt)
    return (
        "Manifest-Version: 1.0\r\n" +
            "Embedded-Dependencies-Mod: $first\r\n" +
            " $rest\r\n\r\n"
    ).toByteArray()
}

/** 逐层嵌套的 Fabric JAR：第 1 层最内，第 [layers] 层最外。 */
internal fun layeredJar(layers: Int): ByteArray {
    require(layers >= 1) { "层数必须为正" }
    var current: ByteArray = jarBytes(mapOf("fabric.mod.json" to fabricBytes("layer1")))
    for (level in 2..layers) {
        val inner = "layer${level - 1}.jar"
        current =
            jarBytes(
                mapOf(
                    "fabric.mod.json" to fabricBytes("layer$level", jars = listOf(inner)),
                    inner to current,
                ),
            )
    }
    return current
}

/** 用给出的字节写一个测试模组文件；返回 JAR 大小与修改时间，供构造 [ModFile]。 */
internal fun createModFile(
    directory: Path,
    baseName: String,
    bytes: ByteArray,
    enabled: Boolean,
): ModFile {
    Files.createDirectories(directory)
    val filename = if (enabled) baseName else "$baseName.disabled"
    val path = directory.resolve(filename)
    path.writeBytes(bytes)
    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
    return ModFile(
        filename = filename,
        size = attributes.size(),
        enabled = enabled,
        modified = attributes.lastModifiedTime().toMillis(),
    )
}
