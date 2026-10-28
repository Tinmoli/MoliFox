// 在资源包和存档列表中显示内容自带图标，损坏或缺失时显示统一占位图标。

package dev.molifox.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import dev.molifox.core.localContentIcon
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage

/**
 * 内容自带的图标（世界的 `icon.png` / 资源包的 `pack.png`）。
 *
 * [modified] 是内容本身的最后修改时间（毫秒）：给出时把解码结果缓存起来——缓存键是图标文件的路径，
 * 再按图标文件自己的修改时间判断要不要重新解码，所以同一张 PNG 滚动、切页时只解码一次，
 * 世界重新截图后也能拿到新图。`null` 表示不缓存（其他调用点保持原有行为）。
 * 图标缺失、损坏或读不出来时渲染 [fallback]，默认是文件夹 / 图片占位图标。
 */
@Composable
internal fun LocalContentThumbnail(
    path: Path,
    world: Boolean,
    modifier: Modifier = Modifier.size(32.dp),
    modified: Long? = null,
    fallback: @Composable () -> Unit = {
        Icon(
            if (world) Icons.Outlined.Folder else Icons.Outlined.Image,
            null,
            modifier,
            tint = MaterialTheme.colorScheme.primary,
        )
    },
) {
    // 命中缓存时首帧直接画出来，不再先闪一下占位图标。
    var bitmap by
        remember(path, modified) { mutableStateOf(if (modified == null) null else LocalIconCache.peek(path)) }
    LaunchedEffect(path, modified) {
        val decoded =
            withContext(Dispatchers.IO) {
                if (modified == null) decodeLocalContentIcon(path, world)
                else LocalIconCache.load(path, world)
            }
        bitmap = decoded
    }
    val image = bitmap
    if (image != null) Image(image, null, modifier) else fallback()
}

/** 读一次 `icon.png` / `pack.png` 并解码；读不出或解不出都返回 null。 */
private fun decodeLocalContentIcon(path: Path, world: Boolean): ImageBitmap? =
    localContentIcon(path, world)?.let { bytes ->
        runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
    }

/**
 * 内容图标的进程内缓存：键是图标文件路径，条目里存「图标文件修改时间 + 解码结果」。
 * `null` 结果同样缓存，避免每次进入列表都重新读盘；图标被替换后修改时间变化会自动重新解码。
 */
private object LocalIconCache {
    private class Entry(val modified: Long, val image: ImageBitmap?)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** 只读缓存里的解码结果，供首帧使用；可能比磁盘旧一次。 */
    fun peek(path: Path): ImageBitmap? = entries[path.toString()]?.image

    /**
     * 按图标文件的修改时间决定是否复用缓存；需要重新解码时在 IO 线程完成。
     * 目录世界看 `icon.png` 自身的修改时间，`.zip` 世界看压缩包的修改时间。
     */
    fun load(path: Path, world: Boolean): ImageBitmap? {
        val key = path.toString()
        val stamp = iconStamp(path, world)
        entries[key]?.let { entry ->
            if (entry.modified == stamp) return entry.image
        }
        val image = decodeLocalContentIcon(path, world)
        entries[key] = Entry(stamp, image)
        return image
    }

    /** 图标文件（或压缩包）的修改时间；不存在或读不到时用 -1 表示。 */
    private fun iconStamp(path: Path, world: Boolean): Long {
        val target =
            if (world && Files.isDirectory(path)) path.resolve("icon.png") else path
        return runCatching { Files.getLastModifiedTime(target).toMillis() }.getOrDefault(-1L)
    }
}