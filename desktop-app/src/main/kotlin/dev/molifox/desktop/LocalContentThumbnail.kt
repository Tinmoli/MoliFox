// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 在资源包、光影包和存档列表中显示内容自带图标，损坏或缺失时显示统一占位图标。

package dev.molifox.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import dev.molifox.core.localContentIcon
import dev.molifox.core.shaderPackIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.skia.Image as SkiaImage

/**
 * 内容自带的图标（世界的 `icon.png` / 资源包的 `pack.png` / 光影包的预览图）。
 *
 * [shader] 为 true 时读取明确命名的包图标与预览图，不把普通光影纹理当头像。
 *
 * [modified] 是内容本身的最后修改时间（毫秒）：给出时把解码结果缓存起来——缓存键是图标来源的路径， 再按来源的「大小 + 修改时间」判断要不要重新解码，所以同一张 PNG
 * 滚动、切页时只解码一次， 世界重新截图或光影包被替换后也能拿到新图。`null` 表示不缓存（其他调用点保持原有行为）。 图标缺失、损坏或读不出来时渲染 [fallback]，默认是图片占位图标。
 */
@Composable
internal fun LocalContentThumbnail(
    path: Path,
    world: Boolean,
    modifier: Modifier = Modifier.size(32.dp),
    modified: Long? = null,
    shader: Boolean = false,
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
        remember(path, modified, shader) {
            mutableStateOf(
                if (modified == null) {
                    null
                } else {
                    LocalIconCache.peek(path, world, shader)
                },
            )
        }
    // 解码永远在 IO 线程；列表首帧先画占位图标，结果回来后再替换。
    LaunchedEffect(path, modified, shader) {
        val decoded =
            withContext(Dispatchers.IO) {
                if (modified == null) {
                    decodeLocalContentIcon(path, world, shader)
                } else {
                    LocalIconCache.load(path, world, shader)
                }
            }
        bitmap = decoded
    }
    val image = bitmap
    if (image != null) Image(image, null, modifier) else fallback()
}

/** 读一次图标并解码；读不出或解不出都返回 null。 */
private fun decodeLocalContentIcon(
    path: Path,
    world: Boolean,
    shader: Boolean,
): ImageBitmap? {
    val bytes = if (shader) shaderPackIcon(path) else localContentIcon(path, world)
    return bytes?.let {
        runCatching { SkiaImage.makeFromEncoded(it).toComposeImageBitmap() }.getOrNull()
    }
}

/** 内容图标的进程内缓存：键是内容路径，条目里存「来源的大小 + 修改时间 + 解码结果」。 `null` 结果同样缓存，避免每次进入列表都重新读盘；图标被替换后时间戳变化会自动重新解码。 */
private object LocalIconCache {
    private class Entry(
        val stamp: Long,
        val image: ImageBitmap?,
    )

    private const val MAX_ENTRIES = 256
    private const val LOAD_FACTOR = .75f
    private val entries =
        object : LinkedHashMap<String, Entry>(MAX_ENTRIES, LOAD_FACTOR, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Entry>?,
            ): Boolean = size > MAX_ENTRIES
        }

    private fun key(
        path: Path,
        world: Boolean,
        shader: Boolean,
    ): String =
        path.toString() +
            if (shader) {
                "|shader"
            } else if (world) {
                "|world"
            } else {
                "|pack"
            }

    /** 只读缓存里的解码结果，供首帧使用；可能比磁盘旧一次。 */
    fun peek(
        path: Path,
        world: Boolean,
        shader: Boolean,
    ): ImageBitmap? = synchronized(entries) { entries[key(path, world, shader)]?.image }

    /** 按来源的大小与修改时间决定是否复用缓存；需要重新解码时在 IO 线程完成。 目录世界看 `icon.png` 自身的时间戳，压缩包看压缩包本身。 */
    fun load(
        path: Path,
        world: Boolean,
        shader: Boolean,
    ): ImageBitmap? {
        val key = key(path, world, shader)
        val stamp = iconStamp(path, world)
        synchronized(entries) { entries[key] }?.let { entry ->
            if (entry.stamp == stamp) return entry.image
        }
        val image = decodeLocalContentIcon(path, world, shader)
        synchronized(entries) { entries[key] = Entry(stamp, image) }
        return image
    }

    /** 图标来源的「大小 + 修改时间」；不存在或读不到时用 -1 表示。 */
    private fun iconStamp(
        path: Path,
        world: Boolean,
    ): Long =
        runCatching {
            val target =
                if (world &&
                    Files.isDirectory(path)
                ) {
                    path.resolve("icon.png")
                } else {
                    path
                }
            val modified = Files.getLastModifiedTime(target).toMillis()
            val size = if (Files.isRegularFile(target)) Files.size(target) else 0L
            modified * 31 + size
        }.getOrDefault(-1L)
}

/** 光影包行的内置占位图标：没有预览图或预览图无法解码时使用。 */
@Composable
internal fun ShaderFallbackIcon(modifier: Modifier = Modifier.size(28.dp)) {
    Icon(
        Icons.Outlined.AutoAwesome,
        null,
        modifier,
        tint = MaterialTheme.colorScheme.primary,
    )
}
