// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责背景图片、字体等个性化资源的导入与清理。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
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
private const val MAX_BRAND_ICON_BYTES = 2L * 1024 * 1024
private const val MIN_BRAND_ICON_EDGE = 16
private const val MAX_BRAND_ICON_EDGE = 1024
private const val BRAND_ICON_PNG = "brand-icon.png"
private const val BRAND_ICON_JPEG = "brand-icon.jpg"

/** Keep a single validated background copy in portable launcher storage. */
fun importAppearanceBackground(
    dataDirectory: Path,
    source: Path,
): Path {
    validateAppearanceBackground(source)
    return storeAppearanceBackground(dataDirectory, source)
}

private fun validateAppearanceBackground(source: Path) {
    require(source.isRegularFile() && source.fileSize() in 1..MAX_BACKGROUND_FILE_BYTES) {
        I18n.t("core.appearanceAssets.1")
    }
    ImageIO.createImageInputStream(source.toFile()).use { input ->
        require(input != null) { I18n.t("core.appearanceAssets.2") }
        val readers = ImageIO.getImageReaders(input)
        require(readers.hasNext()) { I18n.t("core.appearanceAssets.3") }
        val reader = readers.next()
        try {
            reader.input = input
            require(reader.formatName.lowercase() in listOf("png", "jpeg", "jpg")) {
                I18n.t("core.appearanceAssets.3")
            }
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            require(width > 0 && height > 0 && width.toLong() * height <= MAX_BACKGROUND_PIXELS) {
                I18n.t("core.appearanceAssets.4")
            }
            require(reader.read(0) != null) { I18n.t("core.appearanceAssets.5") }
        } finally {
            reader.dispose()
        }
    }
}

private fun storeAppearanceBackground(
    dataDirectory: Path,
    source: Path,
    name: String = "background.img",
): Path {
    val directory =
        LauncherDirectories(dataDirectory).backgrounds.apply { createStorageDirectory(this) }
    val destination = directory.resolve(name)
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
        I18n.t("core.appearanceAssets.6")
    }
    val font =
        try {
            Files.newInputStream(source).use { Font.createFont(Font.TRUETYPE_FONT, it) }
        } catch (error: FontFormatException) {
            throw IllegalArgumentException(I18n.t("core.appearanceAssets.7"), error)
        } catch (error: IOException) {
            throw IllegalArgumentException(I18n.t("core.appearanceAssets.7"), error)
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

/** UI 无关的外观资源边界：校验、复制、删除和读取都由 core 负责。 */
interface AppearanceAssetService {
    fun backgroundAccent(settings: LauncherSettings, dark: Boolean): String? =
        readModeBackground(settings, dark)?.let(::extractBackgroundAccent)

    fun importBackground(source: Path): Path

    fun importModeBackground(source: Path, dark: Boolean): Path = importBackground(source)

    fun readModeBackground(settings: LauncherSettings, dark: Boolean): ByteArray? =
        readBackground(settings.effectiveBackground(dark))

    fun removeBackground()

    fun readBackground(path: String): ByteArray?

    fun importFont(source: Path): LauncherFont

    fun removeFont(font: LauncherFont)

    fun importBrandIcon(source: Path): Path

    fun removeBrandIcon(stored: String)

    fun readBrandIcon(path: String): ByteArray?

    fun brandIconProblem(path: String): String?
}

internal class FileAppearanceAssetService(
    private val dataDirectory: Path,
) : AppearanceAssetService {
    override fun importBackground(source: Path): Path = importAppearanceBackground(dataDirectory, source)

    override fun importModeBackground(source: Path, dark: Boolean): Path {
        validateAppearanceBackground(source)
        return storeAppearanceBackground(dataDirectory, source, if (dark) "background-dark.img" else "background-light.img")
    }

    override fun readModeBackground(settings: LauncherSettings, dark: Boolean): ByteArray? {
        val defaults = try {
            ensureDefaultBackgrounds(dataDirectory)
        } catch (failure: IOException) {
            java.util.logging.Logger.getLogger("MoliFox.appearance").warning("Default backgrounds: ${failure.message}")
            null
        } catch (failure: IllegalArgumentException) {
            java.util.logging.Logger.getLogger("MoliFox.appearance").warning("Default backgrounds: ${failure.message}")
            null
        }
        val selected = settings.effectiveBackground(dark)
        return when (selected) {
            NO_BACKGROUND -> null
            DEFAULT_BACKGROUND -> defaults?.let { readBackground(defaultBackgroundFile(it, dark).toString()) }
            else -> readBackground(selected)
        }
    }

    override fun removeBackground() {
        Files.deleteIfExists(LauncherDirectories(dataDirectory).backgrounds.resolve("background.img"))
    }

    override fun readBackground(path: String): ByteArray? = readAppearanceBytes(path, MAX_BACKGROUND_FILE_BYTES)

    override fun importFont(source: Path): LauncherFont = importAppearanceFont(dataDirectory, source)

    override fun removeFont(font: LauncherFont) = removeAppearanceFont(dataDirectory, font)

    override fun importBrandIcon(source: Path): Path {
        require(source.isRegularFile()) {
            I18n.t("dialogs.brandIcon.invalidFormat")
        }
        require(source.fileSize() in 1..MAX_BRAND_ICON_BYTES) { I18n.t("dialogs.brandIcon.tooLarge") }
        val format = validateBrandIcon(source)
        val directory =
            dataDirectory
                .resolve("appearance/branding")
                .toAbsolutePath()
                .normalize()
                .createDirectories()
        val target = directory.resolve(if (format == "png") BRAND_ICON_PNG else BRAND_ICON_JPEG)
        val temporary = Files.createTempFile(directory, "brand-", ".tmp")
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING)
            moveAppearanceAsset(temporary, target)
        } finally {
            temporary.deleteIfExists()
        }
        val obsolete = directory.resolve(if (format == "png") BRAND_ICON_JPEG else BRAND_ICON_PNG)
        runCatching { obsolete.deleteIfExists() }
        return target
    }

    override fun removeBrandIcon(stored: String) {
        if (stored.isBlank()) return
        runCatching {
            val directory = dataDirectory.resolve("appearance/branding").toAbsolutePath().normalize()
            val file = Path.of(stored).toAbsolutePath().normalize()
            if (file.parent == directory && file.name in setOf(BRAND_ICON_PNG, BRAND_ICON_JPEG)) {
                file.deleteIfExists()
            }
        }
    }

    override fun readBrandIcon(path: String): ByteArray? = readAppearanceBytes(path, MAX_BRAND_ICON_BYTES)

    override fun brandIconProblem(path: String): String? {
        if (path.isBlank()) return null
        return runCatching {
            val file = Path.of(path)
            require(file.isRegularFile()) { I18n.t("dialogs.brandIcon.fileMissing") }
            require(file.fileSize() in 1..MAX_BRAND_ICON_BYTES) { I18n.t("dialogs.brandIcon.sizeAbnormal") }
            val image = ImageIO.read(file.toFile()) ?: error(I18n.t("dialogs.brandIcon.decodeFailed"))
            require(image.width in MIN_BRAND_ICON_EDGE..MAX_BRAND_ICON_EDGE) {
                I18n.t("dialogs.brandIcon.dimensionAbnormal")
            }
            require(image.height in MIN_BRAND_ICON_EDGE..MAX_BRAND_ICON_EDGE) {
                I18n.t("dialogs.brandIcon.dimensionAbnormal")
            }
            null
        }.getOrElse { failure -> failure.message ?: failure.javaClass.simpleName }
    }
}

private fun validateBrandIcon(source: Path): String =
    ImageIO.createImageInputStream(source.toFile()).use { input ->
        require(input != null) { I18n.t("dialogs.brandIcon.unreadable") }
        val readers = ImageIO.getImageReaders(input)
        require(readers.hasNext()) { I18n.t("dialogs.brandIcon.invalidFormat") }
        val reader = readers.next()
        try {
            reader.input = input
            val format = reader.formatName.lowercase()
            require(format in setOf("png", "jpeg", "jpg")) { I18n.t("dialogs.brandIcon.invalidFormat") }
            require(reader.getWidth(0) in MIN_BRAND_ICON_EDGE..MAX_BRAND_ICON_EDGE) {
                I18n.t("dialogs.brandIcon.sizeRange")
            }
            require(reader.getHeight(0) in MIN_BRAND_ICON_EDGE..MAX_BRAND_ICON_EDGE) {
                I18n.t("dialogs.brandIcon.sizeRange")
            }
            require(reader.read(0) != null) { I18n.t("dialogs.brandIcon.corrupted") }
            format
        } finally {
            reader.dispose()
        }
    }

private fun readAppearanceBytes(
    stored: String,
    maximumBytes: Long,
): ByteArray? =
    runCatching {
        val file = Path.of(stored)
        if (!file.isRegularFile() || file.fileSize() !in 1..maximumBytes) return@runCatching null
        Files.readAllBytes(file)
    }.getOrNull()

private fun moveAppearanceAsset(
    source: Path,
    target: Path,
) {
    try {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
    }
}
