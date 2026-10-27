package dev.molifox.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.molifox.core.SkinModelRenderer
import dev.molifox.core.SkinVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage

private val defaultPlayerSkin: ImageBitmap by lazy {
    val bytes =
        checkNotNull(
                SkinVariant::class.java.classLoader.getResourceAsStream("icons/loaders/steve.png")
            )
            .use { it.readBytes() }
    SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
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
