// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 已校验的新版分发文件在独立更新模式中等待旧进程、替换并重启。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
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

/** 启动新版分发文件的更新模式；就绪后才允许桌面层退出旧程序。 */
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
                }.toAbsolutePath().normalize()
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
            val identifier = UUID.randomUUID()
            val staged = target.resolveSibling(".${target.fileName}.update-$identifier")
            runCatching {
                Files.copy(downloaded, staged)
                launch(downloaded, target, staged, asset.sha256.lowercase(), identifier)
            }.onFailure { failure ->
                if (failure is Exception) {
                    Files.deleteIfExists(staged)
                    Files.deleteIfExists(staged.resolveSibling("${staged.fileName}.backup"))
                }
            }.getOrThrow()
        }

    private fun launch(
        downloaded: Path,
        target: Path,
        staged: Path,
        sha256: String,
        identifier: UUID,
    ) {
        val directory = store.downloadDirectory.resolve("updates/apply")
        createStorageDirectory(directory)
        val ready = directory.resolve("apply-$identifier.ready").toAbsolutePath()
        val log = directory.resolve("apply-$identifier.log").toAbsolutePath()
        val executable = downloaded.toAbsolutePath().normalize().toString()
        val prefix = if (store.platform.osName == "windows") listOf(executable) else listOf("setsid", "sh", executable)
        val command =
            prefix +
                listOf(
                    UPDATE_HELPER_MODE,
                    target.toString(),
                    staged.toString(),
                    sha256,
                    listOfNotNull(pid, wrapperPid).distinct().joinToString(","),
                    ready.toString(),
                )
        val process =
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .start()
        awaitReady(process, ready, log)
    }

    private fun awaitReady(
        process: Process,
        ready: Path,
        log: Path,
    ) {
        try {
            runCatching {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(UPDATE_HELPER_START_SECONDS)
                while (!Files.isRegularFile(ready) || Files.readString(ready) != "ready") {
                    check(process.isAlive && System.nanoTime() < deadline) {
                        I18n.t("settings.update.helperFailed", log)
                    }
                    process.waitFor(UPDATE_HELPER_POLL_MILLIS, TimeUnit.MILLISECONDS)
                }
            }.onFailure { failure ->
                if (failure is Exception) process.destroyForcibly()
            }.getOrThrow()
        } finally {
            Files.deleteIfExists(ready)
        }
    }
}

internal const val UPDATE_HELPER_MODE = "--molifox-apply-update"
private const val UPDATE_HELPER_START_SECONDS = 10L
private const val UPDATE_HELPER_POLL_MILLIS = 50L
