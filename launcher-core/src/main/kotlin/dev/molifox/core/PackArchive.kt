// 负责整合包压缩文件的安全读取和展开。

package dev.molifox.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Read metadata without extracting it or trusting the ZIP's claimed uncompressed size alone. */
internal fun packJson(zip: ZipFile, name: String): JsonObject? {
    val entry = zip.getEntry(name) ?: return null
    require(!entry.isDirectory && entry.size in 1..8_388_608) { "整合包清单过大或为空：$name" }
    val bytes = zip.getInputStream(entry).use { it.readNBytes(8_388_609) }
    require(bytes.size <= 8_388_608) { "整合包清单过大：$name" }
    return try {
        JsonParser.parseString(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")).asJsonObject
    } catch (_: Exception) {
        error("整合包清单不是有效 JSON 对象：$name")
    }
}

/** Only one pack root is accepted; mcbbs.packmeta and its companion manifest may coexist. */
internal fun findArchiveMetadata(zip: ZipFile, names: Set<String>): List<String> =
    zip.entries()
        .asSequence()
        .filter {
            !it.isDirectory &&
                it.name.substringAfterLast('/') in names &&
                it.name.count { ch -> ch == '/' } <= 1 &&
                !(hasRootPackMetadata(zip) &&
                    '/' in it.name &&
                    it.name.substringBefore('/') in packPayloadFolders)
        }
        .map { it.name }
        .toList()
        .also { found ->
            require(found.distinct().size == found.size) { "整合包包含重复清单" }
        }

private val packPayloadFolders =
    setOf("overrides", "client-overrides", "server-overrides", "files", "minecraft", ".minecraft")

private fun hasRootPackMetadata(zip: ZipFile) =
    listOf(
            "modrinth.index.json",
            "molifox-instance.json",
            "mmc-pack.json",
            "manifest.json",
            "mcbbs.packmeta",
            "modpack.json",
        )
        .any { zip.getEntry(it) != null }

internal fun validatePackPayload(
    zip: ZipFile,
    prefix: String,
    root: Path,
    os: String,
): List<Pair<ZipEntry, String>> {
    val entries =
        validateArchive(zip, prefix, root).map { (entry, target) ->
            val relative = root.toAbsolutePath().normalize().relativize(target).joinToString("/")
            packPath(root, relative)
            entry to relative
        }
    fun key(path: String) = if (os == "windows") path.lowercase() else path
    val paths = entries.map { key(it.second) }
    val seen = paths.toSet()
    require(seen.size == paths.size) { "整合包内容目录包含重复文件" }
    for (path in paths) {
        var parent = path.substringBeforeLast('/', "")
        while (parent.isNotBlank()) {
            require(parent !in seen) { "整合包文件和目录冲突：$parent" }
            parent = parent.substringBeforeLast('/', "")
        }
    }
    return entries
}

internal fun zipEntryHash(zip: ZipFile, entry: ZipEntry): String {
    val hash = MessageDigest.getInstance("SHA-1")
    var read = 0L
    zip.getInputStream(entry).use { input ->
        val buffer = ByteArray(65536)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            read += count
            require(read <= entry.size) { "整合包条目实际大小不符：${entry.name}" }
            hash.update(buffer, 0, count)
        }
    }
    require(read == entry.size) { "整合包条目实际大小不符：${entry.name}" }
    return hash.digest().joinToString("") { "%02x".format(it) }
}

internal val importedPackExclusions =
    setOf(
        "assets",
        "libraries",
        "versions",
        "natives",
        "logs",
        "crash-reports",
        ".cache",
        "launcher_accounts.json",
        "launcher_profiles.json",
        "accounts.json",
        "hmclversion.cfg",
        "hmclver.cfg",
    )
