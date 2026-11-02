// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责“下载完成后自动替换启动器并重启”：写一个等待本进程退出的脚本，再交给系统执行。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
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
            // 原生外壳把自身 exe 路径经环境变量传进来；开发环境（java -jar）没有该变量，只能手动更新。
            "windows" ->
                env("MOLIFOX_BUNDLE_PATH")?.takeIf(::isPortableWindowsExe)?.let(Path::of)
                    ?: command?.takeIf(::isPortableWindowsExe)?.let(Path::of)
            "linux" ->
                env("MOLIFOX_BUNDLE_PATH")?.takeIf { it.endsWith(".run") }?.let(Path::of)
                    ?: command?.takeIf { it.endsWith(".run") }?.let(Path::of)
            else -> null
        }

    /** 原生外壳进程；JVM 退出后它还要清理临时目录并退出，覆盖文件前必须等它一起结束。 */
    fun wrapperPid(): Long? =
        System
            .getenv("MOLIFOX_WRAPPER_PID")
            ?.toLongOrNull()
            ?.takeIf { it != ProcessHandle.current().pid() }

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
    private val wrapperPid: Long? = LauncherBundlePath.wrapperPid(),
) {
    fun apply(downloaded: Path): Result<Unit> =
        runCatching {
            val target =
                requireNotNull(bundle) {
                    I18n.t("networkB2.updateApplier.notPortable")
                }
            require(downloaded.isRegularFile()) {
                I18n.t("networkB2.updateApplier.updateMissing")
            }
            require(target.isRegularFile()) {
                I18n.t("networkB2.updateApplier.currentLauncherMissing")
            }
            require(target.toAbsolutePath() != downloaded.toAbsolutePath()) {
                I18n.t("networkB2.updateApplier.sameFile")
            }
            require(!Files.isSameFile(target, downloaded)) { I18n.t("networkB2.updateApplier.sameFile") }
            val asset =
                requireNotNull(
                    store.state.value.update.release
                        ?.asset,
                ) { I18n.t("core.gitHubUpdater.7") }
            require(
                asset.sha256.isNotBlank() && Files.size(downloaded) == asset.size &&
                    digest(downloaded, "SHA-256").equals(asset.sha256, true),
            ) { I18n.t("core.gitHubUpdater.8") }
            val staged = target.resolveSibling(".${target.fileName}.update-${UUID.randomUUID()}")
            runCatching {
                Files.copy(downloaded, staged)
                launch(writeScript(target, staged, asset.sha256.lowercase()))
            }.onFailure { failure ->
                if (failure is Exception) Files.deleteIfExists(staged)
            }.getOrThrow()
        }

    private fun writeScript(
        target: Path,
        downloaded: Path,
        sha256: String,
    ): Path {
        val directory =
            store.downloadDirectory.resolve("updates").resolve("apply").also {
                it.createDirectories()
            }
        val suffix = if (isWindows()) "ps1" else "sh"
        val script = directory.resolve("apply-${UUID.randomUUID()}.$suffix")
        Files.writeString(
            script,
            if (isWindows()) {
                "\uFEFF" + UpdateReplacementScripts.windows(target, downloaded, listOfNotNull(pid, wrapperPid), sha256)
            } else {
                UpdateReplacementScripts.linux(target, downloaded, listOfNotNull(pid, wrapperPid), sha256)
            },
        )
        return script
    }

    private fun isWindows(): Boolean = store.platform.osName == "windows"

    private fun launch(script: Path) {
        val command =
            if (isWindows()) {
                // 不使用裸命令名：部分机器 PATH 首位是应用执行别名，ProcessBuilder 会拿到拒绝访问。
                val systemRoot = System.getenv("SystemRoot") ?: "C:\\Windows"
                val powershell =
                    Path
                        .of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
                        .takeIf(Files::isRegularFile)
                        ?.toString()
                        ?: "powershell.exe"
                // -File 在受策略限制的机器上可能直接以拒绝访问失败；用 -Command 调用同一脚本更稳。
                val scriptCommand = "& '${script.toString().replace("'", "''")}'"
                listOf(
                    powershell,
                    "-NoProfile",
                    "-NonInteractive",
                    "-WindowStyle",
                    "Hidden",
                    // 默认 Restricted 策略会拒绝 .ps1；替换脚本由本进程生成并校验，显式放行。
                    "-ExecutionPolicy",
                    "Bypass",
                    "-Command",
                    scriptCommand,
                )
            } else {
                listOf("setsid", "sh", script.toString())
            }
        ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(script.resolveSibling("${script.fileName}.log").toFile()))
            .start()
    }
}
