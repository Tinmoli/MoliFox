// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责整合包压缩文件的安全读取和展开。

package dev.molifox.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.molifox.core.i18n.I18n
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists

private const val MAX_PACK_MANIFEST_BYTES = 8_388_608
private const val MAX_EMBEDDED_PACK_BYTES = 4L * 1024 * 1024 * 1024
private const val EMBEDDED_COPY_BUFFER_BYTES = 64 * 1024
private val embeddedPackNames = setOf("modpack.mrpack", "modpack.zip")

/** Read metadata without extracting it or trusting the ZIP's claimed uncompressed size alone. */
internal fun packJson(
    zip: ZipFile,
    name: String,
): JsonObject? {
    val entry = zip.getEntry(name) ?: return null
    require(!entry.isDirectory && entry.size in 1..MAX_PACK_MANIFEST_BYTES) {
        I18n.t("packsA1.packImport.manifestTooLarge", name)
    }
    val bytes = zip.getInputStream(entry).use { it.readNBytes(8_388_609) }
    require(bytes.size <= MAX_PACK_MANIFEST_BYTES) { I18n.t("packsA1.packImport.manifestExceedsLimit", name) }
    return try {
        JsonParser.parseString(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")).asJsonObject
    } catch (_: Exception) {
        error(I18n.t("packsA1.packImport.manifestInvalidJson", name))
    }
}

/** Only one pack root is accepted; mcbbs.packmeta and its companion manifest may coexist. */
internal fun findArchiveMetadata(
    zip: ZipFile,
    names: Set<String>,
): List<String> =
    zip
        .entries()
        .asSequence()
        .filter {
            !it.isDirectory &&
                it.name.substringAfterLast('/') in names &&
                it.name.count { ch -> ch == '/' } <= 1 &&
                !(
                    hasRootPackMetadata(zip) &&
                        '/' in it.name &&
                        it.name.substringBefore('/') in packPayloadFolders
                )
        }.map { it.name }
        .toList()
        .also { found ->
            require(found.distinct().size == found.size) { I18n.t("packsA1.packImport.duplicateManifest") }
        }

private val packPayloadFolders =
    setOf("overrides", "client-overrides", "server-overrides", "files", "minecraft", ".minecraft")

internal fun hasRootPackMetadata(zip: ZipFile) =
    listOf(
        "modrinth.index.json",
        "molifox-instance.json",
        "mmc-pack.json",
        "manifest.json",
        "mcbbs.packmeta",
        "modpack.json",
    ).any { zip.getEntry(it) != null }

/** 外层压缩包里的整合包条目；只认根目录和一级目录，避免误读 overrides 内的同名文件。 */
internal data class EmbeddedPack(
    val entryName: String,
    val size: Long,
)

internal fun findEmbeddedPack(zip: ZipFile): EmbeddedPack? =
    zip
        .entries()
        .asSequence()
        .filter {
            !it.isDirectory &&
                it.name.count { ch -> ch == '/' } <= 1 &&
                it.name.substringAfterLast('/').lowercase() in embeddedPackNames
        }.filter { it.size in 1..MAX_EMBEDDED_PACK_BYTES }
        .sortedWith(compareBy({ it.name.count { ch -> ch == '/' } }, { it.name }))
        .firstOrNull()
        ?.let { EmbeddedPack(it.name, it.size) }

/**
 * 把外层包中的整合包条目解到临时文件；不解压启动器、overrides 或任何其他条目。
 * 调用方负责在导入/预览结束后删除返回值。
 */
internal fun extractEmbeddedPack(
    source: Path,
    temporaryDirectory: Path,
    embedded: EmbeddedPack,
): Path {
    temporaryDirectory.createDirectories()
    val suffix = if (embedded.entryName.endsWith(".zip", ignoreCase = true)) ".zip" else ".mrpack"
    val target = Files.createTempFile(temporaryDirectory, "pack-embedded-", suffix)
    try {
        ZipFile(source.toFile()).use { zip -> copyEmbeddedEntry(zip, target, embedded) }
        return target
    } catch (failure: IOException) {
        target.deleteIfExists()
        throw failure
    } catch (failure: IllegalArgumentException) {
        target.deleteIfExists()
        throw failure
    }
}

private fun copyEmbeddedEntry(
    zip: ZipFile,
    target: Path,
    embedded: EmbeddedPack,
) {
    val entry = requireEmbeddedEntry(zip, embedded)
    zip.getInputStream(entry).use { input ->
        Files.newOutputStream(target).use { output -> copyEmbeddedContent(input, output, entry.size, embedded) }
    }
}

private fun requireEmbeddedEntry(
    zip: ZipFile,
    embedded: EmbeddedPack,
): ZipEntry {
    val entry = zip.getEntry(embedded.entryName)
    require(entry != null && !entry.isDirectory && entry.size in 1..MAX_EMBEDDED_PACK_BYTES) {
        I18n.t("packsA1.packImport.embeddedPackMissing", embedded.entryName)
    }
    return entry
}

private fun copyEmbeddedContent(
    input: java.io.InputStream,
    output: java.io.OutputStream,
    expectedSize: Long,
    embedded: EmbeddedPack,
) {
    val buffer = ByteArray(EMBEDDED_COPY_BUFFER_BYTES)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= MAX_EMBEDDED_PACK_BYTES) {
            I18n.t("packsA1.packImport.embeddedPackTooLarge", embedded.entryName)
        }
        output.write(buffer, 0, count)
    }
    require(total == expectedSize) {
        I18n.t("packsA1.packImport.embeddedPackChanged", embedded.entryName)
    }
}

internal fun validatePackPayload(
    zip: ZipFile,
    prefix: String,
    root: Path,
    os: String,
): List<Pair<ZipEntry, String>> {
    val entries =
        validateArchive(zip, prefix, root).map { (entry, target) ->
            val relative =
                root
                    .toAbsolutePath()
                    .normalize()
                    .relativize(target)
                    .joinToString("/")
            packPath(root, relative)
            entry to relative
        }

    fun key(path: String) = if (os == "windows") path.lowercase() else path
    val paths = entries.map { key(it.second) }
    val seen = paths.toSet()
    require(seen.size == paths.size) { I18n.t("packsA1.packImport.duplicateFiles") }
    for (path in paths) {
        var parent = path.substringBeforeLast('/', "")
        while (parent.isNotBlank()) {
            require(parent !in seen) {
                I18n.t("packsA1.packImport.fileDirectoryConflictWithPath", parent)
            }
            parent = parent.substringBeforeLast('/', "")
        }
    }
    return entries
}

internal fun zipEntryHash(
    zip: ZipFile,
    entry: ZipEntry,
): String {
    val hash = MessageDigest.getInstance("SHA-1")
    var read = 0L
    zip.getInputStream(entry).use { input ->
        val buffer = ByteArray(65536)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            read += count
            require(read <= entry.size) { I18n.t("packsA1.packImport.entrySizeMismatch", entry.name) }
            hash.update(buffer, 0, count)
        }
    }
    require(read == entry.size) { I18n.t("packsA1.packImport.entrySizeMismatch", entry.name) }
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
