package dev.molifox.core

import com.sun.management.OperatingSystemMXBean
import java.awt.Desktop
import java.lang.management.ManagementFactory
import java.net.URI
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
            "arm64" -> "arm64"
            "x86",
            "i386",
            "i686" -> "x86"
            else -> "x64"
        }
    override val javaExecutable = if (osName == "windows") "java.exe" else "java"
    override val classpathSeparator = if (osName == "windows") ";" else ":"
    override val totalMemoryMb: Int
        get() =
            ((ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean)
                    ?.totalMemorySize
                    ?.div(1024 * 1024) ?: 8192)
                .toInt()

    /**
     * 默认数据目录：仍优先使用显式指定的 molifox.data.dir / MOLIFOX_DATA_DIR，否则放在启动器
     * 自身所在目录的 MoliFox/ 下。这里不能用 user.dir：它只是进程工作目录，可能是任意 shell
     * 的位置，会让数据和默认 .minecraft 落到启动器之外。
     */
    override val defaultDataDirectory: Path
        get() =
            (System.getProperty("molifox.data.dir") ?: System.getenv("MOLIFOX_DATA_DIR"))
                ?.takeIf { it.isNotBlank() }
                ?.let { Path.of(it).toAbsolutePath().normalize() }
                ?: LauncherLocation.locate().resolve("MoliFox")

    override val availableMemoryMb: Int
        get() =
            ((ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean)
                    ?.freeMemorySize
                    ?.div(1024 * 1024) ?: (totalMemoryMb / 2).toLong())
                .coerceIn(0, totalMemoryMb.toLong())
                .toInt()

    override fun openFolder(directory: Path) {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN))
            Desktop.getDesktop().open(directory.toFile())
        else
            ProcessBuilder(
                    if (osName == "windows") listOf("explorer.exe", directory.toString())
                    else listOf("xdg-open", directory.toString())
                )
                .start()
    }

    override fun openUrl(url: String) {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.userInfo == null) { "只允许 HTTPS 链接" }
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
            Desktop.getDesktop().browse(uri)
        else
            ProcessBuilder(
                    if (osName == "windows") listOf("rundll32", "url.dll,FileProtocolHandler", url)
                    else listOf("xdg-open", url)
                )
                .start()
    }

    override fun moveToTrash(file: Path) {
        if (
            Desktop.isDesktopSupported() &&
                Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH) &&
                Desktop.getDesktop().moveToTrash(file.toFile())
        )
            return
        // Linux desktops may not support AWT trash. Preserve files in the launcher's
        // own recovery folder rather than falling back to irreversible deletion.
        val trash = defaultDataDirectory.resolve("trash").toFile().apply { mkdirs() }.toPath()
        java.nio.file.Files.move(
            file,
            trash.resolve("${System.currentTimeMillis()}-${file.fileName}"),
        )
    }
}
