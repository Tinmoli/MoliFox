// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.reader

internal const val MAX_JSON_BYTES = 8L * 1024 * 1024

/**
 * 版本 JSON 的位置：优先同名文件；缺失时目录里只有一个 JSON 就用它；有多个时按文件名序
 * 挑第一个像版本清单的。这样文件夹改名、JSON 以版本 id 命名、目录里混入
 * 普通 JSON 都不会让识别失效。
 */
internal fun versionJsonFile(
    folder: Path,
    name: String,
): Path? {
    val exact = folder.resolve("$name.json")
    if (exact.isRegularFile()) return exact
    val jsonFiles = jsonFilesIn(folder)
    return when (jsonFiles.size) {
        0 -> null
        1 -> jsonFiles.first()
        else -> jsonFiles.firstOrNull { looksLikeVersionManifest(it) }
    }
}

private fun jsonFilesIn(folder: Path): List<Path> =
    runCatching {
        folder
            .listDirectoryEntries()
            .filter { it.isRegularFile() && it.name.endsWith(".json", true) }
            .sortedBy { it.name }
    }.getOrDefault(emptyList())

/**
 * 版本清单特征：主类、继承声明、jar 指向或库列表；模组写出的普通配置 JSON 不满足这些，
 * 超过大小上限的文件也不参与挑选（正式读取时会以相同上限拒绝）。
 */
private fun looksLikeVersionManifest(file: Path): Boolean =
    runCatching {
        if (file.fileSize() > MAX_JSON_BYTES) return@runCatching false
        val parsed = Files.newBufferedReader(file).use { reader -> JsonParser.parseReader(reader) }
        if (!parsed.isJsonObject) return@runCatching false
        val json = parsed.asJsonObject
        json.string("mainClass").isNotBlank() ||
            json.string("inheritsFrom").isNotBlank() ||
            json.string("jar").isNotBlank() ||
            (json.has("libraries") && json.get("libraries").isJsonArray)
    }.getOrDefault(false)

internal fun directoryTime(
    root: InstanceRoot,
    name: String,
): String =
    runCatching {
        Files
            .getLastModifiedTime(root.path.resolve("versions").resolve(name))
            .toInstant()
            .toString()
    }.getOrDefault("")
