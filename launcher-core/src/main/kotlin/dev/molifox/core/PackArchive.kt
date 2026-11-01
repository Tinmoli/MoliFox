// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责整合包压缩文件的安全读取和展开。

package dev.molifox.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.molifox.core.i18n.I18n
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

private const val MAX_PACK_MANIFEST_BYTES = 8_388_608

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

private fun hasRootPackMetadata(zip: ZipFile) =
    listOf(
        "modrinth.index.json",
        "molifox-instance.json",
        "mmc-pack.json",
        "manifest.json",
        "mcbbs.packmeta",
        "modpack.json",
    ).any { zip.getEntry(it) != null }

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
