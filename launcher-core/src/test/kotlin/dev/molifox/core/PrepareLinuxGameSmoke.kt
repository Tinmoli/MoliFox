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
        val platform =
            object : PlatformServices by DesktopPlatform() {
                override val osName = "linux"
                override val architecture = "x64"
                override val javaExecutable = "java"
                override val classpathSeparator = ":"
            }
        LauncherController(platform, root.resolve("profile-preparation")).use { controller ->
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
            val instance =
                controller.store.instance(
                    checkNotNull(controller.store.data.selectedInstanceId),
                )
            writeJson(root.resolve("prepared-instance.json"), instance.copy(gameDirectoryId = null))
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
            Files.newInputStream(root.resolve("runtime.tar.gz")).use { raw ->
                GzipCompressorInputStream(raw).use { gzip ->
                    TarArchiveInputStream(gzip).use { tar ->
                        var count = 0
                        var bytes = 0L
                        while (true) {
                            val entry = tar.nextEntry ?: break
                            require(++count <= 50_000 && entry.size >= 0)
                            bytes = Math.addExact(bytes, entry.size)
                            require(bytes <= 1024L * 1024 * 1024)
                            if (!entry.isFile || entry.isSymbolicLink || entry.isLink) continue
                            val target = safePath(unpacking, entry.name.removePrefix("./"))
                            target.parent.createDirectories()
                            Files.copy(tar, target)
                        }
                    }
                }
            }
            root.resolve("runtime.tar.gz").deleteExisting()
            println("Linux artifacts prepared; no Linux process has been validated yet")
        }
    }
