// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 在当前包条目生命周期内展示包内图标或经已登记来源核对的项目头像。

package dev.molifox.desktop

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
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
    // read/onFailure 是 lambda 参数，每次重组都是新实例；用 rememberUpdatedState 持有最新值，
    // produceState 只随文件标识与 revision 重启，执行时读到最新回调。
    val currentRead by rememberUpdatedState(read)
    val currentOnFailure by rememberUpdatedState(onFailure)
    val bitmap by produceState<ImageBitmap?>(null, entry.path, entry.modified, revision) {
        val bytes = currentRead(entry.path)
        value =
            bytes?.let {
                withContext(Dispatchers.Default) {
                    runCatching { SkiaImage.makeFromEncoded(it).toComposeImageBitmap() }
                        .onFailure(currentOnFailure)
                        .getOrNull()
                }
            }
    }
    val image = bitmap
    if (image == null) {
        fallback()
    } else {
        Image(image, tr("pages.packs.thumb.local", entry.filename), modifier)
    }
}
