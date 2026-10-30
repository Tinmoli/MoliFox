// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责桌面平台的内存查询、文件打开和回收站操作。

package dev.molifox.core

import com.sun.management.OperatingSystemMXBean
import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.lang.management.ManagementFactory
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

class DesktopPlatform : PlatformServices {
    override val osName =
        when {
            System.getProperty("os.name").lowercase(Locale.ROOT).contains("win") -> "windows"
            System.getProperty("os.name").lowercase(Locale.ROOT).contains("mac") -> "osx"
            else -> "linux"
        }
    override val architecture =
        when (System.getProperty("os.arch").lowercase(Locale.ROOT)) {
            "aarch64",
            "arm64",
            -> "arm64"
            "x86",
            "i386",
            "i686",
            -> "x86"
            else -> "x64"
        }
    override val javaExecutable = if (osName == "windows") "java.exe" else "java"
    override val classpathSeparator = if (osName == "windows") ";" else ":"
    override val totalMemoryMb: Int
        get() =
            (
                (ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean)
                    ?.totalMemorySize
                    ?.div(1024 * 1024) ?: 8192
            ).toInt()

    /**
     * 打开文件夹失败时的日志与用户提示由控制器注入（DesktopPlatform 不持有 store）；
     * 未注入时静默，方便命令行/测试环境。
     */
    var onLog: ((String) -> Unit)? = null
    var onNotice: ((String) -> Unit)? = null

    private val opener: PathOpener by lazy {
        PathOpener(
            osName = osName,
            openWithDesktop = desktopFileOpener(),
            launch = { command -> ProcessBuilder(command).start() },
            copyToClipboard = ::copyPathToClipboard,
            log = { message -> onLog?.invoke(message) },
            notice = { message -> onNotice?.invoke(message) },
        )
    }

    /**
     * 默认数据目录：仍优先使用显式指定的 molifox.data.dir / MOLIFOX_DATA_DIR，否则放在启动器 自身所在目录的 MoliFox/ 下。这里不能用
     * user.dir：它只是进程工作目录，可能是任意 shell 的位置，会让数据和默认 .minecraft 落到启动器之外。
     */
    override val defaultDataDirectory: Path
        get() =
            (System.getProperty("molifox.data.dir") ?: System.getenv("MOLIFOX_DATA_DIR"))
                ?.takeIf { it.isNotBlank() }
                ?.let { Path.of(it).toAbsolutePath().normalize() }
                ?: LauncherLocation.locate().resolve("MoliFox")

    override val availableMemoryMb: Int
        get() =
            (
                (ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean)
                    ?.freeMemorySize
                    ?.div(1024 * 1024) ?: (totalMemoryMb / 2).toLong()
            ).coerceIn(0, totalMemoryMb.toLong())
                .toInt()

    /**
     * 打开文件夹：Desktop → cmd start → explorer，依次回退；全部失败时把路径复制到剪贴板并提示，
     * 绝不把异常抛进点击回调（旧版直接 ProcessBuilder(explorer.exe) 在受限令牌下必失败）。
     */
    override fun openFolder(directory: Path) {
        val target = directory.toAbsolutePath().normalize()
        require(Files.exists(target) && Files.isDirectory(target)) { "目标不是文件夹：$target" }
        opener.open(target)
    }

    override fun openUrl(url: String) {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.userInfo == null) { "只允许 HTTPS 链接" }
        if (
            !GraphicsEnvironment.isHeadless() &&
            Desktop.isDesktopSupported() &&
            Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)
        ) {
            Desktop.getDesktop().browse(uri)
        } else {
            ProcessBuilder(
                if (osName == "windows") {
                    listOf("rundll32", "url.dll,FileProtocolHandler", url)
                } else {
                    listOf("xdg-open", url)
                },
            ).start()
        }
    }

    override fun moveToTrash(file: Path) {
        val desktopAvailable = !GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported()
        val trashed =
            desktopAvailable &&
                Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH) &&
                Desktop.getDesktop().moveToTrash(file.toFile())
        if (trashed) return
        // Linux desktops may not support AWT trash. Preserve files in the launcher's
        // own recovery folder rather than falling back to irreversible deletion.
        val trash =
            defaultDataDirectory
                .resolve("trash")
                .toFile()
                .apply { mkdirs() }
                .toPath()
        Files.move(file, trash.resolve("${System.currentTimeMillis()}-${file.fileName}"))
    }

    /** AWT 的 ShellExecute 走系统关联，比直接 CreateProcess("explorer.exe") 更不容易被拒。 */
    private fun desktopFileOpener(): ((File) -> Unit)? {
        if (GraphicsEnvironment.isHeadless() || !Desktop.isDesktopSupported()) return null
        val desktop = runCatching { Desktop.getDesktop() }.getOrNull() ?: return null
        if (!runCatching { desktop.isSupported(Desktop.Action.OPEN) }.getOrDefault(false)) {
            return null
        }
        return { file: File -> desktop.open(file) }
    }

    private fun copyPathToClipboard(path: String): Boolean {
        if (GraphicsEnvironment.isHeadless()) return false
        return runCatching {
            Toolkit
                .getDefaultToolkit()
                .systemClipboard
                .setContents(StringSelection(path), null)
            true
        }.getOrDefault(false)
    }
}

/**
 * 打开路径的尝试链：失败一次就换下一种方式，全部失败时复制路径并提示，永不抛出。
 * [openWithDesktop] 为 null 表示当前 JVM/桌面不支持 AWT Desktop（如 headless）。
 */
internal class PathOpener(
    private val osName: String,
    private val openWithDesktop: ((File) -> Unit)?,
    private val launch: (List<String>) -> Unit,
    private val copyToClipboard: (String) -> Boolean,
    private val log: (String) -> Unit,
    private val notice: (String) -> Unit,
) {
    fun open(target: Path): Boolean {
        val path = target.toAbsolutePath().normalize()
        val desktopOpener = openWithDesktop
        val opened =
            (
                desktopOpener != null &&
                    Files.exists(path) &&
                    attempt("java.awt.Desktop", path) { desktopOpener(path.toFile()) }
            ) ||
                pathOpenCommands(osName, path).any { (name, command) ->
                    attempt(name, path) { launch(command) }
                }
        return if (opened) {
            true
        } else {
            val copied = runCatching { copyToClipboard(path.toString()) }.getOrDefault(false)
            log(
                "打开 $path 的所有方式都失败" + if (copied) "，已复制路径到剪贴板" else "，剪贴板也不可用",
            )
            notice(
                if (copied) {
                    "系统拒绝打开（可能被安全软件拦截），路径已复制"
                } else {
                    "系统拒绝打开（可能被安全软件拦截），请手动打开：$path"
                },
            )
            false
        }
    }

    private fun attempt(
        name: String,
        path: Path,
        action: () -> Unit,
    ): Boolean =
        // 打开动作由调用方以 lambda 注入（Desktop.open / ProcessBuilder / 测试替身），异常类型无法收窄，按契约不抛出。
        runCatching {
            action()
            log("已打开 $path（$name）")
            true
        }.getOrElse { error ->
            log("打开 $path 失败（$name）：${error.message ?: error.javaClass.simpleName}")
            false
        }
}

/**
 * 命令行回退方式，按顺序尝试。Windows：`cmd /c start "" <path>`（路径作为单独参数， 不拼接字符串），
 * 再 `explorer.exe /select,<path>`（文件）或 `explorer.exe <path>`（目录）。
 */
internal fun pathOpenCommands(
    osName: String,
    target: Path,
): List<Pair<String, List<String>>> {
    val path = target.toAbsolutePath().normalize().toString()
    return when (osName) {
        "windows" ->
            buildList {
                add("cmd /c start" to listOf("cmd", "/c", "start", "", path))
                if (Files.isDirectory(target)) {
                    add("explorer.exe" to listOf("explorer.exe", path))
                } else {
                    add("explorer.exe /select" to listOf("explorer.exe", "/select,$path"))
                }
            }
        "osx" -> listOf("open" to listOf("open", path))
        else -> listOf("xdg-open" to listOf("xdg-open", path))
    }
}
