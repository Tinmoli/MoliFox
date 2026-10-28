// 负责远端资源缩略图的缓存与显示。

package dev.molifox.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.ContentHit
import kotlinx.coroutines.*
import org.jetbrains.skia.Image as SkiaImage

@Composable
fun ResourceThumbnail(hit: ContentHit, actions: UiActions) {
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
        Modifier.size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) Image(bitmap!!, null, Modifier.fillMaxSize())
        else
            Text(
                hit.title.take(1).uppercase(),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 21.sp,
            )
    }
}
