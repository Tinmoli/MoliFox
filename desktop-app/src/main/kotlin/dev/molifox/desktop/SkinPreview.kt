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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import dev.molifox.core.SkinModelRenderer
import dev.molifox.core.SkinVariant
import dev.molifox.core.domain.skin.PlayerHeadReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
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
    val strings = LocalStrings.current
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
                    SkinModelRenderer
                        .render(
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
                        ).toComposeImageBitmap()
                }
        }
    Box(modifier, contentAlignment = Alignment.Center) {
        frame?.let {
            Image(
                it,
                if (headOnly) strings.t("dialogs.skin.previewHead") else strings.t("dialogs.skin.previewModel"),
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
    val strings = LocalStrings.current
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
            .semantics { contentDescription = strings.t("dialogs.skin.previewLabel") }
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
 * 显示皮肤正面与帽子叠加的头像；原图不可用时回退内置皮肤，不生成派生文件。
 * [revision] 让内容变更但路径不变的皮肤重新读取。
 */
@Composable
fun StaticSkinHead(
    skinFile: Path?,
    onFailure: (Throwable) -> Unit,
    revision: Int = 0,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val reader = remember { PlayerHeadReader() }
    val head by
        produceState<ImageBitmap?>(null, skinFile, revision) {
            val png = skinFile?.let { reader.front(it, 128).onFailure(onFailure).getOrNull() }
            value =
                withContext(Dispatchers.Default) {
                    runCatching {
                        val encoded =
                            png ?: SkinModelRenderer.renderHeadFront(defaultPlayerSkinBytes, 128)
                        encoded?.let { bytes ->
                            SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
                        }
                    }.onFailure(onFailure).getOrNull()
                }
        }
    Box(modifier, contentAlignment = Alignment.Center) {
        val bitmap = head
        if (bitmap != null) {
            Image(
                bitmap,
                strings.t("dialogs.skin.avatar"),
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None,
            )
        } else {
            Icon(
                Icons.Outlined.PersonOutline,
                strings.t("dialogs.skin.avatarPlaceholder"),
                Modifier.fillMaxSize(.45f),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
