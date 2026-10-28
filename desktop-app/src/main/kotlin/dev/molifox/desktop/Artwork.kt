// 负责品牌图形与界面通用装饰。

package dev.molifox.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
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

/** 内置方块图标的统一入口：把资源图片直接放进布局，交给 Compose 默认方式绘制。 */
@Composable
internal fun BundledBlockIcon(
    resource: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(resource) { loadBundledImage(resource) }
    if (bitmap != null) Image(bitmap, description, modifier, contentScale = ContentScale.Fit)
    else Box(modifier)
}

private fun loadBundledImage(resource: String): ImageBitmap? = runCatching {
    val stream =
        LoaderType::class.java.classLoader.getResourceAsStream(resource) ?: return@runCatching null
    stream.use {
        org.jetbrains.skia.Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap()
    }
}
    .getOrNull()

/** 加载器标识使用随包内置的方块图片。 */
private fun LoaderType.blockIcon(): String =
    when (this) {
        LoaderType.Vanilla -> "icons/blocks/grass.png"
        LoaderType.Fabric -> "icons/blocks/fabric.png"
        LoaderType.Quilt -> "icons/blocks/quilt.png"
        LoaderType.Forge -> "icons/blocks/anvil.png"
        LoaderType.NeoForge -> "icons/blocks/neoforge.png"
    }

/** 内置的原版/加载器方块图标；图片随包分发，调用方无需再绘制。 */
@Composable
fun LoaderMark(loader: LoaderType, modifier: Modifier = Modifier.size(22.dp)) {
    BundledBlockIcon(loader.blockIcon(), loader.title, modifier)
}
