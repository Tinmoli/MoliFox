// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责整合包内图标的读取与导出。

package dev.molifox.core

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.writeBytes

/** Artwork is optional pack metadata, not an executable override or an arbitrary network URL. */
internal suspend fun applyPackIcon(
    store: LauncherStore,
    instanceId: String,
    zip: ZipFile,
    manifest: PackManifest,
) {
    val candidates = linkedSetOf<String>()
    val metadata =
        findArchiveMetadata(
            zip,
            setOf("mcbbs.packmeta", "modpack.json", "manifest.json", "mmc-pack.json"),
        )
    for (path in metadata) {
        val prefix = path.substringBeforeLast('/', "").let { if (it.isBlank()) "" else "$it/" }
        val info = packJson(zip, path)
        for (key in listOf("icon", "logo")) {
            val value =
                info
                    ?.get(key)
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString
                    .orEmpty()
            if (value.isNotBlank() && value.length <= 240 && !value.contains("://")) {
                candidates += prefix + value
            }
        }
        if (path.endsWith("mmc-pack.json")) {
            val config = zip.getEntry(prefix + "instance.cfg")
            if (config != null && config.size in 1..(1024 * 1024)) {
                val text =
                    zip.getInputStream(config).use {
                        it.readNBytes(1024 * 1024 + 1).toString(Charsets.UTF_8)
                    }
                val key =
                    text
                        .lineSequence()
                        .firstOrNull { it.substringBefore('=').trim() == "iconKey" }
                        ?.substringAfter('=')
                        ?.trim()
                        ?.removeSurrounding("\"")
                        .orEmpty()
                if (key.matches(Regex("[a-zA-Z0-9_-]{1,100}"))) {
                    candidates += prefix + "icons/$key.png"
                    candidates += prefix + "$key.png"
                }
            }
        }
        candidates += prefix + "icon.png"
        candidates += prefix + "logo.png"
    }
    candidates += listOf("icon.png", "icon.jpg", "icon.jpeg", "logo.png")
    manifest.overridePrefixes.asReversed().forEach { prefix ->
        candidates += prefix + "icon.png"
        candidates += prefix + "PCL/Logo.png"
    }
    val entries =
        zip
            .entries()
            .asSequence()
            .filter { !it.isDirectory }
            .groupBy { it.name }
    val entry: ZipEntry =
        candidates.firstNotNullOfOrNull { candidate ->
            require(
                !candidate.startsWith('/') &&
                    '\\' !in candidate &&
                    candidate.split('/').none { it == ".." || it == "." },
            ) {
                "整合包图标路径无效"
            }
            entries[candidate]?.also { require(it.size == 1) { "整合包图标重复" } }?.single()
        } ?: return
    require(entry.size in 1..(2L * 1024 * 1024)) { "整合包图标超过 2 MiB 或为空" }
    val source = Files.createTempFile(store.temporaryDirectory, "pack-icon-", ".png")
    try {
        val bytes = zip.getInputStream(entry).use { it.readNBytes(2 * 1024 * 1024 + 1) }
        require(bytes.size <= 2 * 1024 * 1024) { "整合包图标过大" }
        source.writeBytes(bytes)
        InstanceIcons(store).setPackIcon(instanceId, source)
    } finally {
        source.deleteIfExists()
    }
}
