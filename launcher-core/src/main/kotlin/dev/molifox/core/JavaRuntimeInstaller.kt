// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责把 Zulu/Temurin 运行时下载、校验、解压并登记到 MoliFox/runtimes/。

package dev.molifox.core

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.io.path.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

/**
 * 安装流程：解析厂商包 → 下载到 MoliFox 下载目录（带 .part 断点）→ 校验 sha256 与大小 →
 * 解压到 runtimes/.part-* → 探测可执行文件 → 原子改名到 runtimes/<vendor>-<major>-<os>-<arch>
 * → 写入 index.json。失败时只清理本次创建的临时目录/压缩包，不会留下标记为已安装的半成品。
 */
internal class JavaRuntimeInstaller(
    private val store: LauncherStore,
    private val network: NetworkClient,
    private val service: JavaService,
) {
    suspend fun install(
        major: Int,
        context: JobContext,
        imageType: String? = null,
    ): JavaRuntime =
        withContext(Dispatchers.IO) {
            require(major in SUPPORTED_JAVA_MAJORS) {
                "暂不支持自动安装 Java $major，请手动选择已有的 Java"
            }
            val os =
                when (store.platform.osName) {
                    "windows" -> "windows"
                    "linux" -> "linux"
                    else -> "osx"
                }
            val arch =
                when (normalizedJavaArchitecture(store.platform.architecture)) {
                    "arm64" -> "aarch64"
                    "x86" -> "x86"
                    else -> "x64"
                }
            context.progress(
                .02f,
                "查询 Java $major 安装包（${javaDownloadSourceLabel(store.data.settings.effectiveJavaDownloadSource)}）…",
            )
            val packageInfo =
                JavaRuntimePackages(network)
                    .find(major, os, arch, imageType, store.data.settings.effectiveJavaDownloadSource)
            store.log(
                "准备安装 ${packageInfo.vendor} Java $major（${packageInfo.imageType.uppercase()}）" +
                    "，来源：${packageInfo.sourceLabel}" +
                    "：${packageInfo.primaryUrl}"
            )
            val runtimes = service.runtimesDirectory.apply { createDirectories() }
            val downloadDirectory = store.downloadDirectory.resolve("java").apply { createDirectories() }
            val archive =
                downloadDirectory.resolve(
                    packageInfo.fileName.takeIf { it.isNotBlank() }
                        ?: "java-$major-${arch}.${if (os == "windows") "zip" else "tar.gz"}"
                )
            val unpacking = runtimes.resolve(".part-${UUID.randomUUID()}").apply { createDirectories() }
            require(
                downloadDirectory.toFile().usableSpace > packageInfo.size * 3 + 64L * 1024 * 1024
            ) {
                "磁盘空间不足，无法安装 Java $major（需要约 ${formatMegabytes(packageInfo.size * 3)}）"
            }
            // 解压遇到文件系统错误时保留 .part 目录与压缩包，方便排查/重试；其它失败照常清理。
            var keepDiagnostics = false
            try {
                context.progress(
                    .05f,
                    "来源：${packageInfo.sourceLabel} · 准备下载 " +
                        "${packageInfo.vendor} Java ${packageInfo.version}",
                )
                download(packageInfo, archive, context)
                val actual = archive.fileSize()
                // Zulu 元数据的 size 与 CDN 实际字节数可能相差几十字节（zip 注释），SHA-256 才是权威校验；
                // 这里只拒绝空文件，完整性与大小由下载器的 SHA-256 校验保证。
                require(actual > 0) { "下载文件为空，请重试" }
                context.progress(.82f, "解压 Java 运行环境…")
                if (os == "windows") extractZipRuntime(archive, unpacking) { store.log(it) }
                else extractTarRuntime(archive, unpacking) { store.log(it) }
                // Zulu/Temurin 的压缩包通常带一层同名目录；剥掉它，保证 runtimes/<目录> 下直接是 bin/。
                val topLevel = Files.list(unpacking).use { it.toList() }
                val installRoot =
                    topLevel.singleOrNull()?.takeIf { it.isDirectory() && findExecutable(it) != null }
                        ?: unpacking
                val executable =
                    findExecutable(installRoot)
                        ?: error("安装包中没有找到 Java 可执行文件（${store.platform.javaExecutable}）")
                // 原子改名之前确认可执行文件真的落地且可执行，避免把坏目录登记成已安装。
                require(Files.isRegularFile(executable) && Files.isExecutable(executable)) {
                    "解压后的 Java 可执行文件不可运行：$executable（可能被杀毒软件拦截或解压不完整）"
                }
                val found = service.inspect(executable.toString(), verifyRunnable = true)
                    ?: error("下载的 Java 无法运行")
                require(found.majorVersion == major) {
                    "Java 版本校验失败：期望 $major，实际 ${found.majorVersion}"
                }
                require(
                    normalizedJavaArchitecture(found.architecture) ==
                        normalizedJavaArchitecture(store.platform.architecture)
                ) {
                    "Java 架构校验失败：${found.architecture}"
                }
                val directoryName = "${packageInfo.directoryPrefix}-$major-$os-$arch"
                val destination = runtimes.resolve(directoryName).toAbsolutePath().normalize()
                require(destination.parent == runtimes.toAbsolutePath().normalize()) {
                    "Java 安装目录异常"
                }
                if (destination.exists()) {
                    require(!Files.isSymbolicLink(destination)) { "Java 安装目录是符号链接，已拒绝替换" }
                    deleteTree(destination)
                }
                val relativeJava = installRoot.relativize(Path.of(found.path))
                try {
                    Files.move(installRoot, destination, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(installRoot, destination)
                }
                val installedPath = destination.resolve(relativeJava).toString()
                val runtime =
                    found.copy(
                        path = installedPath,
                        source = JavaRuntimeSource.Bundled,
                        vendor = packageInfo.vendor,
                        imageType = packageInfo.imageType,
                        version = found.version.ifBlank { packageInfo.version },
                        installedAt = Instant.now().toString(),
                        sizeBytes = directorySize(destination),
                    )
                JavaRuntimeIndex(runtimes, store.platform.javaExecutable)
                    .upsert(
                        JavaRuntimeEntry(
                            path = destination.fileName.toString(),
                            major = major,
                            bits = found.bits,
                            vendor = packageInfo.vendor,
                            imageType = packageInfo.imageType,
                            version = runtime.version,
                            sourceUrl = packageInfo.sourceUrl,
                            sha256 = packageInfo.sha256,
                            installedAt = runtime.installedAt,
                            os = os,
                            arch = arch,
                            sizeBytes = runtime.sizeBytes,
                        )
                    )
                service.invalidate()
                context.progress(1f, "Java $major 安装完成")
                store.log(
                    "已安装 ${runtime.displayName}（${formatMegabytes(runtime.sizeBytes)}）：${runtime.path}"
                )
                runtime
            } catch (failure: Exception) {
                keepDiagnostics = keepsExtractionDiagnostics(failure)
                store.log("安装 Java $major 失败：${failure.message ?: failure.javaClass.simpleName}", true)
                throw IllegalStateException(
                    javaInstallFailureReason(failure, major, unpacking.takeIf { keepDiagnostics }),
                    failure,
                )
            } finally {
                if (keepDiagnostics) {
                    store.log("已保留解压诊断目录与安装包，便于排查：$unpacking / $archive", true)
                } else {
                    if (unpacking.exists()) deleteTree(unpacking)
                    archive.deleteIfExists()
                }
            }
        }

    private suspend fun download(
        packageInfo: JavaRuntimePackage,
        archive: Path,
        context: JobContext,
    ) {
        var lastError: Exception? = null
        for (url in packageInfo.urls) {
            context.checkCancelled()
            network.discardPartial(archive)
            archive.deleteIfExists()
            context.beginTransfers(1)
            try {
                network.download(
                    DownloadArtifact(
                        url,
                        archive,
                        packageInfo.sha256,
                        // 不传元数据大小：Zulu 的 size 与 CDN 响应可能相差几十字节，避免误杀正确文件。
                        size = 0,
                        hashAlgorithm = "SHA-256",
                    )
                ) { bytes, total ->
                    context.transfer(archive.toString(), bytes, total)
                    context.progress(
                        if (total > 0) .05f + bytes.toFloat() / total * .72f else .3f,
                        "来源：${javaDownloadUrlLabel(url)} · 下载 " +
                            "${packageInfo.vendor} Java ${packageInfo.version} · " +
                            "${bytes / 1024 / 1024} MB",
                    )
                }
                val size = archive.fileSize()
                context.transfer(archive.toString(), size, size, completed = true)
                return
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error
                store.log(
                    "Java 下载源失败（${runCatching { java.net.URI(url).host }.getOrDefault(url)}）：" +
                        (error.message ?: error.javaClass.simpleName),
                    true,
                )
                archive.deleteIfExists()
            }
        }
        throw lastError ?: IllegalStateException("没有可用的 Java 下载源")
    }

    private fun findExecutable(root: Path): Path? =
        runCatching {
                Files.walk(root, 4).use { stream ->
                    stream
                        .filter {
                            it.isRegularFile() &&
                                it.fileName.toString() == store.platform.javaExecutable &&
                                it.parent?.fileName?.toString() == "bin"
                        }
                        .findFirst()
                        .orElse(null)
                }
            }
            .getOrNull()

    private fun deleteTree(root: Path) {
        if (!root.exists()) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun directorySize(directory: Path): Long =
        runCatching {
                Files.walk(directory).use { stream ->
                    stream
                        .filter { Files.isRegularFile(it) }
                        .mapToLong { runCatching { it.fileSize() }.getOrDefault(0) }
                        .sum()
                }
            }
            .getOrDefault(0)

    private fun formatMegabytes(bytes: Long): String = "%.1f MB".format(bytes / 1048576f)

    companion object {
        /** 游戏侧需要的主版本；26.x 版本线使用 25。 */
        val SUPPORTED_JAVA_MAJORS = listOf(8, 11, 16, 17, 21, 25)
    }
}

/** 解压时写不进去：明确是文件系统问题（占用/权限/只读），不能报成网络错误。 */
internal class RuntimeFileWriteException(
    /** 写入失败的具体文件（绝对路径）。 */
    val file: Path,
    cause: Throwable,
) : IOException("无法写入 $file（可能被杀毒软件占用或权限不足）", cause)

/** 解压写入失败时保留 .part 目录与压缩包用于排查；其它失败照常清理临时目录。 */
internal fun keepsExtractionDiagnostics(failure: Throwable): Boolean =
    failure is RuntimeFileWriteException

/** 单次失败后的等待；首次失败后共重试 3 次。 */
private val RUNTIME_WRITE_RETRY_DELAYS_MS = longArrayOf(200, 500, 1000)

internal const val MAX_RUNTIME_EXTRACT_BYTES = 1024L * 1024 * 1024

/**
 * 解压 zip 到 [root]。逐个条目：清只读属性 → 删除同名旧文件 → 建父目录 → 写入；
 * 每个文件最多尝试 4 次（首次 + 3 次重试，间隔 200/500/1000ms），仍失败则抛
 * [RuntimeFileWriteException] 并带上具体路径。
 */
internal fun extractZipRuntime(
    archive: Path,
    root: Path,
    log: (String) -> Unit = {},
) {
    ZipFile(archive.toFile()).use { zip ->
        for ((entry, target) in validateArchive(zip, "", root, MAX_RUNTIME_EXTRACT_BYTES)) {
            writeZipEntry(zip, entry, target, log)
        }
    }
}

private fun writeZipEntry(
    zip: ZipFile,
    entry: ZipEntry,
    target: Path,
    log: (String) -> Unit,
) {
    var lastFailure: Exception? = null
    for (attempt in 0..RUNTIME_WRITE_RETRY_DELAYS_MS.size) {
        if (attempt > 0) {
            val delay = RUNTIME_WRITE_RETRY_DELAYS_MS[attempt - 1]
            log("写入 ${target.fileName} 失败，${delay}ms 后第 $attempt 次重试：${lastFailure?.message}")
            Thread.sleep(delay)
        }
        try {
            target.parent?.createDirectories()
            // 安全软件常常把刚解压出的同名文件锁成只读或直接占用：先清属性再删掉旧文件。
            clearReadOnly(target)
            Files.deleteIfExists(target)
            zip.getInputStream(entry).use { input ->
                Files.newOutputStream(
                        target,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE,
                    )
                    .use { output -> input.copyTo(output) }
            }
            return
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (error: Exception) {
            lastFailure = error
        }
    }
    throw RuntimeFileWriteException(
        target.toAbsolutePath().normalize(),
        lastFailure ?: IOException("写入失败"),
    )
}

/** 清除 Windows 只读属性并尽量恢复可写；其它平台/文件系统不支持时忽略。 */
internal fun clearReadOnly(target: Path) {
    if (!Files.exists(target)) return
    runCatching { Files.setAttribute(target, "dos:readonly", false) }
    runCatching { target.toFile().setWritable(true) }
}

/**
 * 解压 tar.gz 到 [root]。tar 是流式的，条目内容无法回放重试，因此只做与 zip 相同的
 * 「清只读 + 删旧文件」预处理，失败同样归类为文件系统错误。
 */
internal fun extractTarRuntime(
    archive: Path,
    root: Path,
    log: (String) -> Unit = {},
) {
    Files.newInputStream(archive).use { raw ->
        GzipCompressorInputStream(raw).use { gzip ->
            TarArchiveInputStream(gzip).use { tar ->
                var count = 0
                var totalBytes = 0L
                while (true) {
                    val entry = tar.nextEntry ?: break
                    require(++count <= 50_000 && entry.size >= 0) { "Java 安装包结构无效" }
                    totalBytes = Math.addExact(totalBytes, entry.size)
                    require(totalBytes <= MAX_RUNTIME_EXTRACT_BYTES) { "Java 安装包解压后过大" }
                    if (!entry.isFile || entry.isSymbolicLink || entry.isLink) continue
                    val target = safePath(root, entry.name.removePrefix("./"))
                    try {
                        target.parent?.createDirectories()
                        clearReadOnly(target)
                        Files.deleteIfExists(target)
                        Files.copy(tar, target, StandardCopyOption.REPLACE_EXISTING)
                        runCatching {
                            Files.setPosixFilePermissions(
                                target,
                                PosixFilePermissions.fromString(
                                    if (entry.mode and 0b001001001 != 0) "rwxr-xr-x"
                                    else "rw-r--r--"
                                ),
                            )
                        }
                    } catch (error: Exception) {
                        log("写入 ${target.fileName} 失败：${error.message}")
                        throw RuntimeFileWriteException(
                            target.toAbsolutePath().normalize(),
                            error,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 失败原因分类：文件系统错误绝不能报成「网络错误」。
 * [diagnosticRoot] 非空时附上保留的诊断目录。
 */
internal fun javaInstallFailureReason(
    failure: Throwable,
    major: Int,
    diagnosticRoot: Path? = null,
): String {
    val message = failure.message.orEmpty()
    val reason =
        when {
            failure is RuntimeFileWriteException -> "文件写入失败"
            failure is DownloadVerificationException || message.contains("校验") -> "校验失败"
            message.contains("空间不足") || message.contains("No space", true) -> "磁盘空间不足"
            failure is java.nio.file.FileSystemException -> "文件写入失败"
            failure is IOException || message.contains("下载") || message.contains("连接") -> "网络错误"
            else -> "安装失败"
        }
    val base = "Java $major 安装失败（$reason）：" + (failure.message ?: failure.javaClass.simpleName)
    if (reason != "文件写入失败") return base
    return base +
        "。请把 MoliFox\\runtimes 加入杀毒软件白名单后重试" +
        (diagnosticRoot?.let { "；诊断目录已保留：$it" } ?: "")
}