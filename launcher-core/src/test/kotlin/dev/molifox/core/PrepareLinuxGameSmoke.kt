// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责准备 Linux 真实游戏验收所需目录和启动信息。

package dev.molifox.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.name

/** Downloads Linux artifacts on Windows; never reports a cross-preparation as Linux acceptance. */
fun main(): Unit =
    runBlocking {
        val root = Path.of(requireNotNull(System.getProperty("molifox.test.dir")))
        val version = System.getProperty("molifox.game.version", "1.20.1")
        val loader = LoaderType.valueOf(System.getProperty("molifox.game.loader", "Fabric"))
        require(loader in listOf(LoaderType.Vanilla, LoaderType.Fabric))
        val platform = linuxPreparationPlatform()
        LauncherController(platform, root.resolve("profile-preparation")).use { controller ->
            installGame(controller, version, loader)
            writePreparedInstance(controller, root)
            prepareRuntime(controller, root)
            println("Linux artifacts prepared; no Linux process has been validated yet")
        }
    }

/** 伪装成 Linux 平台下载制品；只准备目录与文件，不代表已在 Linux 上启动验证。 */
private fun linuxPreparationPlatform(): PlatformServices =
    object : PlatformServices by DesktopPlatform() {
        override val osName = "linux"
        override val architecture = "x64"
        override val javaExecutable = "java"
        override val classpathSeparator = ":"
    }

private suspend fun installGame(
    controller: LauncherController,
    version: String,
    loader: LoaderType,
) {
    val loaderVersion =
        if (loader == LoaderType.Vanilla) {
            ""
        } else {
            System.getProperty("molifox.game.loaderVersion", "0.16.14")
        }
    val jobId =
        controller.install(
            InstallRequest("Real Linux acceptance", version, loader, loaderVersion),
        )
    awaitInstallFinished(controller, jobId)
}

/** 轮询安装任务直到结束；状态变化才打印，失败与取消直接抛出任务给出的原因。 */
private suspend fun awaitInstallFinished(
    controller: LauncherController,
    jobId: String,
): Unit =
    withTimeout(30 * 60_000L) {
        var previous = ""
        while (true) {
            val job =
                controller.state.value.jobs
                    .first { it.id == jobId }
            val status = "${job.status}: ${job.detail}"
            if (status != previous) {
                println(status)
                previous = status
            }
            when (job.status) {
                JobStatus.Completed -> break
                JobStatus.Failed,
                JobStatus.Cancelled,
                -> error(job.error ?: job.detail)
                else -> delay(1_000)
            }
        }
    }

private fun writePreparedInstance(
    controller: LauncherController,
    root: Path,
) {
    val instance =
        controller.store.instance(
            checkNotNull(controller.store.data.selectedInstanceId),
        )
    writeJson(root.resolve("prepared-instance.json"), instance.copy(gameDirectoryId = null))
}

/** 下载 Linux Java 运行时并展开，供验收入口离线使用。 */
private suspend fun prepareRuntime(
    controller: LauncherController,
    root: Path,
) {
    val runtime = JavaRuntimePackages(controller.network).find(17, "linux", "x64")
    controller.network.download(
        DownloadArtifact(
            runtime.url,
            root.resolve("runtime.tar.gz"),
            runtime.sha256,
            hashAlgorithm = "SHA-256",
        ),
    )
    writeJson(root.resolve("runtime-package.json"), runtime)
    val unpacking = root.resolve("runtime").apply { createDirectories() }
    unpackRuntimeArchive(root.resolve("runtime.tar.gz"), unpacking)
    root.resolve("runtime.tar.gz").deleteExisting()
}

/** 逐条限制条目数量、累计大小与落盘路径，避免把任意 tar 内容写到验收目录之外。 */
private fun unpackRuntimeArchive(
    archive: Path,
    unpacking: Path,
) {
    Files.newInputStream(archive).use { raw -> unpackCompressedRuntime(raw, unpacking) }
}

private fun unpackCompressedRuntime(
    raw: InputStream,
    unpacking: Path,
) {
    GzipCompressorInputStream(raw).use { gzip ->
        TarArchiveInputStream(gzip).use { tar -> extractRuntimeEntries(tar, unpacking) }
    }
}

private fun extractRuntimeEntries(
    tar: TarArchiveInputStream,
    unpacking: Path,
) {
    var count = 0
    var bytes = 0L
    while (true) {
        val entry = tar.nextEntry ?: break
        require(++count <= 50_000 && entry.size >= 0)
        bytes = Math.addExact(bytes, entry.size)
        require(bytes <= 1024L * 1024 * 1024)
        if (entry.isFile && !entry.isSymbolicLink && !entry.isLink) {
            val target = safePath(unpacking, entry.name.removePrefix("./"))
            target.parent.createDirectories()
            Files.copy(tar, target)
        }
    }
}
