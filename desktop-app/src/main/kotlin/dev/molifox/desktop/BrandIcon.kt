// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责左上角品牌图标的导入、校验、读取与恢复默认。

package dev.molifox.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO

/** 自定义图标上限：2 MB、边长 16–1024 像素；足够放在 22dp 的品牌位，也不会拖慢启动。 */
internal const val BRAND_ICON_MAX_BYTES = 2L * 1024 * 1024
private const val BRAND_ICON_MAX_EDGE = 1024
private const val BRAND_ICON_MIN_EDGE = 16
private const val BRAND_ICON_PNG = "brand-icon.png"
private const val BRAND_ICON_JPEG = "brand-icon.jpg"
private val brandIconFormats = setOf("png", "jpeg", "jpg")

/** 图标副本目录：与背景图片、字体同在 MoliFox/appearance 下，随数据目录一起备份。 */
private fun brandIconDirectory(dataDirectory: Path): Path =
    dataDirectory.resolve("appearance/branding").toAbsolutePath().normalize().also {
        Files.createDirectories(it)
    }

/**
 * 把用户选中的图片复制到 `MoliFox/appearance/branding/` 并返回可持久化的路径。
 *
 * 校验顺序：普通文件 → 不超过 2 MB → 能识别出 PNG/JPEG → 边长 16–1024 → 真正解码一次排除损坏文件。
 * 任一步失败都抛出带中文说明的 [IllegalArgumentException]，由设置页就地提示，不会让启动器崩溃。
 */
internal fun importBrandIcon(
    dataDirectory: Path,
    source: Path,
): Path {
    require(Files.isRegularFile(source)) { tr("dialogs.brandIcon.invalidFormat") }
    require(Files.size(source) in 1..BRAND_ICON_MAX_BYTES) { tr("dialogs.brandIcon.tooLarge") }
    val format = validateBrandIcon(source)
    return storeBrandIcon(dataDirectory, source, format)
}

private fun validateBrandIcon(source: Path): String =
    ImageIO.createImageInputStream(source.toFile()).use { input ->
        require(input != null) { tr("dialogs.brandIcon.unreadable") }
        val readers = ImageIO.getImageReaders(input)
        require(readers.hasNext()) { tr("dialogs.brandIcon.invalidFormat") }
        val reader = readers.next()
        try {
            reader.input = input
            val name = reader.formatName.lowercase()
            require(name in brandIconFormats) { tr("dialogs.brandIcon.invalidFormat") }
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            require(
                width in BRAND_ICON_MIN_EDGE..BRAND_ICON_MAX_EDGE &&
                    height in BRAND_ICON_MIN_EDGE..BRAND_ICON_MAX_EDGE,
            ) {
                tr("dialogs.brandIcon.sizeRange")
            }
            require(reader.read(0) != null) { tr("dialogs.brandIcon.corrupted") }
            name
        } finally {
            reader.dispose()
        }
    }

private fun storeBrandIcon(
    dataDirectory: Path,
    source: Path,
    format: String,
): Path {
    val directory = brandIconDirectory(dataDirectory)
    val target = directory.resolve(if (format == "png") BRAND_ICON_PNG else BRAND_ICON_JPEG)
    val temporary = Files.createTempFile(directory, "brand-", ".tmp")
    try {
        Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING)
        try {
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
    // 换格式后旧副本不再被引用，顺手清掉，目录里只留当前这一张。
    runCatching {
        Files.deleteIfExists(
            directory.resolve(if (format == "png") BRAND_ICON_JPEG else BRAND_ICON_PNG),
        )
    }
    return target
}

/** 删除启动器自己保存的图标副本；只处理 branding 目录下的固定文件名，手改配置不会波及别的文件。 */
internal fun removeBrandIcon(
    dataDirectory: Path,
    stored: String,
) {
    if (stored.isBlank()) return
    runCatching {
        val directory = dataDirectory.resolve("appearance/branding").toAbsolutePath().normalize()
        val file = Path.of(stored).toAbsolutePath().normalize()
        if (file.parent == directory &&
            file.fileName.toString() in setOf(BRAND_ICON_PNG, BRAND_ICON_JPEG)
        ) {
            Files.deleteIfExists(file)
        }
    }
}

/** 读取自定义图标；文件缺失、过大或解码失败都返回 null，由界面回退内置图标。 */
internal fun loadBrandIcon(path: String): ImageBitmap? =
    runCatching {
        val file = Path.of(path)
        if (!Files.isRegularFile(file)) return@runCatching null
        if (Files.size(file) !in 1..BRAND_ICON_MAX_BYTES) return@runCatching null
        org.jetbrains.skia.Image
            .makeFromEncoded(Files.readAllBytes(file))
            .toComposeImageBitmap()
    }.getOrNull()

/** 启动时检查已保存的自定义图标：返回 null 表示可用，否则是回退原因（用于记一行日志）。 */
internal fun brandIconProblem(path: String): String? {
    if (path.isBlank()) return null
    return runCatching {
        val file = Path.of(path)
        require(Files.isRegularFile(file)) { tr("dialogs.brandIcon.fileMissing") }
        require(Files.size(file) in 1..BRAND_ICON_MAX_BYTES) { tr("dialogs.brandIcon.sizeAbnormal") }
        val image = ImageIO.read(file.toFile()) ?: error(tr("dialogs.brandIcon.decodeFailed"))
        require(
            image.width in BRAND_ICON_MIN_EDGE..BRAND_ICON_MAX_EDGE &&
                image.height in BRAND_ICON_MIN_EDGE..BRAND_ICON_MAX_EDGE,
        ) {
            tr("dialogs.brandIcon.dimensionAbnormal")
        }
        null
    }.getOrElse { error ->
        error.message ?: error.javaClass.simpleName
    }
}

/**
 * 左上角品牌标识：用户自定义过就画自定义图片，否则用内置狐狸图标。
 *
 * 只在主窗口标题栏使用；标题栏图标、任务栏与准备窗口仍走 `icons/app/icon.png`。
 * 读取失败（文件被删、损坏）时静默回退，启动不会因此中断。
 */
@Composable
fun BrandMark(modifier: Modifier = Modifier.size(22.dp)) {
    val settings = LocalLauncherSettings.current
    val path = settings.brandIconPath
    val revision = settings.brandIconRevision
    var bitmap by remember(path, revision) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path, revision) {
        bitmap =
            if (path.isBlank()) {
                null
            } else {
                withContext(Dispatchers.IO) { loadBrandIcon(path) }
            }
    }
    val custom = bitmap
    if (custom != null) {
        Image(
            custom,
            contentDescription = null,
            modifier = modifier.clip(RoundedCornerShape(percent = 22)),
            contentScale = ContentScale.Fit,
        )
    } else {
        FoxMark(modifier)
    }
}
