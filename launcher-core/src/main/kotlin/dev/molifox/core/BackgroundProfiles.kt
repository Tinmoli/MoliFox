// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.zip.ZipInputStream

const val DEFAULT_BACKGROUND = "@default"
const val NO_BACKGROUND = "@none"

fun LauncherSettings.effectiveBackground(dark: Boolean): String =
    (if (dark) darkBackgroundPath else lightBackgroundPath)
        ?: backgroundPath.takeIf { it.isNotBlank() }
        ?: DEFAULT_BACKGROUND

/** 背景媒体类型：未知值按图片处理，与设置校验保持一致。 */
fun LauncherSettings.effectiveBackgroundKind(dark: Boolean): String =
    (if (dark) darkBackgroundKind else lightBackgroundKind).takeIf { it in BACKGROUND_KINDS } ?: "image"

/** 当前主题选中的视频；只有类型为 video 且路径已填写时才返回。 */
fun LauncherSettings.effectiveVideo(dark: Boolean): String? =
    (if (dark) darkVideoPath else lightVideoPath)
        ?.takeIf { it.isNotBlank() }
        ?.takeIf { effectiveBackgroundKind(dark) == "video" }

private val defaultBackgroundNames = setOf("default-light.jpg", "default-dark.jpg")
private const val DEFAULT_ENTRY_BUFFER_BYTES = 8192
private const val MAX_DEFAULT_BACKGROUND_BYTES = 12L * 1024 * 1024

/** 保留已释放的旧版默认图片，只有缺图时才使用新版 JPEG。 */
internal fun defaultBackgroundFile(
    directory: Path,
    dark: Boolean,
): Path = defaultBackgroundFile(directory, if (dark) "default-dark.jpg" else "default-light.jpg")

private fun defaultBackgroundFile(
    directory: Path,
    name: String,
): Path {
    val legacy = directory.resolve(name.removeSuffix(".jpg") + ".png")
    return if (Files.isRegularFile(legacy, LinkOption.NOFOLLOW_LINKS)) legacy else directory.resolve(name)
}

/** 默认资源只补缺，不覆盖已有图；此处由资源服务在 IO 线程调用。 */
@Synchronized
internal fun ensureDefaultBackgrounds(dataDirectory: Path): Path {
    val directory = LauncherDirectories(dataDirectory).backgrounds.resolve("default")
    createStorageDirectory(directory)
    require(!Files.isSymbolicLink(directory)) { "Default background directory is a symbolic link" }
    if (defaultBackgroundsPresent(directory)) return directory
    val stream =
        AppearanceAssetService::class.java.getResourceAsStream("/META-INF/molifox/default-backgrounds.zip")
            ?: throw IOException("Default background resources are missing")
    ZipInputStream(stream).use { installDefaultBackgrounds(it, directory) }
    return directory
}

private fun defaultBackgroundsPresent(directory: Path): Boolean =
    defaultBackgroundNames.all {
        Files.isRegularFile(defaultBackgroundFile(directory, it), LinkOption.NOFOLLOW_LINKS)
    }

private fun installDefaultBackgrounds(
    zip: ZipInputStream,
    directory: Path,
) {
    while (true) {
        val entry = zip.nextEntry ?: break
        if (entry.name in defaultBackgroundNames && !entry.isDirectory) {
            installDefaultBackground(zip, directory, entry.name)
        }
    }
}

private fun installDefaultBackground(
    zip: ZipInputStream,
    directory: Path,
    name: String,
) {
    val target = defaultBackgroundFile(directory, name)
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return
    val temporary = Files.createTempFile(directory, "default-", ".part")
    try {
        Files.newOutputStream(temporary).use { copyDefaultBackground(zip, it) }
        publishDefaultBackground(temporary, target)
    } finally {
        Files.deleteIfExists(temporary)
    }
}

private fun copyDefaultBackground(
    zip: ZipInputStream,
    output: java.io.OutputStream,
) {
    val buffer = ByteArray(DEFAULT_ENTRY_BUFFER_BYTES)
    var total = 0L
    while (true) {
        val count = zip.read(buffer)
        if (count < 0) return
        total += count
        if (total > MAX_DEFAULT_BACKGROUND_BYTES) throw IOException("Default background exceeds size limit")
        output.write(buffer, 0, count)
    }
}

private fun publishDefaultBackground(
    temporary: Path,
    target: Path,
) {
    // 不使用 REPLACE_EXISTING，另一进程已经补齐时保留其文件。
    try {
        Files.move(temporary, target)
    } catch (_: java.nio.file.FileAlreadyExistsException) {
        // 同时启动的另一个资源读取者已经完成发布。
    }
}
