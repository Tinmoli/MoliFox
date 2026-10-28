// 负责磨砂（毛玻璃）面板：把背后画面换成模糊版，再叠与卡片相同的 surface，合成结果不比卡片更暗或更亮。

package dev.molifox.desktop

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.molifox.core.LauncherSettings
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage

/** 背景快照的缩小倍数：整图按 1/8 缩小后模糊，绘制时再把对应区域放大回面板尺寸。 */
private const val SNAPSHOT_DIVISOR = 8

/** 模糊后的背景图缩略图（1/8、RGBA 像素）：只跟背景图片有关，可跨主题 / 背景浓度复用。 */
private class BlurredWallpaper(val pixels: IntArray, val width: Int, val height: Int)

/** 缩略图缓存：键为「背景路径 + 修订号 + 文件大小/修改时间」，只解码、缩小、模糊一次。 */
private object WallpaperCache {
    private val lock = Any()
    private var key = ""
    private var cached: BlurredWallpaper? = null

    fun get(path: String, revision: Long): BlurredWallpaper? {
        val file = File(path)
        val stamp =
            if (file.isFile) file.length().toString() + ":" + file.lastModified() else "missing"
        val current = path + "#" + revision + "#" + stamp
        synchronized(lock) {
            if (current == key) return cached
            val built = buildWallpaper(path)
            key = current
            cached = built
            return built
        }
    }
}

/** 解码背景图并生成模糊缩略图；任何一步失败都返回 null，由面板退化为普通半透明面板。 */
private fun buildWallpaper(path: String): BlurredWallpaper? {
    val file = File(path)
    if (!file.isFile) return null
    val source = runCatching { ImageIO.read(file) }.getOrNull() ?: return null
    if (source.width <= 0 || source.height <= 0) return null
    val width = max(1, source.width / SNAPSHOT_DIVISOR)
    val height = max(1, source.height / SNAPSHOT_DIVISOR)
    val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    scaled.createGraphics().apply {
        setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BILINEAR,
        )
        setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        drawImage(source, 0, 0, width, height, null)
        dispose()
    }
    val pixels = scaled.getRGB(0, 0, width, height, null, 0, width)
    // 半径按短边取比例：同一张图在任何窗口尺寸下模糊观感一致。
    boxBlur(pixels, width, height, max(2, min(width, height) / 24))
    return BlurredWallpaper(pixels, width, height)
}

/**
 * 把模糊缩略图合成成不透明快照：`底色 × (1-背景浓度) + 模糊图 × 背景浓度`， 也就是 LauncherBackdrop 在窗口上铺出来的那张画，只是模糊过。
 * 面板直接用它替换背后的画面，再叠一层 `surface`，合成结果与普通卡片完全一致，不会更暗或更亮。
 */
private fun compositeSnapshot(
    wallpaper: BlurredWallpaper,
    base: Color,
    opacity: Float,
): ImageBitmap? {
    val keep = 1f - opacity.coerceIn(0f, 1f)
    val take = 1f - keep
    val baseRed = base.red * 255f
    val baseGreen = base.green * 255f
    val baseBlue = base.blue * 255f
    val pixels = IntArray(wallpaper.pixels.size)
    for (index in pixels.indices) {
        val pixel = wallpaper.pixels[index]
        val alpha = (pixel ushr 24) and 0xFF
        val red = baseRed * keep + ((pixel ushr 16) and 0xFF) * take
        val green = baseGreen * keep + ((pixel ushr 8) and 0xFF) * take
        val blue = baseBlue * keep + (pixel and 0xFF) * take
        pixels[index] =
            (alpha shl 24) or
                (red.roundToInt().coerceIn(0, 255) shl 16) or
                (green.roundToInt().coerceIn(0, 255) shl 8) or
                blue.roundToInt().coerceIn(0, 255)
    }
    return encodeSnapshot(pixels, wallpaper.width, wallpaper.height)
}

/** 像素 → ImageBitmap：借助 PNG 编码回到 Compose 图元，与外观页加载背景走同一条 Skia 解码路径。 */
private fun encodeSnapshot(pixels: IntArray, width: Int, height: Int): ImageBitmap? {
    val image =
        BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
            setRGB(0, 0, width, height, pixels, 0, width)
        }
    val bytes =
        ByteArrayOutputStream().use { out ->
            ImageIO.write(image, "png", out)
            out.toByteArray()
        }
    return runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
}

/** 三遍方框模糊：接近高斯，且在 1/8 的缩略图上只需几毫秒（只在背景变化时执行）。 */
private fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int) {
    if (radius <= 0) return
    val scratch = IntArray(pixels.size)
    repeat(3) {
        blurPass(pixels, scratch, width, height, radius, horizontal = true)
        blurPass(scratch, pixels, width, height, radius, horizontal = false)
    }
}

/** 单遍一维模糊；边缘按最近像素重复，避免出现黑边。 */
private fun blurPass(
    src: IntArray,
    dst: IntArray,
    width: Int,
    height: Int,
    radius: Int,
    horizontal: Boolean,
) {
    val span = radius * 2 + 1
    for (y in 0 until height) {
        for (x in 0 until width) {
            var alpha = 0
            var red = 0
            var green = 0
            var blue = 0
            for (step in -radius..radius) {
                val sx = if (horizontal) (x + step).coerceIn(0, width - 1) else x
                val sy = if (horizontal) y else (y + step).coerceIn(0, height - 1)
                val pixel = src[sy * width + sx]
                alpha += (pixel ushr 24) and 0xFF
                red += (pixel ushr 16) and 0xFF
                green += (pixel ushr 8) and 0xFF
                blue += pixel and 0xFF
            }
            dst[y * width + x] =
                ((alpha / span) shl 24) or
                    ((red / span) shl 16) or
                    ((green / span) shl 8) or
                    (blue / span)
        }
    }
}

/** 只有开启玻璃效果且存在背景图时才需要磨砂快照；关掉玻璃或没有背景图时返回 null。 */
@Composable
private fun rememberFrostedBackdrop(settings: LauncherSettings, base: Color): ImageBitmap? {
    val path = if (settings.glassEnabled) settings.backgroundPath else ""
    val revision = settings.backgroundRevision
    val opacity = settings.backgroundOpacity.coerceIn(0f, 1f)
    // 底色或背景浓度变了只需重新合成缩略图（像素级混合），不会重新解码背景图。
    val state =
        produceState<ImageBitmap?>(null, path, revision, base, opacity) {
            value =
                if (path.isBlank()) null
                else
                    withContext(Dispatchers.IO) {
                        WallpaperCache.get(path, revision)?.let {
                            compositeSnapshot(it, base, opacity)
                        }
                    }
        }
    return state.value
}

/**
 * 把背景快照中「正好位于面板背后」的那一块画到面板里：面板按窗口坐标定位，背景按 ContentScale 规则
 * 缩放并居中对齐，两者相减就得到快照上的源区域。因此滚动、窗口缩放时取到的都是当前位置背后的画面。
 */
private fun DrawScope.drawBackdropRegion(
    bitmap: ImageBitmap,
    panelLeft: Float,
    panelTop: Float,
    panelSize: Size,
    windowWidth: Float,
    windowHeight: Float,
    fit: String,
    alignment: String,
) {
    val imageWidth = bitmap.width.toFloat()
    val imageHeight = bitmap.height.toFloat()
    if (imageWidth <= 0f || imageHeight <= 0f) return
    val scaleX: Float
    val scaleY: Float
    val offsetX: Float
    val offsetY: Float
    if (fit == "stretch") {
        scaleX = windowWidth / imageWidth
        scaleY = windowHeight / imageHeight
        offsetX = 0f
        offsetY = 0f
    } else {
        val scale =
            if (fit == "fit") min(windowWidth / imageWidth, windowHeight / imageHeight)
            else max(windowWidth / imageWidth, windowHeight / imageHeight)
        scaleX = scale
        scaleY = scale
        val freeX = windowWidth - imageWidth * scale
        val freeY = windowHeight - imageHeight * scale
        offsetX =
            when {
                alignment.endsWith("Left") -> 0f
                alignment.endsWith("Right") -> freeX
                else -> freeX / 2f
            }
        offsetY =
            when {
                alignment.startsWith("top") -> 0f
                alignment.startsWith("bottom") -> freeY
                else -> freeY / 2f
            }
    }
    // 面板像素 → 快照像素：(窗口坐标 - 图片偏移) / 显示缩放。
    val rawLeft = (panelLeft - offsetX) / scaleX
    val rawRight = (panelLeft + panelSize.width - offsetX) / scaleX
    val rawTop = (panelTop - offsetY) / scaleY
    val rawBottom = (panelTop + panelSize.height - offsetY) / scaleY
    // 「完整显示」时图片两侧可能留白：面板完全落在留白里就不画，避免把边缘像素拉成一条色带。
    if (rawRight <= 0f || rawLeft >= imageWidth || rawBottom <= 0f || rawTop >= imageHeight) return
    val left = rawLeft.coerceIn(0f, imageWidth)
    val right = rawRight.coerceIn(0f, imageWidth)
    val top = rawTop.coerceIn(0f, imageHeight)
    val bottom = rawBottom.coerceIn(0f, imageHeight)
    val srcLeft = left.roundToInt().coerceIn(0, bitmap.width - 1)
    val srcRight = right.roundToInt().coerceIn(srcLeft + 1, bitmap.width)
    val srcTop = top.roundToInt().coerceIn(0, bitmap.height - 1)
    val srcBottom = bottom.roundToInt().coerceIn(srcTop + 1, bitmap.height)
    // 快照像素 → 面板内的目标位置（保证放大后与背景完全对齐）。
    val dstLeft = (srcLeft * scaleX + offsetX - panelLeft).roundToInt()
    val dstTop = (srcTop * scaleY + offsetY - panelTop).roundToInt()
    val dstWidth = ((srcRight - srcLeft) * scaleX).roundToInt().coerceAtLeast(1)
    val dstHeight = ((srcBottom - srcTop) * scaleY).roundToInt().coerceAtLeast(1)
    drawImage(
        image = bitmap,
        srcOffset = IntOffset(srcLeft, srcTop),
        srcSize = IntSize(srcRight - srcLeft, srcBottom - srcTop),
        dstOffset = IntOffset(dstLeft, dstTop),
        dstSize = IntSize(dstWidth, dstHeight),
        filterQuality = FilterQuality.Low,
    )
}

/**
 * 磨砂面板：把面板背后的画面换成「同一张画、只是模糊过」，再叠与普通卡片完全相同的一层 `surface`。
 *
 * 合成顺序只有两步：不透明模糊快照（已含主题底色 × (1-背景浓度)）→ `colorScheme.surface`。 没有额外的不透明底、不会出现 「底色 +
 * 面板色」两层叠加，所以空栏与空卡片的有效颜色一致，差别只在模糊。 没有背景图、关闭玻璃效果、解码失败或 `surface` 本身不透明时只画 `surface`，与卡片完全相同。
 */
@Composable
fun FrostedPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 6.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val settings = LocalLauncherSettings.current
    val colors = MaterialTheme.colorScheme
    val snapshot = rememberFrostedBackdrop(settings, colors.background)
    val shape = RoundedCornerShape(cornerRadius)
    var placed by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val windowSize = LocalWindowInfo.current.containerSize
    Box(modifier.onGloballyPositioned { placed = it }.border(1.dp, hairline(), shape)) {
        // 背景层单独铺一层：只裁圆角背景，不裁内容，键盘焦点环与涟漪不会被切掉。
        Box(
            Modifier.matchParentSize().clip(shape).drawBehind {
                val image = snapshot
                val coordinates = placed
                // surface 不透明时模糊看不见，省掉一次贴图；其余情况用模糊快照替换背后画面。
                if (
                    image != null &&
                        colors.surface.alpha < 1f &&
                        coordinates != null &&
                        windowSize.width > 0 &&
                        windowSize.height > 0
                ) {
                    val origin = coordinates.positionInWindow()
                    drawBackdropRegion(
                        bitmap = image,
                        panelLeft = origin.x,
                        panelTop = origin.y,
                        panelSize = size,
                        windowWidth = windowSize.width.toFloat(),
                        windowHeight = windowSize.height.toFloat(),
                        fit = settings.backgroundFit,
                        alignment = settings.backgroundAlignment,
                    )
                }
                // 唯一的一层底色：surface 已带上「面板不透明度」，与卡片用同一份颜色。
                drawRect(colors.surface)
            }
        )
        Box(Modifier.padding(contentPadding), content = content)
    }
}
