// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责游戏截图的筛选、导出和回收。

package dev.molifox.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
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
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.reader

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
                "截图目录不是实例内的普通目录"
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
                "实例截图目录已改变，请刷新"
            }
            val file = safePath(root, safeFilename(screenshot.name))
            require(
                Files.isRegularFile(file, NOFOLLOW_LINKS) &&
                    file.fileSize() == screenshot.size &&
                    file.getLastModifiedTime().toMillis() == screenshot.modifiedMillis,
            ) {
                "截图已改变或被移除，请刷新"
            }
            require(file.toRealPath().startsWith(root.toRealPath())) { "截图指向目录外" }
            file
        }

    suspend fun thumbnail(
        screenshot: GameScreenshot,
        maximum: Int = 480,
    ): ByteArray =
        withContext(Dispatchers.IO) {
            require(maximum in 64..1024 && screenshot.previewable && screenshot.size <= maxBytes) {
                "此截图暂不支持预览"
            }
            val file = resolve(screenshot)
            require(dimensions(file) == (screenshot.width to screenshot.height)) { "截图尺寸已改变，请刷新" }
            ImageIO.createImageInputStream(file.toFile()).use { input ->
                val reader =
                    ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: error("截图无法解码")
                try {
                    reader.input = input
                    val sample =
                        ((maxOf(reader.getWidth(0), reader.getHeight(0)) + maximum - 1) / maximum)
                            .coerceAtLeast(1)
                    val parameter =
                        reader.defaultReadParam.apply { setSourceSubsampling(sample, sample, 0, 0) }
                    val image = reader.read(0, parameter) ?: error("截图无法解码")
                    ByteArrayOutputStream().use { output ->
                        ImageIO.write(image, "png", output)
                        output.toByteArray()
                    }
                } finally {
                    reader.dispose()
                }
            }
        }

    /** Never overwrite an existing export, and clean only files created by this call on failure. */
    suspend fun export(
        screenshots: List<GameScreenshot>,
        targetDirectory: Path,
    ): List<Path> =
        withContext(Dispatchers.IO) {
            require(
                screenshots.isNotEmpty() &&
                    screenshots.size <= 1000 &&
                    screenshots
                        .map { Triple(it.instanceId, it.directory, it.name) }
                        .distinct()
                        .size == screenshots.size,
            ) {
                "请选择 1–1000 张不同的截图"
            }
            val sources = screenshots.map { resolve(it) }
            val destination = targetDirectory.toAbsolutePath().normalize()
            require(screenshots.none { destination == Path.of(it.directory) }) { "请选择截图目录以外的导出位置" }
            require(!Files.isSymbolicLink(destination)) { "导出目录不能是符号链接" }
            destination.createDirectories()
            val written = mutableListOf<Path>()
            try {
                for ((i, source) in sources.withIndex()) {
                    coroutineContext.ensureActive()
                    resolve(screenshots[i])
                    var target = safePath(destination, source.name)
                    var suffix = 2
                    while (target.exists()) {
                        require(suffix <= 10000) { "导出目录存在过多重名文件" }
                        target =
                            safePath(
                                destination,
                                "${source.nameWithoutExtension} (${suffix++}).${source.extension}",
                            )
                    }
                    val staging =
                        safePath(destination, ".molifox-screenshot-${UUID.randomUUID()}.tmp")
                    try {
                        Files.copy(source, staging)
                        coroutineContext.ensureActive()
                        resolve(screenshots[i])
                        require(staging.fileSize() == screenshots[i].size) { "截图在导出时改变" }
                        Files.move(staging, target)
                        written.add(target)
                    } finally {
                        staging.deleteIfExists()
                    }
                }
                written.toList()
            } catch (failure: Throwable) {
                written.asReversed().forEach { runCatching { it.deleteIfExists() } }
                throw failure
            }
        }

    suspend fun remove(screenshots: List<GameScreenshot>) =
        withContext(Dispatchers.IO) {
            require(
                screenshots.isNotEmpty() &&
                    screenshots.size <= 1000 &&
                    screenshots
                        .map { Triple(it.instanceId, it.directory, it.name) }
                        .distinct()
                        .size == screenshots.size,
            ) {
                "请选择要移入回收站的截图"
            }
            screenshots.forEach { resolve(it) }
            var removed = 0
            try {
                for (screenshot in screenshots) {
                    coroutineContext.ensureActive()
                    store.platform.moveToTrash(resolve(screenshot))
                    removed++
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                throw java.io.IOException("已移入回收站 $removed 张，后续操作停止：${failure.message}", failure)
            }
        }
}
