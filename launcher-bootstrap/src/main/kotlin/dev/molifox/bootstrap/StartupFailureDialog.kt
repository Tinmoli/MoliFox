// 启动阶段也能显示的轻量错误窗口，不依赖 Compose；提供原因、复制详情和日志入口。
package dev.molifox.bootstrap

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.UIManager

private const val ERROR_WINDOW_WIDTH = 640
private const val ERROR_WINDOW_HEIGHT = 320
private const val ERROR_DETAILS_HEIGHT = 180
private const val ERROR_PADDING = 20
private const val ERROR_TEXT_SIZE = 14
private const val ERROR_TEXT_COLUMNS = 45
private const val ERROR_TITLE_SIZE = 22
private const val ERROR_BACKGROUND = 0xFFFAFC
private const val ERROR_ACCENT = 0xB94E78
private const val ERROR_HEADER = 0xF7E4ED
private const val ERROR_FOREGROUND = 0x382D33
private const val ERROR_MUTED = 0x76636C

object StartupFailureDialog {
    fun show(
        error: Throwable,
        logPath: Path? = null,
    ) {
        val display = Runnable { FailureWindow(error, logPath).show() }
        if (SwingUtilities.isEventDispatchThread()) {
            display.run()
        } else {
            SwingUtilities.invokeAndWait(
                display,
            )
        }
    }
}

private class FailureWindow(
    private val error: Throwable,
    private val logPath: Path?,
) {
    private val dialog = JDialog(null as java.awt.Frame?, "茉狐 · 遇到问题", true)
    private val font =
        (
            UIManager.getFont("Label.font")
                ?: Font(Font.SANS_SERIF, Font.PLAIN, ERROR_TEXT_SIZE)
        ).deriveFont(ERROR_TEXT_SIZE.toFloat())
    private val status = JLabel("详细原因已保留，可复制后反馈。")
    private val details =
        JScrollPane(text(error.stackTraceToString(), wrap = false)).apply {
            preferredSize = Dimension(ERROR_WINDOW_WIDTH - ERROR_PADDING * 2, ERROR_DETAILS_HEIGHT)
            isVisible = false
        }

    fun show() {
        dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
        dialog.contentPane =
            JPanel(BorderLayout()).apply {
                background = Color(ERROR_BACKGROUND)
                add(header(), BorderLayout.NORTH)
                add(body(), BorderLayout.CENTER)
                add(actions(), BorderLayout.SOUTH)
            }
        dialog.minimumSize = Dimension(ERROR_WINDOW_WIDTH, ERROR_WINDOW_HEIGHT)
        dialog.pack()
        dialog.setLocationRelativeTo(null)
        dialog.isVisible = true
    }

    private fun header() =
        JPanel(BorderLayout()).apply {
            background = Color(ERROR_HEADER)
            border =
                BorderFactory.createEmptyBorder(
                    ERROR_PADDING,
                    ERROR_PADDING,
                    ERROR_PADDING,
                    ERROR_PADDING,
                )
            add(
                JLabel("暂时无法继续").apply {
                    font = this@FailureWindow.font.deriveFont(Font.BOLD, ERROR_TITLE_SIZE.toFloat())
                    foreground = Color(ERROR_ACCENT)
                },
            )
        }

    private fun body() =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = BorderFactory.createEmptyBorder(ERROR_PADDING, ERROR_PADDING, 0, ERROR_PADDING)
            add(text(summary()))
            logPath?.let { add(text("日志位置：$it")) }
            add(
                button("查看技术详情") {
                    details.isVisible = !details.isVisible
                    (it.source as? JButton)?.text =
                        if (details.isVisible) "收起技术详情" else "查看技术详情"
                    dialog.pack()
                },
            )
            add(details)
            add(
                status.apply {
                    font = this@FailureWindow.font
                    foreground = Color(ERROR_MUTED)
                },
            )
        }

    private fun actions() =
        JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            isOpaque = false
            border =
                BorderFactory.createEmptyBorder(
                    ERROR_PADDING,
                    ERROR_PADDING,
                    ERROR_PADDING,
                    ERROR_PADDING,
                )
            add(button("复制错误") { copyError() })
            add(button("打开日志目录") { openLogs() }.apply { isEnabled = logPath != null })
            add(
                button("关闭") { dialog.dispose() }.apply {
                    background = Color(ERROR_ACCENT)
                    foreground = Color.WHITE
                    dialog.rootPane.defaultButton = this
                },
            )
        }

    private fun copyError() =
        attempt("错误详情已复制") {
            val content = error.stackTraceToString() + (logPath?.let { "\n日志：$it" } ?: "")
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(content), null)
        }

    private fun openLogs() =
        attempt("已打开日志目录") {
            val directory = requireNotNull(logPath?.toAbsolutePath()?.parent) { "尚未生成日志目录" }
            check(Files.isDirectory(directory)) { "日志目录不存在" }
            check(Desktop.isDesktopSupported()) { "系统不支持直接打开目录，请按日志位置手动查看" }
            Desktop.getDesktop().open(directory.toFile())
        }

    private fun summary(): String =
        when {
            error.message.orEmpty().contains("Cannot change transparency", ignoreCase = true) ->
                "窗口透明效果未能应用。请重新打开启动器，并将错误详情反馈给开发者。"
            error is java.io.IOException -> "无法完成文件或网络操作，请查看具体原因：\n${error.message.orEmpty()}"
            else -> "程序遇到了一个问题：\n${error.message ?: error.javaClass.simpleName}"
        }

    private fun text(
        content: String,
        wrap: Boolean = true,
    ) = JTextArea(content).apply {
        font = this@FailureWindow.font
        foreground = Color(ERROR_FOREGROUND)
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
        action: (java.awt.event.ActionEvent) -> Unit,
    ) = JButton(label).apply {
        font = this@FailureWindow.font
        isFocusPainted = true
        addActionListener { action(it) }
    }

    private fun attempt(
        message: String,
        action: () -> Unit,
    ) {
        runCatching(action).onSuccess { status.text = message }.onFailure {
            status.text = it.message ?: "操作未完成，请手动查看日志"
            System.err.println("错误窗口操作失败：${it.stackTraceToString()}")
        }
    }
}
