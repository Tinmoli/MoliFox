package dev.molifox.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import dev.molifox.core.LoaderType

private fun DrawScope.polygon(color: Color, vararg points: Pair<Float, Float>) {
    drawPath(
        Path().apply {
            moveTo(points[0].first, points[0].second)
            points.drop(1).forEach { lineTo(it.first, it.second) }
            close()
        },
        color,
    )
}

/** 启动器标志独立于玩家皮肤头像。 */
@Composable
fun FoxMark(modifier: Modifier = Modifier.size(36.dp)) {
    Canvas(modifier) {
        withTransform({ scale(size.width / 64, size.height / 64, Offset.Zero) }) {
            polygon(
                FoxOrange,
                7f to 10f,
                24f to 19f,
                40f to 19f,
                57f to 10f,
                53f to 43f,
                32f to 58f,
                11f to 43f,
            )
            polygon(Color(0xFFCB6539), 7f to 10f, 26f to 25f, 15f to 32f)
            polygon(Color(0xFFCB6539), 57f to 10f, 38f to 25f, 49f to 32f)
            polygon(Color(0xFFFFEED8), 12f to 35f, 29f to 39f, 32f to 53f, 17f to 44f)
            polygon(Color(0xFFFFEED8), 52f to 35f, 35f to 39f, 32f to 53f, 47f to 44f)
            drawCircle(Color(0xFF354334), 2.1f, Offset(21f, 32f))
            drawCircle(Color(0xFF354334), 2.1f, Offset(43f, 32f))
            polygon(Color(0xFF354334), 28f to 45f, 36f to 45f, 32f to 49f)
        }
    }
}

/** Three-dimensional head preview using the account's original texture and hat layer. */
@Composable
fun SkinHead(skin: ImageBitmap?, modifier: Modifier = Modifier) {
    SkinModelImage(skin, dev.molifox.core.SkinVariant.Classic, true, -25f, 12f, true, modifier)
}

/** Original bundled game/loader artwork; source and rights notices accompany these resources. */
@Composable
fun LoaderMark(loader: LoaderType, modifier: Modifier = Modifier.size(22.dp)) {
    val bitmap =
        remember(loader) {
            val file = "icons/loaders/${loader.name.lowercase()}.png"
            val bytes =
                checkNotNull(LoaderType::class.java.classLoader.getResourceAsStream(file)) {
                        "Missing bundled loader icon: $file"
                    }
                    .use { it.readBytes() }
            org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
        }
    Image(
        bitmap,
        loader.title,
        modifier,
        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
    )
}
