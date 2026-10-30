// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 准备窗口的视觉组件：配色、字体、圆角面板/按钮/进度条与统计格，只用 Java2D。
package dev.molifox.bootstrap

import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import kotlin.math.roundToInt

/** 进度条的首选/最小宽度与扫动动画步长。 */
private const val PROGRESS_BAR_PREFERRED_WIDTH = 320
private const val PROGRESS_BAR_MINIMUM_WIDTH = 80
private const val INDETERMINATE_PHASE_STEP = 0.014f

/** 主/次按钮的字号、内边距与圆角。 */
private const val BUTTON_FONT_SIZE = 12
private const val BUTTON_PADDING_VERTICAL = 7
private const val BUTTON_PADDING_HORIZONTAL = 16
private const val BUTTON_RADIUS = 8

/** 统计格的标签/数值字号与两者间距。 */
private const val STAT_VALUE_FONT_SIZE = 13
private const val STAT_LABEL_FONT_SIZE = 11
private const val STAT_LABEL_VALUE_GAP = 3

/** 容量换算基准：1 KiB = 1024 B。 */
private const val BYTES_PER_KIB = 1024L

// ---------- 准备窗口的视觉令牌：与桌面端主题（樱花粉、浅色底、1dp 细边）对应 ----------

internal object BootstrapPalette {
    val background = Color(0xFBF7F9)
    val surface = Color(0xFFFFFF)
    val border = Color(0xEADFE5)
    val accent = Color(0xB94E7B)
    val accentHover = Color(0xC75C89)
    val accentPressed = Color(0xA64470)
    val track = Color(0xF2E6EC)
    val heading = Color(0xB94E7B)
    val onSurface = Color(0x3A333A)
    val muted = Color(0x6B646B)
    val faint = Color(0x948A92)
    val error = Color(0xB3384A)
    val errorFill = Color(0xFDF3F0)
    val errorBorder = Color(0xE8C4C4)
}

/** 与桌面端一致的中文字体：优先系统 UI 字体，找不到时退回逻辑字体（Swing 会自动补中文字形）。 */
private val bootstrapFontFamily: String by lazy {
    val preferred =
        listOf(
            "Microsoft YaHei UI",
            "Microsoft YaHei",
            "PingFang SC",
            "Noto Sans CJK SC",
            "Source Han Sans SC",
            "WenQuanYi Micro Hei",
        )
    val available =
        runCatching {
            java.awt.GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .availableFontFamilyNames
                .toSet()
        }.getOrDefault(emptySet())
    preferred.firstOrNull { it in available } ?: Font.SANS_SERIF
}

internal fun uiFont(
    size: Int,
    style: Int = Font.PLAIN,
) = Font(bootstrapFontFamily, style, size)

/** 圆角实色面板：白卡片、错误卡片与按钮共用同一套绘制方式（无新依赖，只用 Java2D）。 */
internal open class RoundedSurface(
    private val fill: Color,
    private val stroke: Color? = null,
    private val radius: Int = 12,
) : JPanel() {
    init {
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = fill
            g2.fillRoundRect(0, 0, width - 1, height - 1, radius, radius)
            if (stroke != null) {
                g2.color = stroke
                g2.drawRoundRect(0, 0, width - 1, height - 1, radius, radius)
            }
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

/** 8dp 圆角进度条：浅色底轨 + 主题色填充；还没有镜像/总量时画一段循环扫过的指示条。 */
internal class RoundedProgressBar : JComponent() {
    var progress: Float = 0f
    var indeterminate: Boolean = false
    private var phase = 0f
    private val barHeight = 8

    init {
        isOpaque = false
        preferredSize = Dimension(PROGRESS_BAR_PREFERRED_WIDTH, barHeight)
        minimumSize = Dimension(PROGRESS_BAR_MINIMUM_WIDTH, barHeight)
    }

    /** 由 250ms 的刷新定时器推动扫动动画；确定进度时不做多余重绘。 */
    fun tick() {
        if (!indeterminate) return
        phase = (phase + INDETERMINATE_PHASE_STEP) % 1f
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val height = barHeight.coerceAtMost(this.height).coerceAtLeast(1)
            val top = (this.height - height) / 2
            paintTrack(g2, height, top)
            val fillWidth = resolveFillWidth(height)
            if (fillWidth >= 1f) paintFill(g2, fillWidth, height, top)
        } finally {
            g2.dispose()
        }
    }

    private fun paintTrack(
        g2: Graphics2D,
        height: Int,
        top: Int,
    ) {
        g2.color = BootstrapPalette.track
        g2.fillRoundRect(0, top, width, height, height, height)
    }

    private fun resolveFillWidth(height: Int): Float {
        val fillWidth =
            if (indeterminate) {
                (width * 0.34f)
            } else {
                (width * progress.coerceIn(0f, 1f)).coerceAtLeast(
                    if (progress > 0f) {
                        height.toFloat()
                    } else {
                        0f
                    },
                )
            }
        return fillWidth
    }

    private fun paintFill(
        g2: Graphics2D,
        fillWidth: Float,
        height: Int,
        top: Int,
    ) {
        g2.clipRect(0, 0, width, this.height)
        val left =
            if (indeterminate) {
                -fillWidth + (width + fillWidth) * phase
            } else {
                0f
            }
        g2.color = BootstrapPalette.accent
        g2.fillRoundRect(
            left.roundToInt(),
            top,
            fillWidth.roundToInt(),
            height,
            height,
            height,
        )
    }
}

/** 与主界面主/次按钮同高（34dp）的圆角按钮：主按钮实色主题色，次按钮白底细边。 */
internal class RoundedButton(
    text: String,
    private val primary: Boolean,
) : JButton(text) {
    init {
        isFocusPainted = false
        isContentAreaFilled = false
        isBorderPainted = false
        isOpaque = false
        isRolloverEnabled = true
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = uiFont(BUTTON_FONT_SIZE, Font.BOLD)
        border =
            BorderFactory.createEmptyBorder(
                BUTTON_PADDING_VERTICAL,
                BUTTON_PADDING_HORIZONTAL,
                BUTTON_PADDING_VERTICAL,
                BUTTON_PADDING_HORIZONTAL,
            )
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val enabled = isEnabled
            val rollover = model.isRollover && enabled
            val pressed = model.isArmed && model.isPressed && enabled
            val fill =
                when {
                    !primary -> BootstrapPalette.surface
                    pressed -> BootstrapPalette.accentPressed
                    rollover -> BootstrapPalette.accentHover
                    else -> BootstrapPalette.accent
                }
            g2.color = fill
            g2.fillRoundRect(0, 0, width - 1, height - 1, BUTTON_RADIUS, BUTTON_RADIUS)
            g2.color =
                when {
                    !primary -> BootstrapPalette.border
                    !enabled -> BootstrapPalette.track
                    else -> fill
                }
            g2.drawRoundRect(0, 0, width - 1, height - 1, BUTTON_RADIUS, BUTTON_RADIUS)
        } finally {
            g2.dispose()
        }
        foreground =
            when {
                !isEnabled -> BootstrapPalette.faint
                primary -> Color.WHITE
                else -> BootstrapPalette.onSurface
            }
        super.paintComponent(g)
    }
}

/** 内置狐狸图标：标题栏、任务栏与左上角品牌图共用同一张 icons/app/icon.png；缺失时窗口仍可打开。 */
private val launcherIconBytes: ByteArray? by lazy {
    runCatching {
        BootstrapMainMarker::class
            .java
            .getResourceAsStream("/icons/app/icon.png")
            ?.use { it.readBytes() }
    }.getOrNull()
}

internal fun launcherIcon(size: Int): BufferedImage? {
    val bytes = launcherIconBytes ?: return null
    return runCatching {
        val source =
            ImageIO.read(java.io.ByteArrayInputStream(bytes)) ?: return@runCatching null
        val scaled = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = scaled.createGraphics()
        try {
            g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC,
            )
            g.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON,
            )
            g.drawImage(source, 0, 0, size, size, null)
        } finally {
            g.dispose()
        }
        scaled
    }.getOrNull()
}

/** 统计卡片里的一格：11sp 灰色标签 + 13sp 深色数值。 */
internal fun statValue(text: String): JLabel =
    JLabel(text).apply {
        font = uiFont(STAT_VALUE_FONT_SIZE)
        foreground = BootstrapPalette.onSurface
    }

internal fun statCell(
    title: String,
    value: JLabel,
): JPanel =
    JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(
            JLabel(title).apply {
                font = uiFont(STAT_LABEL_FONT_SIZE)
                foreground = BootstrapPalette.faint
            },
        )
        add(Box.createVerticalStrut(STAT_LABEL_VALUE_GAP))
        add(value)
    }

internal fun formatBytes(bytes: Long): String =
    when {
        bytes >= BYTES_PER_KIB * BYTES_PER_KIB * BYTES_PER_KIB ->
            "%.2f GB".format(bytes.toDouble() / BYTES_PER_KIB / BYTES_PER_KIB / BYTES_PER_KIB)
        bytes >= BYTES_PER_KIB * BYTES_PER_KIB ->
            "%.1f MB".format(bytes.toDouble() / BYTES_PER_KIB / BYTES_PER_KIB)
        bytes >= BYTES_PER_KIB -> "%.0f KB".format(bytes.toDouble() / BYTES_PER_KIB)
        else -> "$bytes B"
    }
