// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 将测试中的系统操作限制在临时目录，记录打开请求而不启动用户的外部程序。

package dev.molifox.desktop.checks

import dev.molifox.core.PlatformServices
import dev.molifox.desktop.platform.dialogs.FilePicker
import dev.molifox.desktop.platform.dialogs.PickRequest
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

internal class FixturePlatform(
    private val root: Path,
) : PlatformServices {
    override val osName = "windows"
    override val architecture = "x86_64"

    // 与真实平台一致：javaExecutable 只是文件名，扫描时由调用方拼到 bin/ 下。
    override val javaExecutable = "java.exe"

    /** 本机正在运行的 Java 完整路径；仅作为「已配置的 Java」写入设置。 */
    val javaPath: String =
        Path
            .of(
                System.getProperty("java.home"),
                "bin",
                javaExecutable,
            ).toString()
    override val classpathSeparator = ";"
    override val totalMemoryMb = 8192
    override val defaultDataDirectory: Path = root.resolve("profile")
    val openedFolders = CopyOnWriteArrayList<Path>()
    val openedUrls = CopyOnWriteArrayList<String>()

    override fun openFolder(directory: Path) {
        check(directory.toAbsolutePath().normalize().startsWith(root))
        openedFolders.add(directory)
    }

    override fun openUrl(url: String) {
        openedUrls.add(url)
    }

    override fun moveToTrash(file: Path) {
        check(file.toRealPath().startsWith(root.toRealPath()))
        val trash = Files.createDirectories(root.resolve("trash"))
        Files.move(file, trash.resolve(UUID.randomUUID().toString()))
    }
}

internal class FixtureFilePicker : FilePicker {
    private val choices = ArrayDeque<Path>()
    val requests = CopyOnWriteArrayList<PickRequest>()

    @Synchronized
    fun chooseNext(path: Path) {
        choices.addLast(path)
    }

    override suspend fun pick(request: PickRequest): List<Path> =
        synchronized(this) {
            requests.add(request)
            check(choices.isNotEmpty()) { "没有为文件选择请求准备结果：${request.title}" }
            listOf(choices.removeFirst())
        }
}
