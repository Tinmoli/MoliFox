// 下载、校验并安装 EasyTier sidecar：官方 zip + SHA-256，失败不留下半成品。
package dev.molifox.multiplayer.easytier

import dev.molifox.multiplayer.common.CoreStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** 下载官方安装包；测试注入假实现，生产环境走 HTTP。 */
interface EasyTierArchiveDownloader {
    /**
     * 下载单个来源。
     *
     * @param abortWhenSlow 还有其它来源可试时置 true：长时间明显偏慢会提前放弃当前源，
     *        避免在慢速源上无谓等待；最后一个来源不启用，慢但能下完也比失败好。
     */
    suspend fun download(
        url: String,
        target: Path,
        expectedBytes: Long,
        onProgress: (Float?) -> Unit,
        abortWhenSlow: Boolean = false,
    )
}

/**
 * EasyTier sidecar 安装器：目标目录 `<运行数据>/runtimes/easytier/<版本>/<平台>/`。
 *
 * 规则：
 * - 已安装且必需文件齐全时不再下载。
 * - 下载后先校验大小与 SHA-256，再解包到临时目录，最后整体移动到目标目录。
 * - 任何一步失败都清理临时目录与未完成的目标目录。
 */
class EasyTierInstaller(
    private val runtimesRoot: Path,
    private val logger: (message: String, error: Boolean) -> Unit = { _, _ -> },
    private val packageProvider: () -> EasyTierPackage? = EasyTierCatalog::current,
    private val downloader: EasyTierArchiveDownloader = HttpArchiveDownloader(),
    private val probe: EasyTierSourceProbe = HttpEasyTierSourceProbe(),
) {
    /** 当前平台的安装包信息；不支持的平台给出明确提示。 */
    fun packageInfo(): EasyTierPackage =
        packageProvider()
            ?: throw IllegalStateException(EasyTierCatalog.unsupportedPlatformMessage())

    /** 安装目录：`runtimes/easytier/<版本>/<平台>/`。 */
    fun directory(pkg: EasyTierPackage = packageInfo()): Path =
        runtimesRoot.resolve(RUNTIME_DIRECTORY).resolve(pkg.version).resolve(pkg.classifier)

    /** 主程序（`easytier-core`），后续 backend 用它启动 sidecar。 */
    fun executable(pkg: EasyTierPackage = packageInfo()): Path =
        directory(pkg).resolve(pkg.coreFile)

    /** 命令行工具（`easytier-cli`），后续 backend 用它查询 peer 与添加端口转发。 */
    fun cliExecutable(pkg: EasyTierPackage = packageInfo()): Path =
        directory(pkg).resolve(pkg.cliFile)

    /** 检查本地安装是否可用：只校验必需文件存在且非空，不读网络。 */
    fun inspect(pkg: EasyTierPackage = packageInfo()): CoreStatus {
        val target = directory(pkg)
        val installed = pkg.requiredFiles.all { name -> isUsableFile(target.resolve(name)) }
        return CoreStatus(
            installed = installed,
            version = pkg.version,
            platform = pkg.classifier,
            downloadBytes = pkg.downloadBytes,
            directory = target.toString(),
        )
    }

    /** 下载并安装；[onProgress] 收到 0..1 的进度，未知时为 null。 */
    suspend fun install(onProgress: (Float?) -> Unit = {}): Result<CoreStatus> =
        withContext(Dispatchers.IO) {
            runCatching {
                val pkg = packageInfo()
                val current = inspect(pkg)
                if (current.installed) {
                    onProgress(ONE)
                    return@runCatching current
                }
                Files.createDirectories(runtimesRoot)
                val work = Files.createTempDirectory(runtimesRoot, WORK_PREFIX)
                try {
                    installInto(pkg, work, onProgress)
                } finally {
                    deleteRecursively(work, logger)
                }
            }.onFailure { failure ->
                // runCatching 会吞掉取消信号，这里恢复协程取消语义。
                if (failure is CancellationException) throw failure
            }
        }

    /**
     * 从本地 zip 安装（用户手动下载或通过代理下载后导入）。
     *
     * 与在线安装走同一套校验与解包流程：大小、SHA-256、必需文件，失败不留下半成品。
     */
    suspend fun installFromArchive(
        archive: Path,
        onProgress: (Float?) -> Unit = {},
    ): Result<CoreStatus> =
        withContext(Dispatchers.IO) {
            runCatching {
                val pkg = packageInfo()
                require(Files.isRegularFile(archive)) { "找不到安装包：$archive" }
                logger("从本地安装包安装 EasyTier：$archive", false)
                val work = Files.createTempDirectory(runtimesRoot, WORK_PREFIX)
                try {
                    verifyArchive(pkg, archive)
                    onProgress(null)
                    val staging = work.resolve(STAGING_DIRECTORY)
                    ZipExtractor.extract(archive, staging, pkg.rootPrefix)
                    onProgress(SIXTY)
                    requireRequiredFiles(pkg, staging)
                    moveIntoPlace(staging, directory(pkg))
                    val status = inspect(pkg)
                    require(status.installed) { "EasyTier 安装完成后仍缺少必需文件，请检查安装包。" }
                    logger("EasyTier ${pkg.version} 已从本地安装包安装到 ${status.directory}", false)
                    onProgress(ONE)
                    status
                } finally {
                    deleteRecursively(work, logger)
                }
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
            }
        }

    /** 在临时目录内完成下载、校验、解包与落盘；失败时由 [install] 清理临时目录。 */
    private suspend fun installInto(
        pkg: EasyTierPackage,
        work: Path,
        onProgress: (Float?) -> Unit,
    ): CoreStatus {
        val archive = work.resolve(ARCHIVE_NAME)
        downloadVerified(pkg, archive, onProgress)
        val staging = work.resolve(STAGING_DIRECTORY)
        ZipExtractor.extract(archive, staging, pkg.rootPrefix)
        requireRequiredFiles(pkg, staging)
        moveIntoPlace(staging, directory(pkg))
        val status = inspect(pkg)
        require(status.installed) { "EasyTier 安装完成后仍缺少必需文件，请重试。" }
        logger("EasyTier ${pkg.version} 已安装到 ${status.directory}", false)
        onProgress(ONE)
        return status
    }

    /**
     * 按测速结果依次尝试官方与镜像来源；来源来自不同 CDN 但文件完全相同。
     * 总尝试次数固定为 [ATTEMPTS]，来源不足时循环使用，避免无上限重试。
     */
    private suspend fun downloadVerified(
        pkg: EasyTierPackage,
        target: Path,
        onProgress: (Float?) -> Unit,
    ) {
        val sources = orderedSources(pkg, probe, logger)
        logger("EasyTier 下载源顺序：${sources.joinToString()}", false)
        var failure: Throwable? = null
        repeat(ATTEMPTS) { attempt ->
            val source = sources[attempt % sources.size]
            failure = tryDownloadSource(source, attempt, sources.size, pkg, target, onProgress)
            if (failure == null) return
        }
        val reason = failure?.message.orEmpty()
        throw IOException("下载 EasyTier 失败：$reason。已尝试 ${sources.size} 个下载源，请检查网络后重试。", failure)
    }

    /** 尝试一个来源；成功返回 null，失败返回异常并记录可读日志。 */
    private suspend fun tryDownloadSource(
        source: String,
        attempt: Int,
        sourceCount: Int,
        pkg: EasyTierPackage,
        target: Path,
        onProgress: (Float?) -> Unit,
    ): Throwable? =
        try {
            logger(
                "下载 EasyTier（第 ${attempt + 1}/$ATTEMPTS 次，${attempt + 1}/$sourceCount 个源）：$source",
                false,
            )
            onProgress(null)
            var lastLoggedPercent = -1
            val reportProgress: (Float?) -> Unit = { progress ->
                onProgress(progress)
                val percent = progress?.let { (it * PERCENT).toInt().coerceIn(0, PERCENT) }
                if (percent != null && percent >= lastLoggedPercent + PROGRESS_LOG_STEP) {
                    lastLoggedPercent = percent
                    logger("EasyTier 下载进度 $percent%", false)
                }
            }
            val abortWhenSlow = attempt + 1 < ATTEMPTS && sourceCount > 1
            downloader.download(source, target, pkg.downloadBytes, reportProgress, abortWhenSlow)
            verifyArchive(pkg, target)
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (downloadFailure: IOException) {
            logger("EasyTier 来源失败（$source）：${downloadFailure.message}", true)
            downloadFailure
        } catch (downloadFailure: IllegalStateException) {
            logger("EasyTier 来源校验失败（$source）：${downloadFailure.message}", true)
            downloadFailure
        }

    /** 解包结果整体替换目标目录，并补上 Linux 可执行权限。 */
    private fun moveIntoPlace(
        staging: Path,
        target: Path,
    ) {
        target.parent?.let { Files.createDirectories(it) }
        deleteRecursively(target, logger)
        runCatching { Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE) }
            .recoverCatching { Files.move(staging, target) }
            .getOrThrow()
        applyExecutablePermissions(target, logger)
    }
}

/** 用 HTTP 下载安装包，按 64 KiB 上报进度。 */
class HttpArchiveDownloader(
    private val connectTimeoutMillis: Int = CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = READ_TIMEOUT_MILLIS,
) : EasyTierArchiveDownloader {
    override suspend fun download(
        url: String,
        target: Path,
        expectedBytes: Long,
        onProgress: (Float?) -> Unit,
        abortWhenSlow: Boolean,
    ) {
        coroutineContext.ensureActive()
        withHttpConnection(url) { connection ->
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.setRequestProperty("User-Agent", EASY_TIER_USER_AGENT)
            val code = connection.responseCode
            check(code in HTTP_OK) { "HTTP $code" }
            val totalBytes = connection.contentLengthLong.takeIf { it > 0 } ?: expectedBytes
            val pace = DownloadPaceGuard()
            connection.inputStream.use { input ->
                Files.newOutputStream(target).use { output ->
                    transfer(input, output, totalBytes, onProgress, pace, abortWhenSlow)
                }
            }
        }
    }

    private suspend fun transfer(
        input: InputStream,
        output: OutputStream,
        totalBytes: Long,
        onProgress: (Float?) -> Unit,
        pace: DownloadPaceGuard,
        abortWhenSlow: Boolean,
    ) {
        val buffer = ByteArray(BUFFER_BYTES)
        var received = 0L
        var reported = 0L
        while (true) {
            coroutineContext.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            received += count
            if (abortWhenSlow && pace.tooSlow(received, totalBytes)) {
                throw IOException("当前来源下载过慢，换用其它下载源")
            }
            if (totalBytes > 0 && received - reported >= PROGRESS_STEP_BYTES) {
                reported = received
                onProgress((received.toFloat() / totalBytes).coerceIn(ZERO, ONE))
            }
        }
        onProgress(ONE)
    }
}

/** 纯按实测速度降序选择下载源，不偏向官方源；测速失败的来源保留在末尾兜底。 */
private suspend fun orderedSources(
    pkg: EasyTierPackage,
    probe: EasyTierSourceProbe,
    logger: (message: String, error: Boolean) -> Unit,
): List<String> {
    val candidates = (listOf(pkg.url) + pkg.mirrors).distinct()
    val measured =
        candidates.mapNotNull { source ->
            probe.measureSpeed(source)?.let {
                source to
                    it
            }
        }
    measured.forEach { (source, speed) ->
        logger("下载源测速：${speed / KIB} KiB/s  $source", false)
    }
    val ranked = measured.sortedByDescending { it.second }.map { it.first }
    val unscored = candidates.filterNot { it in ranked }
    unscored.forEach { source -> logger("下载源未测速（作为兜底）：$source", false) }
    return ranked + unscored
}

/** 校验安装包大小与 SHA-256。 */
private fun verifyArchive(
    pkg: EasyTierPackage,
    archive: Path,
) {
    val actualBytes = Files.size(archive)
    check(actualBytes == pkg.downloadBytes) {
        "EasyTier 安装包大小不符（$actualBytes ≠ ${pkg.downloadBytes} 字节），请重新下载"
    }
    check(sha256(archive) == pkg.sha256.lowercase(Locale.ROOT)) {
        "EasyTier 安装包校验失败（SHA-256 不一致），请重新下载"
    }
}

/** 确认解包结果里必需文件都在。 */
private fun requireRequiredFiles(
    pkg: EasyTierPackage,
    staging: Path,
) {
    val missing = pkg.requiredFiles.filterNot { name -> isUsableFile(staging.resolve(name)) }
    require(missing.isEmpty()) { "EasyTier 安装包缺少必需文件：${missing.joinToString()}" }
}

/** Linux 上补可执行权限；Windows 忽略。 */
private fun applyExecutablePermissions(
    root: Path,
    logger: (message: String, error: Boolean) -> Unit,
) {
    if (isWindows()) return
    Files.walk(root).use { stream ->
        stream.filter { Files.isRegularFile(it) }.forEach { file ->
            runCatching { Files.setPosixFilePermissions(file, EXECUTABLE_PERMISSIONS) }
                .onFailure { failure ->
                    logger("无法为 ${file.fileName} 设置可执行权限：${failure.message}", true)
                }
        }
    }
}

private fun isUsableFile(file: Path): Boolean =
    runCatching { Files.isRegularFile(file) && Files.size(file) > 0 }.getOrDefault(false)

private fun sha256(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(file).use { input ->
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun deleteRecursively(
    path: Path,
    logger: (message: String, error: Boolean) -> Unit,
) {
    if (!Files.exists(path)) return
    Files.walk(path).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach { child ->
            runCatching { Files.deleteIfExists(child) }
                .onFailure { failure -> logger("清理安装临时文件失败：${failure.message}", true) }
        }
    }
}

private fun isWindows(): Boolean {
    val os = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
    return os.startsWith("windows")
}

private const val RUNTIME_DIRECTORY = "easytier"
private const val WORK_PREFIX = "easytier-install-"
private const val STAGING_DIRECTORY = "staging"
private const val ARCHIVE_NAME = "easytier.zip"
private const val ATTEMPTS = 3
private const val CONNECT_TIMEOUT_MILLIS = 20_000
private const val READ_TIMEOUT_MILLIS = 30_000
private const val BUFFER_BYTES = 64 * 1024
private const val SIXTY = 0.6f
private const val PROGRESS_STEP_BYTES = 512L * 1024
private const val PROGRESS_LOG_STEP = 10
private const val PERCENT = 100
private const val KIB = 1024L
private val HTTP_OK = 200..299
private const val ONE = 1f
private const val ZERO = 0f
private val EXECUTABLE_PERMISSIONS: Set<PosixFilePermission> =
    PosixFilePermissions.fromString("rwxr-xr-x")
