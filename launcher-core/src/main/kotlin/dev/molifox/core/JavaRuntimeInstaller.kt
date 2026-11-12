// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责把 Zulu/Temurin 运行时下载、校验、解压并登记到 MoliFox/runtimes/。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
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
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize

/** 查询安装包阶段的进度。 */
private const val PROGRESS_QUERYING_PACKAGE = .02f

/** 准备下载阶段的进度。 */
private const val PROGRESS_PREPARING_DOWNLOAD = .05f

/** 解压阶段的进度。 */
private const val PROGRESS_EXTRACTING = .82f

/** 下载进度在整体进度中的起点。 */
private const val PROGRESS_DOWNLOAD_BASE = .05f

/** 下载进度在整体进度中占用的区间。 */
private const val PROGRESS_DOWNLOAD_SPAN = .72f

/** 无法得知总大小时的兜底进度。 */
private const val PROGRESS_DOWNLOAD_FALLBACK = .3f

/** 磁盘预留：安装包大小按此倍数估算。 */
private const val DISK_SPACE_FACTOR = 3L

/** 磁盘预留：安装包之外额外需要的余量。 */
private const val DISK_SPACE_HEADROOM_BYTES = 64L * 1024 * 1024

/** 每 MiB 的字节数；下载进度与容量展示共用。 */
private const val BYTES_PER_MEBIBYTE = 1024L * 1024

/** tar 包允许的最大条目数，避免异常包耗尽资源。 */
private const val MAX_TAR_ENTRIES = 50_000

/** 解压后允许的最大字节数。 */
internal const val MAX_RUNTIME_EXTRACT_BYTES = 1024L * 1024 * 1024

/** tar 条目权限位里代表「可执行」的掩码。 */
private const val EXECUTABLE_PERMISSION_MASK = 0b001001001

/** 单次失败后的等待；首次失败后共重试 3 次。 */
private val RUNTIME_WRITE_RETRY_DELAYS_MS = longArrayOf(200, 500, 1000)

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
                I18n.t("core.javaRuntimeInstaller.1", major)
            }
            val plan = prepareRuntimePlan(major, context, imageType)
            // 解压遇到文件系统错误时保留 .part 目录与压缩包，方便排查/重试；其它失败照常清理。
            var keepDiagnostics = false
            try {
                runCatching { installArchive(plan, context) }.getOrElse { failure ->
                    // 安装是回滚边界：任何异常都要记录、分类原因并清理本次临时产物，故此处必须兜底。
                    keepDiagnostics = keepsExtractionDiagnostics(failure)
                    store.log(
                        I18n.t(
                            "networkB1.javaRuntimeInstaller.installFailedLog",
                            major,
                            failure.message ?: failure.javaClass.simpleName,
                        ),
                        true,
                    )
                    val diagnosticRoot = plan.unpacking.takeIf { keepDiagnostics }
                    throw IllegalStateException(
                        javaInstallFailureReason(failure, major, diagnosticRoot),
                        failure,
                    )
                }
            } finally {
                if (keepDiagnostics) {
                    store.log(
                        I18n.t(
                            "networkB1.javaRuntimeInstaller.diagnosticsKept",
                            plan.unpacking,
                            plan.archive,
                        ),
                        true,
                    )
                } else {
                    if (plan.unpacking.exists()) deleteTree(plan.unpacking)
                    plan.archive.deleteIfExists()
                }
            }
        }

    /** 选好安装包，并把下载目录、安装目录、临时解压目录与磁盘余量检查一次做完。 */
    private suspend fun prepareRuntimePlan(
        major: Int,
        context: JobContext,
        imageType: String?,
    ): RuntimeInstallPlan {
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
        val packageInfo = resolveRuntimePackage(major, context, os, arch, imageType)
        val runtimes = service.runtimesDirectory.apply { createDirectories() }
        val downloadDirectory =
            store.downloadDirectory.resolve("java").apply { createDirectories() }
        val fallbackName = "java-$major-$arch.${if (os == "windows") "zip" else "tar.gz"}"
        val archive =
            downloadDirectory.resolve(
                packageInfo.fileName.takeIf { it.isNotBlank() } ?: fallbackName,
            )
        val unpacking =
            runtimes.resolve(".part-${UUID.randomUUID()}").apply { createDirectories() }
        require(
            downloadDirectory.toFile().usableSpace >
                packageInfo.size * DISK_SPACE_FACTOR + DISK_SPACE_HEADROOM_BYTES,
        ) {
            I18n.t(
                "networkB1.javaRuntimeInstaller.diskSpace",
                major,
                megabytesText(packageInfo.size * DISK_SPACE_FACTOR),
            )
        }
        return RuntimeInstallPlan(major, os, arch, packageInfo, runtimes, archive, unpacking)
    }

    /** 查询厂商元数据并留下安装来源日志。 */
    private suspend fun resolveRuntimePackage(
        major: Int,
        context: JobContext,
        os: String,
        arch: String,
        imageType: String?,
    ): JavaRuntimePackage {
        context.progress(
            PROGRESS_QUERYING_PACKAGE,
            I18n.t(
                "core.javaRuntimeInstaller.2",
                major,
                javaDownloadSourceLabel(
                    store.data.settings.effectiveJavaDownloadSource,
                ),
            ),
        )
        val packageInfo =
            JavaRuntimePackages(network)
                .find(major, os, arch, imageType, store.data.settings.effectiveJavaDownloadSource)
        store.log(
            I18n.t(
                "networkB1.javaRuntimeInstaller.preparingInstallLog",
                packageInfo.vendor,
                major,
                packageInfo.imageType.uppercase(),
                packageInfo.sourceLabel,
                packageInfo.primaryUrl,
            ),
        )
        return packageInfo
    }

    /** 下载并解压到临时目录，然后交给登记步骤。 */
    private suspend fun installArchive(
        plan: RuntimeInstallPlan,
        context: JobContext,
    ): JavaRuntime {
        context.progress(
            PROGRESS_PREPARING_DOWNLOAD,
            I18n.t(
                "networkB1.javaRuntimeInstaller.preparingDownload",
                plan.packageInfo.sourceLabel,
                plan.packageInfo.vendor,
                plan.packageInfo.version,
            ),
        )
        download(plan.packageInfo, plan.archive, context)
        val actual = plan.archive.fileSize()
        // Zulu 元数据的 size 与 CDN 实际字节数可能相差几十字节（zip 注释），SHA-256 才是权威校验；
        // 这里只拒绝空文件，完整性与大小由下载器的 SHA-256 校验保证。
        require(actual > 0) { I18n.t("core.javaRuntimeInstaller.3") }
        context.progress(PROGRESS_EXTRACTING, I18n.t("core.javaRuntimeInstaller.4"))
        if (plan.os == "windows") {
            extractZipRuntime(plan.archive, plan.unpacking) { store.log(it) }
        } else {
            extractTarRuntime(plan.archive, plan.unpacking) { store.log(it) }
        }
        return registerRuntime(plan, stageRuntime(plan), context)
    }

    /** 探测可执行文件、校验版本与架构，并把临时目录原子改名到最终位置。 */
    private suspend fun stageRuntime(plan: RuntimeInstallPlan): StagedRuntime {
        // Zulu/Temurin 的压缩包通常带一层同名目录；剥掉它，保证 runtimes/<目录> 下直接是 bin/。
        val installRoot = resolveInstallRoot(plan.unpacking, store.platform.javaExecutable)
        val executable =
            findExecutable(installRoot, store.platform.javaExecutable)
                ?: error(I18n.t("core.javaRuntimeInstaller.5", store.platform.javaExecutable))
        // 原子改名之前确认可执行文件真的落地且可执行，避免把坏目录登记成已安装。
        require(Files.isRegularFile(executable) && Files.isExecutable(executable)) {
            I18n.t("core.javaRuntimeInstaller.6", executable)
        }
        val found =
            service.inspect(executable.toString(), verifyRunnable = true)
                ?: error(I18n.t("core.javaRuntimeInstaller.7"))
        verifyStagedRuntime(plan, found, store.platform)
        val destination = prepareDestination(plan) { deleteTree(it) }
        val relativeJava = installRoot.relativize(Path.of(found.path))
        try {
            Files.move(installRoot, destination, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(installRoot, destination)
        }
        return StagedRuntime(destination, destination.resolve(relativeJava), found)
    }

    /** 把已就位的运行时写入 index.json，并上报完成进度与日志。 */
    private suspend fun registerRuntime(
        plan: RuntimeInstallPlan,
        staged: StagedRuntime,
        context: JobContext,
    ): JavaRuntime {
        val runtime =
            staged.info.copy(
                path = staged.executable.toString(),
                source = JavaRuntimeSource.Bundled,
                vendor = plan.packageInfo.vendor,
                imageType = plan.packageInfo.imageType,
                version = staged.info.version.ifBlank { plan.packageInfo.version },
                installedAt = Instant.now().toString(),
                sizeBytes = directorySize(staged.directory),
            )
        JavaRuntimeIndex(plan.runtimes, store.platform.javaExecutable).upsert(
            JavaRuntimeEntry(
                path = staged.directory.fileName.toString(),
                major = plan.major,
                bits = runtime.bits,
                vendor = plan.packageInfo.vendor,
                imageType = plan.packageInfo.imageType,
                version = runtime.version,
                sourceUrl = plan.packageInfo.sourceUrl,
                sha256 = plan.packageInfo.sha256,
                installedAt = runtime.installedAt,
                os = plan.os,
                arch = plan.arch,
                sizeBytes = runtime.sizeBytes,
            ),
        )
        service.invalidate()
        context.progress(1f, I18n.t("core.javaRuntimeInstaller.8", plan.major))
        val sizeLabel = megabytesText(runtime.sizeBytes)
        store.log(I18n.t("core.javaRuntimeInstaller.9", runtime.displayName, sizeLabel, runtime.path))
        return runtime
    }

    /** 同一运行时的候选地址共用摘要验证和回退。 */
    private suspend fun download(
        packageInfo: JavaRuntimePackage,
        archive: Path,
        context: JobContext,
    ) {
        val url = packageInfo.urls.firstOrNull() ?: error(I18n.t("core.javaRuntimeInstaller.10"))
        network.downloadTracked(
            context,
            // 部分运行时元数据大小与实际 CDN 响应有偏差，归档以 SHA-256 校验。
            DownloadArtifact(url, archive, packageInfo.sha256, hashAlgorithm = "SHA-256"),
            packageInfo.urls,
            downloadProgress(packageInfo, context),
        )
    }

    /** 下载进度回调：显示运行时版本和已接收的字节。 */
    private fun downloadProgress(
        packageInfo: JavaRuntimePackage,
        context: JobContext,
    ): (Long, Long) -> Unit =
        { bytes, total ->
            context.progress(
                if (total > 0) {
                    PROGRESS_DOWNLOAD_BASE + bytes.toFloat() / total * PROGRESS_DOWNLOAD_SPAN
                } else {
                    PROGRESS_DOWNLOAD_FALLBACK
                },
                I18n.t(
                    "networkB1.javaRuntimeInstaller.downloading",
                    packageInfo.vendor,
                    packageInfo.version,
                    bytes / BYTES_PER_MEBIBYTE,
                ),
            )
        }

    companion object {
        /** 游戏侧需要的主版本；26.x 版本线使用 25，27 起新版本线直接用 27。 */
        val SUPPORTED_JAVA_MAJORS = listOf(8, 11, 16, 17, 21, 25, 27)
    }
}

/** 一次安装所需的路径与元数据快照。 */
internal data class RuntimeInstallPlan(
    val major: Int,
    val os: String,
    val arch: String,
    val packageInfo: JavaRuntimePackage,
    val runtimes: Path,
    val archive: Path,
    val unpacking: Path,
) {
    /** 安装目录名：`<供应商>-<主版本>-<os>-<arch>`。 */
    val directoryName: String = "${packageInfo.directoryPrefix}-$major-$os-$arch"
}

/** 已解压、已校验并原子改名到最终位置的运行时。 */
private class StagedRuntime(
    val directory: Path,
    val executable: Path,
    val info: JavaRuntime,
)

/** 解压时写不进去：明确是文件系统问题（占用/权限/只读），不能报成网络错误。 */
internal class RuntimeFileWriteException(
    /** 写入失败的具体文件（绝对路径）。 */
    val file: Path,
    cause: Throwable,
) : IOException(I18n.t("core.javaRuntimeInstaller.11", file), cause)

/** 解压写入失败时保留 .part 目录与压缩包用于排查；其它失败照常清理临时目录。 */
internal fun keepsExtractionDiagnostics(failure: Throwable): Boolean = failure is RuntimeFileWriteException

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
    var lastFailure: IOException? = null
    for (attempt in 0..RUNTIME_WRITE_RETRY_DELAYS_MS.size) {
        if (attempt > 0) {
            val delay = RUNTIME_WRITE_RETRY_DELAYS_MS[attempt - 1]
            log(
                I18n.t(
                    "networkB1.javaRuntimeInstaller.retryingWrite",
                    target.fileName,
                    delay,
                    attempt,
                    lastFailure?.message,
                ),
            )
            Thread.sleep(delay)
        }
        try {
            writeEntryBytes(zip, entry, target)
            return
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (error: IOException) {
            lastFailure = error
        }
    }
    throw RuntimeFileWriteException(
        target.toAbsolutePath().normalize(),
        lastFailure ?: IOException(I18n.t("core.javaRuntimeInstaller.12")),
    )
}

/** 写入单个 zip 条目：先建父目录，再清只读属性并覆盖旧文件。 */
private fun writeEntryBytes(
    zip: ZipFile,
    entry: ZipEntry,
    target: Path,
) {
    target.parent?.createDirectories()
    // 安全软件常常把刚解压出的同名文件锁成只读或直接占用：先清属性再删掉旧文件。
    clearReadOnly(target)
    Files.deleteIfExists(target)
    zip.getInputStream(entry).use { input ->
        Files
            .newOutputStream(
                target,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            ).use { output -> input.copyTo(output) }
    }
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
                copyTarEntries(tar, root, log)
            }
        }
    }
}

/** 遍历 tar 条目：先累加计数器与解压后大小，再写入普通文件条目。 */
private fun copyTarEntries(
    tar: TarArchiveInputStream,
    root: Path,
    log: (String) -> Unit,
) {
    var count = 0
    var totalBytes = 0L
    while (true) {
        val entry = tar.nextEntry ?: break
        count += 1
        require(count <= MAX_TAR_ENTRIES && entry.size >= 0) { I18n.t("core.javaRuntimeInstaller.13") }
        totalBytes = Math.addExact(totalBytes, entry.size)
        require(totalBytes <= MAX_RUNTIME_EXTRACT_BYTES) { I18n.t("core.javaRuntimeInstaller.14") }
        if (entry.isFile && !entry.isSymbolicLink && !entry.isLink) {
            writeTarEntry(tar, entry, root, log)
        }
    }
}

/** 写入单个 tar 条目；失败时带上目标路径并归类为文件系统错误。 */
private fun writeTarEntry(
    tar: TarArchiveInputStream,
    entry: TarArchiveEntry,
    root: Path,
    log: (String) -> Unit,
) {
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
                    if (entry.mode and EXECUTABLE_PERMISSION_MASK != 0) {
                        "rwxr-xr-x"
                    } else {
                        "rw-r--r--"
                    },
                ),
            )
        }
    } catch (error: IOException) {
        log(
            I18n.t(
                "networkB1.javaRuntimeInstaller.writeFailed",
                target.fileName,
                error.message,
            ),
        )
        throw RuntimeFileWriteException(
            target.toAbsolutePath().normalize(),
            error,
        )
    }
}

/** 安装失败的原因分类；文案由资源提供，逻辑只比较枚举。 */
private enum class JavaInstallFailureKind(
    val messageKey: String,
) {
    FILE_WRITE("core.javaRuntimeInstaller.failure.fileWrite"),
    VERIFICATION("core.javaRuntimeInstaller.failure.verification"),
    DISK_FULL("core.javaRuntimeInstaller.failure.diskFull"),
    NETWORK("core.javaRuntimeInstaller.failure.network"),
    UNKNOWN("core.javaRuntimeInstaller.failure.unknown"),
}

/**
 * 把安装失败翻译成用户可读的原因分类；文件系统错误绝不能报成网络错误。
 * `message.contains` 里的中文只用于兼容旧日志与外部异常文本，是兜底路径；
 * 正常路径由异常类型与 [JavaInstallFailureKind] 判定。
 */
private fun classifyInstallFailure(failure: Throwable): JavaInstallFailureKind {
    val message = failure.message.orEmpty()
    return when {
        failure is RuntimeFileWriteException -> JavaInstallFailureKind.FILE_WRITE
        failure is DownloadVerificationException || message.contains("校验") -> JavaInstallFailureKind.VERIFICATION
        message.contains("空间不足") || message.contains("No space", true) -> JavaInstallFailureKind.DISK_FULL
        failure is java.nio.file.FileSystemException -> JavaInstallFailureKind.FILE_WRITE
        failure is IOException || message.contains("下载") || message.contains("连接") -> JavaInstallFailureKind.NETWORK
        else -> JavaInstallFailureKind.UNKNOWN
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
    val kind = classifyInstallFailure(failure)
    val reason = I18n.t(kind.messageKey)
    val detail = failure.message ?: failure.javaClass.simpleName
    if (kind != JavaInstallFailureKind.FILE_WRITE) {
        return I18n.t("core.javaRuntimeInstaller.failed", major, reason, detail)
    }
    return if (diagnosticRoot == null) {
        I18n.t("core.javaRuntimeInstaller.failedFileWrite", major, reason, detail)
    } else {
        I18n.t("core.javaRuntimeInstaller.failedFileWriteWithDiagnostics", major, reason, detail, diagnosticRoot)
    }
}
