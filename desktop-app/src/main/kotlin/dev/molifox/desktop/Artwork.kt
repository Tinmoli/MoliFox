// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责品牌图形与界面通用装饰。

package dev.molifox.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.molifox.core.LoaderType

/** 启动器标志与窗口/任务栏/安装包图标同源：内置的狐狸品牌图（透明背景，icons/app/icon.png）。 */
@Composable
fun FoxMark(modifier: Modifier = Modifier.size(36.dp)) {
    Image(
        painterResource("icons/app/icon.png"),
        contentDescription = null,
        modifier = modifier.clip(RoundedCornerShape(percent = 22)),
        contentScale = ContentScale.Fit,
    )
}

/** 内置方块图标的统一入口：把资源图片直接放进布局，交给 Compose 默认方式绘制。 */
@Composable
internal fun BundledBlockIcon(
    resource: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(resource) { loadBundledImage(resource) }
    if (bitmap != null) {
        Image(bitmap, description, modifier, contentScale = ContentScale.Fit)
    } else {
        Box(modifier)
    }
}

private fun loadBundledImage(resource: String): ImageBitmap? =
    runCatching {
        val stream =
            LoaderType::class.java.classLoader.getResourceAsStream(resource)
                ?: return@runCatching null
        stream.use {
            org.jetbrains.skia.Image
                .makeFromEncoded(it.readBytes())
                .toComposeImageBitmap()
        }
    }.getOrNull()

/** 加载器标识使用随包内置的方块图片。 */
private fun LoaderType.blockIcon(): String =
    when (this) {
        LoaderType.Vanilla -> "icons/blocks/grass.png"
        LoaderType.Fabric -> "icons/blocks/fabric.png"
        LoaderType.Quilt -> "icons/blocks/quilt.png"
        LoaderType.Forge -> "icons/blocks/anvil.png"
        LoaderType.NeoForge -> "icons/blocks/neoforge.png"
        // 新加载器使用各自项目的官方标识；LiteLoader/Cleanroom 只用于已有实例的列表徽标。
        LoaderType.LegacyFabric -> "icons/blocks/legacyfabric.png"
        LoaderType.OptiFine -> "icons/blocks/optifine.png"
        LoaderType.LiteLoader -> "icons/blocks/liteloader.png"
        LoaderType.Cleanroom -> "icons/blocks/cleanroom.png"
    }

/** 内置的原版/加载器方块图标；图片随包分发，调用方无需再绘制。 */
@Composable
fun LoaderMark(
    loader: LoaderType,
    modifier: Modifier = Modifier.size(22.dp),
) {
    BundledBlockIcon(loader.blockIcon(), loader.title, modifier)
}
