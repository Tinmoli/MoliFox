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
import dev.molifox.core.AppearanceAssetService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage

/**
 * 左上角品牌标识：用户自定义过就画自定义图片，否则用内置狐狸图标。
 *
 * 只在主窗口标题栏使用；标题栏图标、任务栏与准备窗口仍走 `icons/app/icon.png`。
 * 读取失败（文件被删、损坏）时静默回退，启动不会因此中断。
 */
@Composable
fun BrandMark(
    assets: AppearanceAssetService,
    modifier: Modifier = Modifier.size(22.dp),
) {
    val settings = LocalLauncherSettings.current
    val path = settings.brandIconPath
    val revision = settings.brandIconRevision
    var bitmap by remember(path, revision) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path, revision) {
        bitmap =
            if (path.isBlank()) {
                null
            } else {
                withContext(Dispatchers.IO) {
                    assets.readBrandIcon(path)?.let { bytes ->
                        runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
                    }
                }
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
