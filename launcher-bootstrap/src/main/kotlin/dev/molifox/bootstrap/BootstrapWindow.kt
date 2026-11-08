// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 准备窗口：品牌标题、进度统计卡片与错误/重试交互，只用 Swing，不加载 Compose。
package dev.molifox.bootstrap

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.GridLayout
import java.awt.Image
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer

// ---------- 准备窗口的尺寸/节奏令牌 ----------

private const val CONTENT_ROW_GAP = 16
private const val WINDOW_PADDING_TOP = 22
private const val WINDOW_PADDING_HORIZONTAL = 26
private const val WINDOW_PADDING_BOTTOM = 20
private const val VERIFY_PHASE_HOLD_MILLIS = 180L
private const val DONE_PHASE_HOLD_MILLIS = 420L
private const val PERCENT_SCALE = 100
private const val SPEED_SAMPLE_INTERVAL_SECONDS = 0.5
private const val WINDOW_ICON_SIZE_TASKBAR = 16
private const val WINDOW_ICON_SIZE_TITLEBAR = 24
private const val WINDOW_ICON_SIZE_DIALOG = 32
private const val WINDOW_ICON_SIZE_LARGE = 48
private const val WINDOW_ICON_SIZE_EXTRA_LARGE = 64
private const val WINDOW_ICON_SIZE_HUGE = 128
private const val WINDOW_ICON_SIZE_MAX = 256

/**
 * 用户在准备窗口上选择的恢复动作：按钮入队放在本文件，主流程（BootstrapMain.kt）的
 * awaitInstallation 也要消费它，跨两个文件使用，因此从 private 提升为 internal。
 */
internal enum class RecoveryAction {
    Retry,
    SwitchMirror,
}

/**
 * 准备窗口：品牌标题 + 一行副标题 + 紧凑统计卡片（状态、圆角进度条、镜像/文件/已下载/速度）， 以及失败时的每条错误与两个按钮。
 *
 * 只用 Swing，不加载 Compose（Compose 依赖正是在这一步下载的）。窗口固定大小、不可缩放；
 * 文案与配色与桌面端一致，状态行随准备阶段切换动词（准备 / 下载 / 校验 / 完成）。
 *
 * 主流程（BootstrapMain.kt）负责创建并驱动本窗口，跨文件使用，因此从 private 提升为 internal。
 */
internal class BootstrapWindow(
    private val installer: RuntimeInstaller,
) {
    private enum class Phase { Preparing, Downloading, Verifying, Done }

    private val frame = JFrame(I18n.t("bootstrap.bootstrapMain.8"))

    @Volatile private var phase = Phase.Preparing

    private val progressBar = RoundedProgressBar()
    private val statusLabel =
        JLabel(I18n.t("bootstrap.bootstrapMain.9")).apply {
            font = uiFont(14, Font.BOLD)
            foreground = BootstrapPalette.onSurface
        }
    private val percentLabel =
        JLabel("—").apply {
            font = uiFont(13, Font.BOLD)
            foreground = BootstrapPalette.accent
            horizontalAlignment = SwingConstants.RIGHT
            preferredSize = Dimension(48, 18)
        }
    private val mirrorValue = statValue(I18n.t("bootstrap.bootstrapMain.10"))
    private val filesValue = statValue("0 / ${installer.totalFiles}")
    private val bytesValue = statValue("0 B / ${formatBytes(installer.totalBytes)}")
    private val speedValue = statValue("0 B/s")

    private val errorArea =
        JTextArea(3, 40).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            foreground = BootstrapPalette.error
            border = BorderFactory.createEmptyBorder(2, 4, 2, 2)
            font = uiFont(12)
        }
    private val errorScroll =
        JScrollPane(errorArea).apply {
            isOpaque = false
            viewport.isOpaque = false
            border = BorderFactory.createEmptyBorder()
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        }
    private val retryButton = RoundedButton(I18n.t("bootstrap.bootstrapMain.11"), primary = true)
    private val switchButton = RoundedButton(I18n.t("bootstrap.bootstrapMain.12"), primary = false)
    private val actionsPanel =
        JPanel(FlowLayout(FlowLayout.RIGHT, 10, 0)).apply {
            isOpaque = false
            add(retryButton)
            add(switchButton)
        }
    private val errorCard =
        RoundedSurface(BootstrapPalette.errorFill, BootstrapPalette.errorBorder, 12).apply {
            layout = BorderLayout(0, 10)
            border = BorderFactory.createEmptyBorder(14, 16, 14, 16)
            add(
                JLabel(I18n.t("bootstrap.bootstrapMain.13")).apply {
                    font = uiFont(12, Font.BOLD)
                    foreground = BootstrapPalette.error
                },
                BorderLayout.NORTH,
            )
            add(
                RoundedSurface(BootstrapPalette.surface, BootstrapPalette.errorBorder, 8).apply {
                    layout = BorderLayout()
                    border = BorderFactory.createEmptyBorder(3, 4, 3, 4)
                    add(errorScroll, BorderLayout.CENTER)
                },
                BorderLayout.CENTER,
            )
            add(actionsPanel, BorderLayout.SOUTH)
        }
    private val progressCard =
        RoundedSurface(BootstrapPalette.surface, BootstrapPalette.border, 12).apply {
            border = BorderFactory.createEmptyBorder(14, 18, 16, 18)
            layout = java.awt.GridBagLayout()
            // 错误卡片与进度卡片共用同一块区域：固定一块高度，切换时窗口不跳动。
            preferredSize = Dimension(520, 200)
            val statusRow =
                JPanel(BorderLayout()).apply {
                    isOpaque = false
                    add(statusLabel, BorderLayout.WEST)
                }
            val barRow =
                JPanel(BorderLayout(12, 0)).apply {
                    isOpaque = false
                    add(progressBar, BorderLayout.CENTER)
                    add(percentLabel, BorderLayout.EAST)
                }
            val separator =
                object : JComponent() {
                    init {
                        isOpaque = false
                        preferredSize = Dimension(1, 1)
                        minimumSize = Dimension(1, 1)
                    }

                    override fun paintComponent(g: Graphics) {
                        g.color = BootstrapPalette.border
                        g.fillRect(0, 0, width, 1)
                    }
                }
            val stats =
                JPanel(GridLayout(2, 2, 18, 10)).apply {
                    isOpaque = false
                    add(statCell(I18n.t("bootstrap.bootstrapMain.14"), mirrorValue))
                    add(statCell(I18n.t("bootstrap.bootstrapMain.15"), filesValue))
                    add(statCell(I18n.t("bootstrap.bootstrapMain.16"), bytesValue))
                    add(statCell(I18n.t("bootstrap.bootstrapMain.17"), speedValue))
                }
            val constraints =
                java.awt.GridBagConstraints().apply {
                    gridx = 0
                    gridy = 0
                    weightx = 1.0
                    fill = java.awt.GridBagConstraints.HORIZONTAL
                    anchor = java.awt.GridBagConstraints.NORTHWEST
                }
            add(statusRow, constraints)
            constraints.gridy = 1
            constraints.insets = java.awt.Insets(10, 0, 0, 0)
            add(barRow, constraints)
            constraints.gridy = 2
            constraints.insets = java.awt.Insets(16, 0, 0, 0)
            add(separator, constraints)
            constraints.gridy = 3
            constraints.insets = java.awt.Insets(12, 0, 0, 0)
            add(stats, constraints)
            constraints.gridy = 4
            constraints.weighty = 1.0
            constraints.fill = java.awt.GridBagConstraints.VERTICAL
            add(JPanel().apply { isOpaque = false }, constraints)
        }
    private val bodyCards =
        JPanel(CardLayout()).apply {
            isOpaque = false
            add(progressCard, CARD_PROGRESS)
            add(errorCard, CARD_ERROR)
        }
    private val header =
        JPanel(BorderLayout(14, 0)).apply {
            isOpaque = false
            val mark = launcherIcon(44)
            add(
                JLabel().apply {
                    icon = mark?.let { ImageIcon(it) }
                    preferredSize = Dimension(44, 44)
                    minimumSize = preferredSize
                    horizontalAlignment = SwingConstants.LEFT
                    verticalAlignment = SwingConstants.CENTER
                },
                BorderLayout.WEST,
            )
            add(
                JPanel().apply {
                    isOpaque = false
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    add(
                        JLabel("MoliFox").apply {
                            font = uiFont(26, Font.BOLD)
                            foreground = BootstrapPalette.heading
                        },
                    )
                    add(Box.createVerticalStrut(3))
                    add(
                        JLabel(I18n.t("bootstrap.bootstrapMain.18")).apply {
                            font = uiFont(12)
                            foreground = BootstrapPalette.muted
                        },
                    )
                },
                BorderLayout.CENTER,
            )
        }
    private val footer =
        JLabel(I18n.t("bootstrap.bootstrapMain.19"))
            .apply {
                font = uiFont(11)
                foreground = BootstrapPalette.muted
            }

    private val actions = LinkedBlockingQueue<RecoveryAction>()
    private var lastBytes = 0L
    private var lastSampleAt = System.nanoTime()
    private val timer = Timer(250) { refresh() }

    init {
        retryButton.addActionListener { actions.offer(RecoveryAction.Retry) }
        switchButton.addActionListener { actions.offer(RecoveryAction.SwitchMirror) }
        frame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        frame.isResizable = false
        frame.contentPane =
            JPanel(BorderLayout(0, CONTENT_ROW_GAP)).apply {
                isOpaque = true
                background = BootstrapPalette.background
                border =
                    BorderFactory.createEmptyBorder(
                        WINDOW_PADDING_TOP,
                        WINDOW_PADDING_HORIZONTAL,
                        WINDOW_PADDING_BOTTOM,
                        WINDOW_PADDING_HORIZONTAL,
                    )
                add(header, BorderLayout.NORTH)
                add(bodyCards, BorderLayout.CENTER)
                add(footer, BorderLayout.SOUTH)
            }
        // 窗口图标与左上角品牌图同源：就是启动器自己的 icon.png，不再显示 Java 默认图标。
        val iconSizes =
            listOf(
                WINDOW_ICON_SIZE_TASKBAR,
                WINDOW_ICON_SIZE_TITLEBAR,
                WINDOW_ICON_SIZE_DIALOG,
                WINDOW_ICON_SIZE_LARGE,
                WINDOW_ICON_SIZE_EXTRA_LARGE,
                WINDOW_ICON_SIZE_HUGE,
                WINDOW_ICON_SIZE_MAX,
            )
        frame.iconImages = iconSizes.mapNotNull { launcherIcon(it) }.map { it as Image }
        frame.pack()
        frame.setLocationRelativeTo(null)
    }

    fun open() {
        SwingUtilities.invokeLater {
            frame.isVisible = true
            timer.start()
        }
    }

    /** 全部文件已下载校验完成：状态行切到「校验」，随后由 [close] 显示「完成」。 */
    fun markVerifying() {
        phase = Phase.Verifying
        SwingUtilities.invokeLater {
            renderPhase()
            percentLabel.text = "100%"
            progressBar.indeterminate = false
            progressBar.progress = 1f
        }
        // 仅在首次下载完成时调用一次；当前在后台准备线程上，短暂等待是为了让 EDT
        // 先把「校验」状态渲染出来，避免紧接着的 close 直接跳到「完成」看不到这一步。
        runCatching { Thread.sleep(VERIFY_PHASE_HOLD_MILLIS) }
    }

    /** 关闭窗口；[completed] 为 true 时先短暂显示「完成」，再从准备窗口进入主界面。 */
    fun close(completed: Boolean = true) {
        timer.stop()
        if (completed) {
            val done =
                Runnable {
                    phase = Phase.Done
                    renderPhase()
                    progressBar.indeterminate = false
                    progressBar.progress = 1f
                    percentLabel.text = "100%"
                    speedValue.text = "—"
                }
            if (SwingUtilities.isEventDispatchThread()) {
                done.run()
            } else {
                // invokeAndWait 可能因 EDT 正在退出被打断，或 runnable 自身抛错；
                // 失败原因不能静默吞掉，记入运行日志便于排查，窗口随后仍由下方 dispose 关闭。
                runCatching { SwingUtilities.invokeAndWait(done) }.onFailure { error ->
                    log("prepare window close failed: ${error.message ?: error.javaClass.simpleName}")
                }
            }
            // 仍在后台线程上短暂停留，让刚投递的「完成」状态被用户看到后再关闭窗口；
            // 若已在 EDT 上则不能 sleep（会冻结界面），直接继续关闭。
            if (!SwingUtilities.isEventDispatchThread()) {
                runCatching { Thread.sleep(DONE_PHASE_HOLD_MILLIS) }
            }
        }
        if (SwingUtilities.isEventDispatchThread()) {
            frame.dispose()
        } else {
            SwingUtilities.invokeLater { frame.dispose() }
        }
    }

    fun showError(
        messages: List<String>,
        canSwitchMirror: Boolean,
    ) {
        SwingUtilities.invokeLater {
            errorArea.text = messages.joinToString("\n")
            errorArea.caretPosition = 0
            switchButton.isEnabled = canSwitchMirror
            (bodyCards.layout as CardLayout).show(bodyCards, CARD_ERROR)
            frame.validate()
        }
    }

    fun clearError() {
        SwingUtilities.invokeLater {
            (bodyCards.layout as CardLayout).show(bodyCards, CARD_PROGRESS)
            frame.validate()
        }
    }

    /** 等待「重试」或「更换镜像」操作，并响应当前协程的取消。 */
    suspend fun awaitRecovery(): RecoveryAction {
        while (true) {
            currentCoroutineContext().ensureActive()
            val action = withContext(Dispatchers.IO) { actions.poll(200, TimeUnit.MILLISECONDS) }
            if (action != null) return action
        }
    }

    private fun renderPhase() {
        statusLabel.text =
            when (phase) {
                Phase.Preparing -> I18n.t("bootstrap.bootstrapMain.9")
                Phase.Downloading -> I18n.t("bootstrap.bootstrapMain.20")
                Phase.Verifying -> I18n.t("bootstrap.bootstrapMain.21")
                Phase.Done -> I18n.t("bootstrap.bootstrapMain.22")
            }
    }

    private fun refresh() {
        val done = installer.filesDone
        val bytes = installer.bytesDone
        if (phase == Phase.Preparing && installer.mirror != null) {
            phase = Phase.Downloading
            renderPhase()
        }
        val totalBytes = installer.totalBytes
        val fraction =
            if (totalBytes > 0) {
                bytes.toFloat() / totalBytes
            } else {
                done.toFloat() / installer.totalFiles.coerceAtLeast(1)
            }
        progressBar.progress = fraction.coerceIn(0f, 1f)
        progressBar.indeterminate = phase == Phase.Preparing && installer.mirror == null
        progressBar.tick()
        percentLabel.text =
            if (progressBar.indeterminate) "—" else "${(fraction * PERCENT_SCALE).toInt()}%"
        filesValue.text = "$done / ${installer.totalFiles}"
        bytesValue.text = "${formatBytes(bytes)} / ${formatBytes(totalBytes)}"
        installer.mirror?.let { mirror ->
            mirrorValue.text =
                if (mirror.latencyMillis > 0) {
                    "${mirror.name} · ${mirror.latencyMillis} ms"
                } else {
                    mirror.name
                }
        }
        val now = System.nanoTime()
        val seconds = (now - lastSampleAt) / 1_000_000_000.0
        if (seconds >= SPEED_SAMPLE_INTERVAL_SECONDS) {
            val speed = ((bytes - lastBytes) / seconds).toLong().coerceAtLeast(0)
            speedValue.text = "${formatBytes(speed)}/s"
            lastBytes = bytes
            lastSampleAt = now
        }
    }

    private companion object {
        const val CARD_PROGRESS = "progress"
        const val CARD_ERROR = "error"
    }
}
