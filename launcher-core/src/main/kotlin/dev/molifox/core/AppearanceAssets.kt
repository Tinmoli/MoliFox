// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责背景图片、字体等个性化资源的导入与清理。

package dev.molifox.core

import java.awt.Font
import java.awt.FontFormatException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.reader

private const val MAX_BACKGROUND_FILE_BYTES = 12L * 1024 * 1024
private const val MAX_BACKGROUND_PIXELS = 20_000_000
private const val MAX_FONT_FILE_BYTES = 32L * 1024 * 1024

/** Keep a single validated background copy in portable launcher storage. */
fun importAppearanceBackground(
    dataDirectory: Path,
    source: Path,
): Path {
    require(source.isRegularFile() && source.fileSize() in 1..MAX_BACKGROUND_FILE_BYTES) {
        "背景图片最大 12 MB"
    }
    ImageIO.createImageInputStream(source.toFile()).use { input ->
        require(input != null) { "无法读取背景图片" }
        val readers = ImageIO.getImageReaders(input)
        require(readers.hasNext()) { "请选择 PNG 或 JPEG 图片" }
        val reader = readers.next()
        try {
            reader.input = input
            require(reader.formatName.lowercase() in listOf("png", "jpeg", "jpg")) {
                "请选择 PNG 或 JPEG 图片"
            }
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            require(width > 0 && height > 0 && width.toLong() * height <= MAX_BACKGROUND_PIXELS) {
                "背景图片不能超过 2000 万像素"
            }
            require(reader.read(0) != null) { "背景图片已损坏" }
        } finally {
            reader.dispose()
        }
    }
    return storeAppearanceBackground(dataDirectory, source)
}

private fun storeAppearanceBackground(
    dataDirectory: Path,
    source: Path,
): Path {
    val directory =
        LauncherDirectories(dataDirectory).backgrounds.apply { createStorageDirectory(this) }
    val destination = directory.resolve("background.img")
    if (destination.exists() && Files.isSameFile(source, destination)) return destination
    val temporary = Files.createTempFile(directory, "background-", ".tmp")
    try {
        Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING)
        try {
            Files.move(
                temporary,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
    return destination
}

/** Import a font by content hash, retaining the user's original file. */
fun importAppearanceFont(
    dataDirectory: Path,
    source: Path,
): LauncherFont {
    require(source.isRegularFile() && source.fileSize() in 1..MAX_FONT_FILE_BYTES) {
        "字体文件最大 32 MB"
    }
    val font =
        try {
            Files.newInputStream(source).use { Font.createFont(Font.TRUETYPE_FONT, it) }
        } catch (error: FontFormatException) {
            throw IllegalArgumentException("请选择有效的 TTF 或 OTF 字体", error)
        } catch (error: IOException) {
            throw IllegalArgumentException("请选择有效的 TTF 或 OTF 字体", error)
        }
    val hash = digest(source, "SHA-256")
    val directory = dataDirectory.resolve("appearance/fonts").createDirectories()
    val target = directory.resolve(hash.take(24) + ".font")
    if (!target.exists()) Files.copy(source, target)
    return LauncherFont(font.getFamily(java.util.Locale.getDefault()), target.toString())
}

fun removeAppearanceFont(
    dataDirectory: Path,
    font: LauncherFont,
) {
    val directory = dataDirectory.resolve("appearance/fonts").toAbsolutePath().normalize()
    val file = Path.of(font.path).toAbsolutePath().normalize()
    // A manually edited record never authorizes removal of a font outside our storage.
    if (file.parent == directory && file.name.matches(Regex("[a-f0-9]{24}\\.font"))) {
        Files.deleteIfExists(file)
    }
}
