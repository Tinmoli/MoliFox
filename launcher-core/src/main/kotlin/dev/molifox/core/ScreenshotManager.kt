// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责游戏截图的筛选、导出和回收。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.coroutines.coroutineContext
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.reader

private const val MIN_THUMBNAIL_SIZE = 64
private const val MAX_THUMBNAIL_SIZE = 1024
private const val MAX_SCREENSHOT_SELECTION = 1000
private const val MAX_EXPORT_SUFFIX = 10000

data class GameScreenshot(
    val instanceId: String,
    val name: String,
    val size: Long,
    val modifiedMillis: Long,
    val width: Int,
    val height: Int,
    val directory: String,
) {
    val previewable: Boolean
        get() = width > 0 && height > 0
}

/**
 * Uses the actual isolated/shared screenshot directory; selection is revalidated before mutation.
 */
class ScreenshotManager(
    private val store: LauncherStore,
) {
    private val maxBytes = 64L * 1024 * 1024

    private fun directory(instanceId: String): Path {
        val game = store.instancePath(instanceId).toAbsolutePath().normalize()
        val root = safePath(game, "screenshots")
        if (root.exists()) {
            require(root.isDirectory() && root.toRealPath().startsWith(game.toRealPath())) {
                I18n.t("core.screenshotManager.1")
            }
        }
        return root
    }

    suspend fun list(instanceId: String): List<GameScreenshot> =
        withContext(Dispatchers.IO) {
            val root = directory(instanceId)
            if (!root.exists()) return@withContext emptyList()
            Files.newDirectoryStream(root).use { paths ->
                paths
                    .mapNotNull { file ->
                        coroutineContext.ensureActive()
                        if (
                            !Files.isRegularFile(file, NOFOLLOW_LINKS) ||
                            Files.isSymbolicLink(file) ||
                            file.extension.lowercase() !in setOf("png", "jpg", "jpeg")
                        ) {
                            return@mapNotNull null
                        }
                        runCatching {
                            safeFilename(file.name)
                            val size = file.fileSize()
                            val dimensions = if (size in 1..maxBytes) dimensions(file) else 0 to 0
                            GameScreenshot(
                                instanceId,
                                file.name,
                                size,
                                file.getLastModifiedTime().toMillis(),
                                dimensions.first,
                                dimensions.second,
                                root.toRealPath().toString(),
                            )
                        }.getOrNull()
                    }.sortedByDescending { it.modifiedMillis }
            }
        }

    private fun dimensions(file: Path): Pair<Int, Int> =
        runCatching {
            ImageIO.createImageInputStream(file.toFile()).use { input ->
                val reader =
                    ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return 0 to 0
                try {
                    reader.input = input
                    val width = reader.getWidth(0)
                    val height = reader.getHeight(0)
                    val supportedFormat =
                        reader.formatName.lowercase() in setOf("png", "jpeg", "jpg")
                    val limitedSize =
                        width in 1..16384 &&
                            height in 1..16384 &&
                            width.toLong() * height <= 40_000_000
                    if (supportedFormat && limitedSize) {
                        width to height
                    } else {
                        0 to 0
                    }
                } finally {
                    reader.dispose()
                }
            }
        }.getOrDefault(0 to 0)

    suspend fun resolve(screenshot: GameScreenshot): Path =
        withContext(Dispatchers.IO) {
            val root = directory(screenshot.instanceId)
            require(root.exists() && root.toRealPath().toString() == screenshot.directory) {
                I18n.t("core.screenshotManager.2")
            }
            val file = safePath(root, safeFilename(screenshot.name))
            require(
                Files.isRegularFile(file, NOFOLLOW_LINKS) &&
                    file.fileSize() == screenshot.size &&
                    file.getLastModifiedTime().toMillis() == screenshot.modifiedMillis,
            ) {
                I18n.t("core.screenshotManager.3")
            }
            require(file.toRealPath().startsWith(root.toRealPath())) {
                I18n.t("diagA2.screenshotManager.outsideRoot")
            }
            file
        }

    suspend fun thumbnail(
        screenshot: GameScreenshot,
        maximum: Int = 480,
    ): ByteArray =
        withContext(Dispatchers.IO) {
            require(
                maximum in MIN_THUMBNAIL_SIZE..MAX_THUMBNAIL_SIZE &&
                    screenshot.previewable &&
                    screenshot.size <= maxBytes,
            ) {
                I18n.t("core.screenshotManager.4")
            }
            val file = resolve(screenshot)
            require(dimensions(file) == (screenshot.width to screenshot.height)) { I18n.t("core.screenshotManager.5") }
            ImageIO.createImageInputStream(file.toFile()).use { input ->
                val reader =
                    ImageIO.getImageReaders(input).asSequence().firstOrNull()
                        ?: error(I18n.t("core.screenshotManager.6"))
                try {
                    reader.input = input
                    val sample =
                        ((maxOf(reader.getWidth(0), reader.getHeight(0)) + maximum - 1) / maximum)
                            .coerceAtLeast(1)
                    val parameter =
                        reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) }
                    val image = reader.read(0, parameter) ?: error(I18n.t("core.screenshotManager.6"))
                    ByteArrayOutputStream().use { output ->
                        ImageIO.write(image, "png", output)
                        output.toByteArray()
                    }
                } finally {
                    reader.dispose()
                }
            }
        }

    private fun requireExportSelection(screenshots: List<GameScreenshot>) {
        require(
            screenshots.isNotEmpty() &&
                screenshots.size <= MAX_SCREENSHOT_SELECTION &&
                screenshots
                    .map { Triple(it.instanceId, it.directory, it.name) }
                    .distinct()
                    .size == screenshots.size,
        ) {
            I18n.t("core.screenshotManager.7")
        }
    }

    /** Never overwrite an existing export, and clean only files created by this call on failure. */
    suspend fun export(
        screenshots: List<GameScreenshot>,
        targetDirectory: Path,
    ): List<Path> =
        withContext(Dispatchers.IO) {
            requireExportSelection(screenshots)
            val sources = screenshots.map { resolve(it) }
            val destination = targetDirectory.toAbsolutePath().normalize()
            require(screenshots.none { destination == Path.of(it.directory) }) { I18n.t("core.screenshotManager.8") }
            require(!Files.isSymbolicLink(destination)) { I18n.t("core.screenshotManager.9") }
            destination.createDirectories()
            val written = mutableListOf<Path>()
            var completed = false
            try {
                copyExportedScreenshots(screenshots, sources, destination, written)
                completed = true
                written.toList()
            } finally {
                // 失败或取消时回收本次已写出的文件，成功时保持不动。
                if (!completed) written.asReversed().forEach { runCatching { it.deleteIfExists() } }
            }
        }

    private suspend fun copyExportedScreenshots(
        screenshots: List<GameScreenshot>,
        sources: List<Path>,
        destination: Path,
        written: MutableList<Path>,
    ) {
        for ((index, source) in sources.withIndex()) {
            coroutineContext.ensureActive()
            resolve(screenshots[index])
            var target = safePath(destination, source.name)
            var suffix = 2
            while (target.exists()) {
                require(suffix <= MAX_EXPORT_SUFFIX) { I18n.t("core.screenshotManager.10") }
                target =
                    safePath(
                        destination,
                        "${source.nameWithoutExtension} (${suffix++}).${source.extension}",
                    )
            }
            val staging = safePath(destination, ".molifox-screenshot-${UUID.randomUUID()}.tmp")
            try {
                Files.copy(source, staging)
                coroutineContext.ensureActive()
                resolve(screenshots[index])
                require(staging.fileSize() == screenshots[index].size) { I18n.t("core.screenshotManager.11") }
                Files.move(staging, target)
                written.add(target)
            } finally {
                staging.deleteIfExists()
            }
        }
    }

    private fun requireRemovalSelection(screenshots: List<GameScreenshot>) {
        require(
            screenshots.isNotEmpty() &&
                screenshots.size <= MAX_SCREENSHOT_SELECTION &&
                screenshots
                    .map { Triple(it.instanceId, it.directory, it.name) }
                    .distinct()
                    .size == screenshots.size,
        ) {
            I18n.t("core.screenshotManager.12")
        }
    }

    private fun removalFailure(
        removed: Int,
        failure: Exception,
    ): java.io.IOException = java.io.IOException(I18n.t("core.screenshotManager.13", removed, failure.message), failure)

    suspend fun remove(screenshots: List<GameScreenshot>) =
        withContext(Dispatchers.IO) {
            requireRemovalSelection(screenshots)
            screenshots.forEach { resolve(it) }
            var removed = 0
            try {
                for (screenshot in screenshots) {
                    coroutineContext.ensureActive()
                    store.platform.moveToTrash(resolve(screenshot))
                    removed++
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: java.io.IOException) {
                throw removalFailure(removed, failure)
            } catch (failure: IllegalStateException) {
                throw removalFailure(removed, failure)
            } catch (failure: IllegalArgumentException) {
                throw removalFailure(removed, failure)
            }
        }
}
