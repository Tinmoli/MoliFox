// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责“下载完成后自动替换启动器并重启”：写一个等待本进程退出的脚本，再交给系统执行。

package dev.molifox.core

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile

/** 解析当前便携版启动器文件；开发环境（java -jar）返回 null，只允许手动更新。 */
internal object LauncherBundlePath {
    fun current(
        os: String,
        command: String? =
            ProcessHandle
                .current()
                .info()
                .command()
                .orElse(null),
        env: (String) -> String? = System::getenv,
    ): Path? =
        when (os) {
            "windows" -> command?.takeIf(::isPortableWindowsExe)?.let(Path::of)
            "linux" ->
                env("MOLIFOX_BUNDLE_PATH")?.takeIf { it.endsWith(".run") }?.let(Path::of)
                    ?: command?.takeIf { it.endsWith(".run") }?.let(Path::of)
            else -> null
        }

    /** Linux 的 .run 外壳进程；JVM 退出后它还要清理临时目录，需等它一起退出再覆盖文件。 */
    fun wrapperPid(os: String): Long? =
        if (os == "linux") System.getenv("MOLIFOX_WRAPPER_PID")?.toLongOrNull() else null

    private fun isPortableWindowsExe(path: String): Boolean {
        if (!path.endsWith(".exe", ignoreCase = true)) return false
        val name =
            path
                .replace(File.separatorChar, '/')
                .substringAfterLast('/')
                .lowercase()
        return name !in setOf("java.exe", "javaw.exe")
    }
}

/** 生成跨平台替换脚本并启动；脚本负责等待进程退出、覆盖文件、重启新版本。 */
internal class UpdateApplier(
    private val store: LauncherStore,
    private val bundle: Path? = LauncherBundlePath.current(store.platform.osName),
    private val pid: Long = ProcessHandle.current().pid(),
    private val wrapperPid: Long? = LauncherBundlePath.wrapperPid(store.platform.osName),
) {
    fun apply(downloaded: Path): Result<Unit> =
        runCatching {
            val target = requireNotNull(bundle) { "当前不是便携版启动器，无法自动替换，请手动更新。" }
            require(downloaded.isRegularFile()) { "更新包不存在，请重新下载。" }
            require(target.isRegularFile()) { "找不到当前启动器文件，请手动更新。" }
            require(target.toAbsolutePath() != downloaded.toAbsolutePath()) {
                "更新包与当前启动器是同一个文件。"
            }
            launch(writeScript(target, downloaded))
        }

    private fun writeScript(
        target: Path,
        downloaded: Path,
    ): Path {
        val directory =
            store.downloadDirectory.resolve("updates").resolve("apply").also {
                it.createDirectories()
            }
        val suffix = if (isWindows()) "ps1" else "sh"
        val script = directory.resolve("apply-${UUID.randomUUID()}.$suffix")
        Files.writeString(
            script,
            if (isWindows()) windowsScript(target, downloaded) else linuxScript(target, downloaded),
        )
        return script
    }

    private fun isWindows(): Boolean = store.platform.osName == "windows"

    /** 等待 JVM（以及 Linux 外壳）退出后覆盖启动器；用单引号字面量避免路径注入。 */
    private fun windowsScript(
        target: Path,
        downloaded: Path,
    ): String {
        val waits =
            listOfNotNull(pid, wrapperPid).joinToString(NEWLINE) { processPid ->
                val check = "Get-Process -Id $processPid -ErrorAction SilentlyContinue"
                "while ($check) { Start-Sleep -Milliseconds 500 }"
            }
        val copy =
            "Copy-Item -LiteralPath '${psLiteral(downloaded)}' " +
                "-Destination '${psLiteral(target)}' -Force"
        return listOf(
            "\$ErrorActionPreference = 'Stop'",
            waits,
            "for (\$attempt = 0; \$attempt -lt 30; \$attempt++) {",
            "    try {",
            "        $copy",
            "        break",
            "    } catch {",
            "        Start-Sleep -Milliseconds 500",
            "    }",
            "}",
            "Start-Process -FilePath '${psLiteral(target)}'",
            "Remove-Item -LiteralPath \$PSCommandPath -Force",
        ).joinToString(NEWLINE)
    }

    private fun linuxScript(
        target: Path,
        downloaded: Path,
    ): String {
        val wait = listOfNotNull(pid, wrapperPid).joinToString(" ") { "\"$it\"" }
        return listOf(
            "#!/bin/sh",
            "for pid in $wait; do",
            "  while kill -0 \"\$pid\" 2>/dev/null; do sleep 1; done",
            "done",
            "cp -f '${shLiteral(downloaded)}' '${shLiteral(target)}' && " +
                "chmod +x '${shLiteral(target)}'",
            "setsid '${shLiteral(target)}' >/dev/null 2>&1 &",
            "rm -f \"\$0\"",
        ).joinToString(NEWLINE) + NEWLINE
    }

    private fun launch(script: Path) {
        val command =
            if (isWindows()) {
                listOf(
                    "cmd.exe",
                    "/c",
                    "start",
                    "",
                    "/min",
                    "powershell.exe",
                    "-NoProfile",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    script.toString(),
                )
            } else {
                listOf("setsid", "sh", script.toString())
            }
        ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }

    private fun psLiteral(path: Path): String = path.toAbsolutePath().toString().replace("'", "''")

    private fun shLiteral(path: Path): String =
        path.toAbsolutePath().toString().replace("'", "'\''")

    private companion object {
        const val NEWLINE = "\n"
    }
}
