// 启动阶段也能显示的轻量错误窗口，不依赖 Compose；提供原因、复制详情和日志入口。
package dev.molifox.bootstrap

import dev.molifox.core.i18n.I18n
import java.awt.BorderLayout
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities

private const val ERROR_WINDOW_WIDTH = 600
private const val ERROR_WINDOW_HEIGHT = 280
private const val ERROR_DETAILS_HEIGHT = 180
private const val ERROR_PADDING = 20
private const val ERROR_TEXT_SIZE = 13
private const val ERROR_TEXT_COLUMNS = 45
private const val ERROR_TITLE_SIZE = 20
private const val ERROR_ICON_SIZE = 32

object StartupFailureDialog {
    fun show(
        error: Throwable,
        logPath: Path? = null,
    ) {
        val display = Runnable { startupFailureWindow(error, logPath).isVisible = true }
        if (SwingUtilities.isEventDispatchThread()) {
            display.run()
        } else {
            SwingUtilities.invokeAndWait(
                display,
            )
        }
    }
}

internal fun startupFailureWindow(
    error: Throwable,
    logPath: Path? = null,
): JDialog = FailureWindow(error, logPath).build()

private class FailureWindow(
    private val error: Throwable,
    private val logPath: Path?,
) {
    private val dialog = JDialog(null as java.awt.Frame?, I18n.t("bootstrapB.dialog.title"), true)
    private val font = uiFont(ERROR_TEXT_SIZE)
    private val status = JLabel(I18n.t("bootstrap.startupFailureDialog.1"))
    private val details =
        JScrollPane(text(error.stackTraceToString(), wrap = false)).apply {
            alignmentX = 0f
            preferredSize = Dimension(ERROR_WINDOW_WIDTH - ERROR_PADDING * 2, ERROR_DETAILS_HEIGHT)
            border = BorderFactory.createLineBorder(BootstrapPalette.border)
            viewport.background = BootstrapPalette.surface
            isVisible = false
        }

    fun build(): JDialog {
        dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
        dialog.iconImages = listOfNotNull(launcherIcon(ERROR_ICON_SIZE))
        dialog.contentPane =
            JPanel(BorderLayout()).apply {
                background = BootstrapPalette.background
                add(header(), BorderLayout.NORTH)
                add(body(), BorderLayout.CENTER)
                add(actions(), BorderLayout.SOUTH)
            }
        dialog.minimumSize = Dimension(ERROR_WINDOW_WIDTH, ERROR_WINDOW_HEIGHT)
        dialog.pack()
        dialog.setLocationRelativeTo(null)
        return dialog
    }

    private fun header() =
        JPanel(BorderLayout(ERROR_PADDING / 2, 0)).apply {
            isOpaque = false
            border =
                BorderFactory.createEmptyBorder(
                    ERROR_PADDING / 2,
                    ERROR_PADDING,
                    ERROR_PADDING,
                    ERROR_PADDING,
                )
            add(
                JLabel(
                    I18n.t(
                        if (error.message == I18n.t("miscA1.controllerCore.alreadyRunning")) {
                            "bootstrap.startupFailureDialog.alreadyRunning"
                        } else {
                            "bootstrap.startupFailureDialog.2"
                        },
                    ),
                ).apply {
                    font = this@FailureWindow.font.deriveFont(Font.BOLD, ERROR_TITLE_SIZE.toFloat())
                    foreground = BootstrapPalette.onSurface
                },
                BorderLayout.CENTER,
            )
            launcherIcon(ERROR_ICON_SIZE)?.let { add(JLabel(ImageIcon(it)), BorderLayout.WEST) }
        }

    private fun body() =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = BorderFactory.createEmptyBorder(ERROR_PADDING, ERROR_PADDING, 0, ERROR_PADDING)
            add(text(summary()))
            logPath?.let { add(text(I18n.t("bootstrap.startupFailureDialog.3", it))) }
            add(Box.createVerticalStrut(ERROR_PADDING / 2))
            add(
                JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                    alignmentX = 0f
                    isOpaque = false
                    add(
                        button(I18n.t("bootstrap.startupFailureDialog.4")) {
                            details.isVisible = !details.isVisible
                            (it.source as? JButton)?.text =
                                if (details.isVisible) {
                                    I18n.t("bootstrap.startupFailureDialog.5")
                                } else {
                                    I18n.t("bootstrap.startupFailureDialog.4")
                                }
                            dialog.pack()
                        },
                    )
                    maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                },
            )
            add(Box.createVerticalStrut(ERROR_PADDING / 2))
            add(details)
            add(
                status.apply {
                    font = this@FailureWindow.font
                    foreground = BootstrapPalette.muted
                    alignmentX = 0f
                    border = BorderFactory.createEmptyBorder(ERROR_PADDING / 2, 0, 0, 0)
                },
            )
        }

    private fun actions() =
        JPanel(FlowLayout(FlowLayout.RIGHT, ERROR_PADDING / 2, 0)).apply {
            isOpaque = false
            border =
                BorderFactory.createEmptyBorder(
                    ERROR_PADDING,
                    ERROR_PADDING,
                    ERROR_PADDING,
                    ERROR_PADDING,
                )
            add(button(I18n.t("bootstrap.startupFailureDialog.6")) { copyError() })
            add(button(I18n.t("bootstrap.startupFailureDialog.7")) { openLogs() }.apply { isEnabled = logPath != null })
            add(
                button(I18n.t("bootstrap.startupFailureDialog.8"), primary = true) { dialog.dispose() }.apply {
                    dialog.rootPane.defaultButton = this
                },
            )
        }

    private fun copyError() =
        attempt(I18n.t("bootstrap.startupFailureDialog.9")) {
            val content =
                error.stackTraceToString() + (logPath?.let { I18n.t("bootstrap.startupFailureDialog.10", it) } ?: "")
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(content), null)
        }

    private fun openLogs() =
        attempt(I18n.t("bootstrap.startupFailureDialog.11")) {
            val directory =
                requireNotNull(logPath?.toAbsolutePath()?.parent) { I18n.t("bootstrap.startupFailureDialog.12") }
            check(Files.isDirectory(directory)) { I18n.t("bootstrap.startupFailureDialog.13") }
            check(Desktop.isDesktopSupported()) { I18n.t("bootstrap.startupFailureDialog.14") }
            Desktop.getDesktop().open(directory.toFile())
        }

    private fun summary(): String =
        when {
            error.message.orEmpty().contains("Cannot change transparency", ignoreCase = true) ->
                I18n.t("bootstrap.startupFailureDialog.15")
            error is java.io.IOException -> I18n.t("bootstrap.startupFailureDialog.16", error.message.orEmpty())
            else -> I18n.t("bootstrap.startupFailureDialog.17", error.message ?: error.javaClass.simpleName)
        }

    private fun text(
        content: String,
        wrap: Boolean = true,
    ) = JTextArea(content).apply {
        font = this@FailureWindow.font
        foreground = BootstrapPalette.onSurface
        alignmentX = 0f
        lineWrap = wrap
        wrapStyleWord = true
        isEditable = false
        isOpaque = false
        rows = if (wrap) 2 else 0
        columns = ERROR_TEXT_COLUMNS
        border = BorderFactory.createEmptyBorder(0, 0, ERROR_PADDING / 2, 0)
    }

    private fun button(
        label: String,
        primary: Boolean = false,
        action: (java.awt.event.ActionEvent) -> Unit,
    ) = RoundedButton(label, primary).apply {
        addActionListener { action(it) }
    }

    private fun attempt(
        message: String,
        action: () -> Unit,
    ) {
        runCatching(action).onSuccess { status.text = message }.onFailure {
            status.text = it.message ?: I18n.t("bootstrap.startupFailureDialog.18")
            System.err.println(I18n.t("bootstrap.startupFailureDialog.19", it.stackTraceToString()))
        }
    }
}
