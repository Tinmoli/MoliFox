// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责账户头像和可拖动三维皮肤预览。

package dev.molifox.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.molifox.core.LauncherDirectories
import dev.molifox.core.SkinModelRenderer
import dev.molifox.core.SkinVariant
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage

/** 内置占位皮肤的原始字节；3D 回退与静态头像回退共用同一份，避免重复读资源。 */
private val defaultPlayerSkinBytes: ByteArray by lazy {
    checkNotNull(SkinVariant::class.java.classLoader.getResourceAsStream("icons/loaders/steve.png"))
        .use { it.readBytes() }
}

private val defaultPlayerSkin: ImageBitmap by lazy {
    SkiaImage.makeFromEncoded(defaultPlayerSkinBytes).toComposeImageBitmap()
}

@Composable
internal fun SkinModelImage(
    skin: ImageBitmap?,
    variant: SkinVariant,
    overlays: Boolean,
    yaw: Float,
    pitch: Float,
    headOnly: Boolean,
    modifier: Modifier,
) {
    val texture =
        skin?.takeIf {
            it.width >= 64 &&
                it.width % 64 == 0 &&
                (it.height == it.width || it.height * 2 == it.width)
        } ?: defaultPlayerSkin
    val pixels =
        remember(texture) {
            IntArray(texture.width * texture.height).also { texture.readPixels(it) }
        }
    val frame by
        produceState<ImageBitmap?>(null, texture, variant, overlays, yaw, pitch, headOnly) {
            value =
                withContext(Dispatchers.Default) {
                    SkinModelRenderer.render(
                            texture.width,
                            texture.height,
                            pixels,
                            variant,
                            overlays,
                            yaw,
                            pitch,
                            headOnly,
                            if (headOnly) 128 else 240,
                            if (headOnly) 128 else 320,
                        )
                        .toComposeImageBitmap()
                }
        }
    Box(modifier, contentAlignment = Alignment.Center) {
        frame?.let {
            Image(
                it,
                if (headOnly) "玩家 3D 皮肤头像" else "可旋转的 3D 皮肤模型",
                Modifier.fillMaxSize(),
                filterQuality = FilterQuality.None,
            )
        }
    }
}

/** Dragging rotates a textured six-part Minecraft model, including hat and outer layers. */
@Composable
fun SkinPreview(
    skin: ImageBitmap?,
    variant: SkinVariant,
    overlays: Boolean,
    modifier: Modifier = Modifier,
) {
    var yaw by remember(skin) { mutableFloatStateOf(-25f) }
    var pitch by remember(skin) { mutableFloatStateOf(12f) }
    SkinModelImage(
        skin,
        variant,
        overlays,
        yaw,
        pitch,
        false,
        modifier
            .semantics { contentDescription = "3D皮肤预览（拖动旋转）" }
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(skin) {
                detectDragGestures { change, movement ->
                    change.consume()
                    yaw = (yaw + movement.x * .65f) % 360f
                    pitch = (pitch + movement.y * .45f).coerceIn(-75f, 75f)
                }
            },
    )
}

/**
 * 开始页账户头像：一张静态正面图，皮肤正面 + 帽子/外层叠加，缓存于 `<数据目录>/icons/heads/`。 不做 3D
 * 旋转、不响应拖动，绘制时用最近邻放大保持像素直角；离线或皮肤不可用时回退到内置占位头像。 [revision] 是手动刷新计数：皮肤内容更新但地址不变时，靠它触发重新合成。
 */
@Composable
fun StaticSkinHead(
    skinFile: Path?,
    directories: LauncherDirectories,
    revision: Int = 0,
    modifier: Modifier = Modifier,
) {
    val head by
        produceState<ImageBitmap?>(null, skinFile, directories.root, revision) {
            value =
                withContext(Dispatchers.IO) {
                    runCatching {
                        // 先试账户皮肤，失败或没有皮肤时退回内置占位皮肤。
                        val cached =
                            skinFile?.let { file ->
                                SkinModelRenderer.cachedHeadFront(file, directories, 128)
                            }
                                ?: SkinModelRenderer.cachedHeadFront(
                                    defaultPlayerSkinBytes,
                                    directories,
                                    128,
                                )
                        cached?.let { head ->
                            SkiaImage.makeFromEncoded(Files.readAllBytes(head))
                                .toComposeImageBitmap()
                        }
                    }
                        .getOrNull()
                }
        }
    Box(modifier, contentAlignment = Alignment.Center) {
        val bitmap = head
        if (bitmap != null) {
            Image(
                bitmap,
                "玩家头像",
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None,
            )
        } else {
            Icon(
                Icons.Outlined.PersonOutline,
                "玩家头像占位",
                Modifier.fillMaxSize(.45f),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
