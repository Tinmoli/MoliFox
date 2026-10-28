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
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage

@Composable
internal fun LocalContentThumbnail(
    path: Path,
    world: Boolean,
    modifier: Modifier = Modifier.size(32.dp),
) {
    val bitmap by
        produceState<ImageBitmap?>(null, path, world) {
            value =
                withContext(Dispatchers.IO) {
                    localContentIcon(path, world)?.let { bytes ->
                        runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }
                            .getOrNull()
                    }
                }
        }
    if (bitmap != null) Image(bitmap!!, null, modifier)
    else
        Icon(
            if (world) Icons.Outlined.Folder else Icons.Outlined.Image,
            null,
            modifier,
            tint = MaterialTheme.colorScheme.primary,
        )
}
