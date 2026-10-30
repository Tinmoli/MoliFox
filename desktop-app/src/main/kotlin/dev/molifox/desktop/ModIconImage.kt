// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 在列表生命周期内异步加载并展示模组图标。

package dev.molifox.desktop

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.key
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path
import org.jetbrains.skia.Image as SkiaImage

/** 已解码的模组图标：按「JAR 路径 + 修改时间」键控，最多保留 256 张，列表来回滚动不重复解码。 */
private object ModIconBitmaps {
    private val values =
        object : LinkedHashMap<String, ImageBitmap>(64, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) =
                size > 256
        }

    @Synchronized operator fun get(key: String): ImageBitmap? = values[key]

    @Synchronized
    fun put(
        key: String,
        value: ImageBitmap,
    ) {
        values[key] = value
    }
}

/**
 * 通过读取接口取得归档图标，后台解码；只复用有界内存图像，没有图标时使用占位。
 */
@Composable
internal fun rememberModIcon(
    jar: Path,
    modified: Long,
    readIcon: suspend (Path) -> ByteArray?,
    onFailure: (Throwable) -> Unit,
): ImageBitmap? {
    val key =
        remember(jar, modified) {
            jar.toAbsolutePath().normalize().toString() + "|" + modified
        }
    val image by
        produceState<ImageBitmap?>(ModIconBitmaps[key], key, readIcon) {
            if (value != null) return@produceState
            value =
                try {
                    readIcon(jar)?.let { bytes ->
                        withContext(Dispatchers.Default) {
                            SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: IllegalArgumentException) {
                    // Skia 解码失败（图片损坏/格式不支持）只影响这一个图标，退回占位图。
                    onFailure(failure)
                    null
                } catch (failure: IOException) {
                    // 读取归档内的图标时文件被占用或损坏；与解码失败同样只退回占位图。
                    onFailure(failure)
                    null
                } catch (failure: IllegalStateException) {
                    // 解码后转成 Compose 位图时画布/位图句柄已失效；同样只影响当前图标。
                    onFailure(failure)
                    null
                }
            value?.let { ModIconBitmaps.put(key, it) }
        }
    return image
}
