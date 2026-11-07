// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责远端资源缩略图的缓存与显示。

package dev.molifox.desktop

import androidx.collection.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.ContentHit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage

/** 缩略图缓存条目上限：一屏资源列表加滚动缓冲远用不到这么多。 */
private const val RESOURCE_THUMBNAIL_CACHE_ENTRIES = 64

/**
 * 远端资源图标的解码结果缓存，键是图标 URL。
 * 列表滚动、切换页面会让同一图标反复进入组合，不缓存就每次重新下载并解码。
 * 这是 UI 展示用的解码缓存，不是业务数据缓存，条目可随时按原流程重建；
 * 必须有界：浏览过的资源图标会持续变化，无界缓存会让内存随使用增长，
 * 超限按 LRU 逐出，被逐出的图标下次显示时重新下载解码即可。
 */
private val resourceThumbnailCache = LruCache<String, ImageBitmap>(RESOURCE_THUMBNAIL_CACHE_ENTRIES)

@Composable
fun ResourceThumbnail(
    hit: ContentHit,
    actions: UiActions,
) {
    // 命中缓存时直接用已解码位图，跳过下载与解码；仍按 iconUrl 记忆，换图标时重新取值。
    var bitmap by remember(hit.iconUrl) { mutableStateOf(hit.iconUrl?.let(resourceThumbnailCache::get)) }
    LaunchedEffect(hit.iconUrl) {
        val url = hit.iconUrl ?: return@LaunchedEffect
        if (bitmap != null) return@LaunchedEffect
        try {
            val decoded =
                withContext(Dispatchers.IO) {
                    val image = SkiaImage.makeFromEncoded(actions.controller.network.thumbnail(url))
                    require(image.width <= 2048 && image.height <= 2048)
                    image.toComposeImageBitmap()
                }
            resourceThumbnailCache.put(url, decoded)
            bitmap = decoded
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 解码失败不缓存，保持原有“下次进入组合时重试”的行为。
            bitmap = null
        }
    }
    Box(
        Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val loadedBitmap = bitmap
        if (loadedBitmap != null) {
            Image(loadedBitmap, null, Modifier.fillMaxSize())
        } else {
            Text(
                hit.title.take(1).uppercase(),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 21.sp,
            )
        }
    }
}
