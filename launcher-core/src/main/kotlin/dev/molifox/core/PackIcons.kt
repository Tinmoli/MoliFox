// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责整合包内图标的读取与导出。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.writeBytes

/** 元数据里图标路径的长度上限：超过视为可疑字段，直接忽略。 */
private const val MAX_PACK_ICON_NAME_CHARS = 240

/** instance.cfg 的读取上限（1 MiB）：只用来找一行 iconKey。 */
private const val MAX_INSTANCE_CONFIG_BYTES = 1024L * 1024

/** 整合包图标大小上限（2 MiB）：为空或超过上限都按缺失处理。 */
private const val MAX_PACK_ICON_BYTES = 2L * 1024 * 1024

/** Artwork is optional pack metadata, not an executable override or an arbitrary network URL. */
internal suspend fun applyPackIcon(
    store: LauncherStore,
    instanceId: String,
    zip: ZipFile,
    manifest: PackManifest,
) {
    val candidates = collectPackIconCandidates(zip, manifest)
    val entry = findPackIconEntry(zip, candidates) ?: return
    require(entry.size in 1L..MAX_PACK_ICON_BYTES) { I18n.t("packsA2.packIcon.sizeOrEmpty") }
    writePackIcon(store, instanceId, zip, entry)
}

/** 按元数据、mmc-pack 配置与实例覆盖前缀的顺序收集候选路径。 */
private fun collectPackIconCandidates(
    zip: ZipFile,
    manifest: PackManifest,
): Set<String> {
    val candidates = linkedSetOf<String>()
    val metadata =
        findArchiveMetadata(
            zip,
            setOf("mcbbs.packmeta", "modpack.json", "manifest.json", "mmc-pack.json"),
        )
    for (path in metadata) {
        val prefix = path.substringBeforeLast('/', "").let { if (it.isBlank()) "" else "$it/" }
        addMetadataIconCandidates(zip, path, prefix, candidates)
        if (path.endsWith("mmc-pack.json")) addMmcPackIconCandidates(zip, prefix, candidates)
        candidates += prefix + "icon.png"
        candidates += prefix + "logo.png"
    }
    candidates += listOf("icon.png", "icon.jpg", "icon.jpeg", "logo.png")
    manifest.overridePrefixes.asReversed().forEach { prefix ->
        candidates += prefix + "icon.png"
        candidates += prefix + "PCL/Logo.png"
    }
    return candidates
}

/** 从 manifest 的 icon / logo 字段取候选路径。 */
private fun addMetadataIconCandidates(
    zip: ZipFile,
    path: String,
    prefix: String,
    candidates: MutableSet<String>,
) {
    val info = packJson(zip, path)
    for (key in listOf("icon", "logo")) {
        val value =
            info
                ?.get(key)
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
                .orEmpty()
        if (isPackIconReference(value)) candidates += prefix + value
    }
}

/** 名字合法（非空、不过长、不是 URL）才当作包内路径。 */
private fun isPackIconReference(value: String): Boolean =
    value.isNotBlank() && value.length <= MAX_PACK_ICON_NAME_CHARS && !value.contains("://")

/** mmc-pack 格式把图标名写在 instance.cfg 的 iconKey 里。 */
private fun addMmcPackIconCandidates(
    zip: ZipFile,
    prefix: String,
    candidates: MutableSet<String>,
) {
    val config = zip.getEntry(prefix + "instance.cfg") ?: return
    if (config.size !in 1L..MAX_INSTANCE_CONFIG_BYTES) return
    val text =
        zip.getInputStream(config).use {
            it.readNBytes(MAX_INSTANCE_CONFIG_BYTES.toInt() + 1).toString(Charsets.UTF_8)
        }
    val key =
        text
            .lineSequence()
            .firstOrNull { it.substringBefore('=').trim() == "iconKey" }
            ?.substringAfter('=')
            ?.trim()
            ?.removeSurrounding("\"")
            .orEmpty()
    if (!key.matches(Regex("[a-zA-Z0-9_-]{1,100}"))) return
    candidates += prefix + "icons/$key.png"
    candidates += prefix + "$key.png"
}

/** 按候选顺序找到第一个真实存在的图标；路径不合法或重名直接报错。 */
private fun findPackIconEntry(
    zip: ZipFile,
    candidates: Set<String>,
): ZipEntry? {
    val entries =
        zip
            .entries()
            .asSequence()
            .filter { !it.isDirectory }
            .groupBy { it.name }
    return candidates.firstNotNullOfOrNull { candidate ->
        require(isSafePackIconPath(candidate)) { I18n.t("packsA2.packIcon.pathInvalid") }
        entries[candidate]?.also { require(it.size == 1) { I18n.t("packsA2.packIcon.duplicate") } }?.single()
    }
}

/** 只接受包内相对路径：拒绝根路径、反斜杠与 .. / . 片段。 */
private fun isSafePackIconPath(candidate: String): Boolean =
    !candidate.startsWith('/') &&
        '\\' !in candidate &&
        candidate.split('/').none { it == ".." || it == "." }

/** 导出到临时文件后交给图标库；无论成败都删掉临时文件。 */
private suspend fun writePackIcon(
    store: LauncherStore,
    instanceId: String,
    zip: ZipFile,
    entry: ZipEntry,
) {
    val source = Files.createTempFile(store.temporaryDirectory, "pack-icon-", ".png")
    try {
        val bytes =
            zip.getInputStream(entry).use { it.readNBytes(MAX_PACK_ICON_BYTES.toInt() + 1) }
        require(bytes.size.toLong() <= MAX_PACK_ICON_BYTES) { I18n.t("packsA2.packIcon.tooLarge") }
        source.writeBytes(bytes)
        InstanceIcons(store).setPackIcon(instanceId, source)
    } finally {
        source.deleteIfExists()
    }
}
