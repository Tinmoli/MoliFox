// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 在当前包条目生命周期内展示包内图标或经已登记来源核对的项目头像。

package dev.molifox.desktop

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.molifox.core.LocalPackEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import org.jetbrains.skia.Image as SkiaImage

@Composable
internal fun InstalledPackThumbnail(
    entry: LocalPackEntry,
    revision: Int,
    read: suspend (Path) -> ByteArray?,
    onFailure: (Throwable) -> Unit,
    modifier: Modifier,
    fallback: @Composable () -> Unit,
) {
    val bitmap by produceState<ImageBitmap?>(null, entry.path, entry.modified, revision) {
        val bytes = read(entry.path)
        value =
            bytes?.let {
                withContext(Dispatchers.Default) {
                    runCatching { SkiaImage.makeFromEncoded(it).toComposeImageBitmap() }
                        .onFailure(onFailure)
                        .getOrNull()
                }
            }
    }
    val image = bitmap
    if (image == null) fallback() else Image(image, "本地包头像 ${entry.filename}", modifier)
}
