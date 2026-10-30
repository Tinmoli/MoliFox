// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责远端资源缩略图的缓存与显示。

package dev.molifox.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage

@Composable
fun ResourceThumbnail(
    hit: ContentHit,
    actions: UiActions,
) {
    var bitmap by remember(hit.iconUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(hit.iconUrl) {
        val url = hit.iconUrl ?: return@LaunchedEffect
        try {
            bitmap =
                withContext(Dispatchers.IO) {
                    val image = SkiaImage.makeFromEncoded(actions.controller.network.thumbnail(url))
                    require(image.width <= 2048 && image.height <= 2048)
                    image.toComposeImageBitmap()
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
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
