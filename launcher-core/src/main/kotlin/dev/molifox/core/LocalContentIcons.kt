// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 读取资源包、光影包和世界自带的 PNG 图标，限制大小和像素数，不解压归档到磁盘。

package dev.molifox.core

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

private const val MIN_PNG_ICON_BYTES = 24
private const val PNG_SIGNATURE_LENGTH = 8
private const val MAX_PNG_ICON_EDGE = 2048

fun localContentIcon(
    path: Path,
    world: Boolean,
): ByteArray? =
    runCatching {
        require(!path.isSymbolicLink())
        val name = if (world) "icon.png" else "pack.png"
        val bytes =
            if (path.isDirectory(NOFOLLOW_LINKS)) {
                val icon = path.resolve(name)
                require(
                    icon.isRegularFile(NOFOLLOW_LINKS) &&
                        !icon.isSymbolicLink() &&
                        icon.toRealPath().startsWith(path.toRealPath()) &&
                        icon.fileSize() in 24..MAXIMUM_ICON_BYTES.toLong(),
                )
                Files.newInputStream(icon).use { it.readNBytes(MAXIMUM_ICON_BYTES + 1) }
            } else {
                require(path.isRegularFile(NOFOLLOW_LINKS))
                ZipFile(path.toFile()).use { zip ->
                    val entry =
                        zip.getEntry(name)
                            ?: if (world) {
                                zip.entries().asSequence().take(10_000).firstOrNull {
                                    it.name.endsWith("/icon.png") &&
                                        zip.getEntry(
                                            it.name.removeSuffix("icon.png") + "level.dat",
                                        ) !=
                                        null
                                }
                            } else {
                                null
                            }
                    require(
                        entry != null &&
                            !entry.isDirectory &&
                            entry.size in 24..MAXIMUM_ICON_BYTES.toLong(),
                    )
                    zip.getInputStream(entry).use { it.readNBytes(MAXIMUM_ICON_BYTES + 1) }
                }
            }
        require(isBoundedPng(bytes))
        bytes
    }.getOrNull()

/**
 * 光影包自带的预览图：根目录明确的包图标与预览图，随后查找截图和 shaders 下明确命名的图标，
 * 取第一张可解码的图片；文件夹与 `.zip` 走同一顺序。没有预览图或图片损坏时返回 null，由界面回退到内置光影图标。
 *
 * 只读取单个候选文件的前 1 MB，不把归档解压到磁盘；候选数量有上限，避免异常包拖慢列表。
 */
fun shaderPackIcon(path: Path): ByteArray? =
    runCatching {
        require(!path.isSymbolicLink())
        if (path.isDirectory(NOFOLLOW_LINKS)) {
            val root = path.toRealPath()
            shaderIconCandidates(path)
                .firstNotNullOfOrNull { candidate ->
                    readCandidate(candidate, root)
                }
        } else {
            require(path.isRegularFile(NOFOLLOW_LINKS))
            ZipFile(path.toFile()).use { zip ->
                shaderIconEntryNames(zip)
                    .firstNotNullOfOrNull { entryName ->
                        val entry = zip.getEntry(entryName)
                        if (
                            entry == null ||
                            entry.isDirectory ||
                            entry.size !in MIN_PNG_ICON_BYTES..MAXIMUM_ICON_BYTES.toLong()
                        ) {
                            null
                        } else {
                            zip.getInputStream(entry).use {
                                validIconOrNull(it.readNBytes(MAXIMUM_ICON_BYTES + 1))
                            }
                        }
                    }
            }
        }
    }.getOrNull()

/** 光影包图标的候选文件名顺序；根目录截图按名称排序，`shaders/` 只取直接子文件。 */
private fun shaderIconCandidates(directory: Path): List<Path> {
    val preferred =
        SHADER_ICON_NAMES.map { directory.resolve(it) }
    val screenshots =
        runCatching {
            directory
                .listDirectoryEntries()
                .filter {
                    it.name.startsWith("screenshot", ignoreCase = true) &&
                        it.extension.equals("png", ignoreCase = true)
                }.sortedBy { it.name.lowercase() }
                .take(8)
        }.getOrDefault(emptyList())
    val shaderImages =
        runCatching {
            directory
                .resolve("shaders")
                .listDirectoryEntries()
                .filter {
                    it.isRegularFile(NOFOLLOW_LINKS) &&
                        it.name.lowercase() in SHADER_ICON_NAMES
                }.sortedBy { SHADER_ICON_NAMES.indexOf(it.name.lowercase()) }
        }.getOrDefault(emptyList())
    return preferred + screenshots + shaderImages
}

/** `.zip` 光影包里的候选条目名，顺序与 [shaderIconCandidates] 一致。 */
private fun shaderIconEntryNames(zip: ZipFile): List<String> {
    val entries =
        zip
            .entries()
            .asSequence()
            .take(20_000)
            .toList()
    val rootPngs =
        entries
            .filter {
                !it.isDirectory && it.name.indexOf(
                    '/',
                ) < 0 && it.name.endsWith(".png", true)
            }.map { it.name }
    val preferred =
        SHADER_ICON_NAMES.filter { name -> zip.getEntry(name) != null }
    val screenshots =
        rootPngs
            .filter { it.startsWith("screenshot", ignoreCase = true) }
            .sortedBy { it.lowercase() }
            .take(8)
    val shaderImages =
        entries
            .filter {
                !it.isDirectory &&
                    it.name.startsWith("shaders/", ignoreCase = true) &&
                    it.name.substringAfter("shaders/").let { rest ->
                        rest.lowercase() in SHADER_ICON_NAMES
                    }
            }.map { it.name }
            .sortedBy { SHADER_ICON_NAMES.indexOf(it.substringAfter('/').lowercase()) }
    return (preferred + screenshots + shaderImages).distinct()
}

/** 读取文件夹里的单个候选：必须是包内普通文件，且是尺寸受限的 PNG。 */
private fun readCandidate(
    candidate: Path,
    root: Path,
): ByteArray? =
    runCatching {
        val usable =
            candidate.isRegularFile(NOFOLLOW_LINKS) &&
                !candidate.isSymbolicLink() &&
                candidate.toRealPath().startsWith(root) &&
                candidate.fileSize() in 24..MAXIMUM_ICON_BYTES.toLong()
        if (!usable) {
            null
        } else {
            Files.newInputStream(candidate).use {
                validIconOrNull(it.readNBytes(MAXIMUM_ICON_BYTES + 1))
            }
        }
    }.getOrNull()

private fun validIconOrNull(bytes: ByteArray): ByteArray? = if (isBoundedPng(bytes)) bytes else null

/** PNG 头、大小和像素数都在可解码范围内；不依赖 ImageIO，核心测试可直接断言。 */
private fun isBoundedPng(bytes: ByteArray): Boolean {
    if (bytes.size !in MIN_PNG_ICON_BYTES..MAXIMUM_ICON_BYTES) return false
    if (!bytes.take(PNG_SIGNATURE_LENGTH).toByteArray().contentEquals(PNG_SIGNATURE)) return false
    val header = ByteBuffer.wrap(bytes, 16, 8)
    return header.int in 1..MAX_PNG_ICON_EDGE && header.int in 1..MAX_PNG_ICON_EDGE
}

private val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

private const val MAXIMUM_ICON_BYTES = 1024 * 1024

private val SHADER_ICON_NAMES = listOf("pack.png", "icon.png", "logo.png", "preview.png")
