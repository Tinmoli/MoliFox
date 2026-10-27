package dev.molifox.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// 草方块配色：与 Minecraft 草方块同一套色系，按 16×16 像素网格手工绘制，不打包参考启动器素材。
private val grassTop = Color(0xFF5DA130)
private val grassTopDark = Color(0xFF4F8A2A)
private val grassTopLight = Color(0xFF6DB63C)
private val grassFringe = Color(0xFF4A7823)
private val dirtLeft = Color(0xFF7A5433)
private val dirtLeftDark = Color(0xFF6B492C)
private val dirtRight = Color(0xFF5E3F26)
private val dirtRightDark = Color(0xFF513520)
private val seamTop = Color(0xFF3F6B1F)
private val seamLeft = Color(0xFF54391F)
private val seamRight = Color(0xFF402A17)

/** 16×16 逻辑网格 ⇒ 等距立方体：顶面 16×8 菱形、左右两个半高侧面，硬边不模糊。 */
@Composable
fun GrassBlockIcon(modifier: Modifier = Modifier, size: Dp = 20.dp) {
    Canvas(modifier.size(size)) {
        val cell = this.size.minDimension / 16f
        fun px(x: Int, y: Int, w: Int = 1, h: Int = 1, color: Color) {
            if (w <= 0 || h <= 0) return
            drawRect(color, Offset(x * cell, y * cell), Size(w * cell, h * cell))
        }
        // 顶面菱形：第 r 行从 7-r 到 8+r
        for (r in 0 until 8) {
            val x0 = 7 - r
            val w = 2 + 2 * r
            px(x0, r, w, 1, grassTop)
            // 固定纹理，保证每次启动一致
            if (r % 3 == 1) px(x0 + 1, r, 1, 1, grassTopDark)
            if (r % 3 == 2) px(x0 + w - 2, r, 1, 1, grassTopLight)
        }
        // 左右侧面
        px(0, 8, 8, 8, dirtLeft)
        px(8, 8, 8, 8, dirtRight)
        // 侧面顶部的草叶垂盖（2-3 格不规则），这是草方块的关键特征
        listOf(0, 2, 4, 6).forEach { x -> px(x, 8, 2, if (x % 4 == 0) 3 else 2, grassFringe) }
        listOf(9, 11, 13, 15).forEach { x -> px(x, 8, 2, if (x % 4 == 1) 3 else 2, grassFringe) }
        // 侧面暗部与纹理
        px(0, 14, 8, 2, dirtLeftDark)
        px(8, 14, 8, 2, dirtRightDark)
        listOf(1 to 11, 3 to 12, 5 to 10, 2 to 13, 6 to 13).forEach { (x, y) ->
            px(x, y, 1, 1, dirtLeftDark)
        }
        listOf(9 to 12, 11 to 11, 13 to 13, 10 to 14, 14 to 12).forEach { (x, y) ->
            px(x, y, 1, 1, dirtRightDark)
        }
        // 面与面之间的暗缝
        px(0, 7, 16, 1, seamTop)
        px(7, 8, 1, 8, seamLeft)
        px(8, 8, 1, 8, seamRight)
    }
}