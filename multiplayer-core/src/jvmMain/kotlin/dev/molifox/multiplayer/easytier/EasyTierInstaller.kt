// 下载、校验并安装 EasyTier sidecar：官方 zip + SHA-256，失败不留下半成品。
package dev.molifox.multiplayer.easytier

import dev.molifox.core.i18n.I18n
import dev.molifox.multiplayer.common.CoreStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    /** 下载单个来源；失败（连接错误、超时、数据不完整）时由安装器换下一个源。 */
    suspend fun download(
        url: String,
        target: Path,
        expectedBytes: Long,
        onProgress: (Float?) -> Unit,
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
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val rankingCache = SourceRankingCache()

    /** 当前平台的安装包信息；不支持的平台给出明确提示。 */
    fun packageInfo(): EasyTierPackage =
        packageProvider()
            ?: throw IllegalStateException(EasyTierCatalog.unsupportedPlatformMessage())

    /** 安装目录：`runtimes/easytier/<版本>/<平台>/`。 */
    fun directory(pkg: EasyTierPackage = packageInfo()): Path =
        runtimesRoot.resolve(RUNTIME_DIRECTORY).resolve(pkg.version).resolve(pkg.classifier)

    /** 主程序（`easytier-core`），后续 backend 用它启动 sidecar。 */
    fun executable(pkg: EasyTierPackage = packageInfo()): Path = directory(pkg).resolve(pkg.coreFile)

    /** 命令行工具（`easytier-cli`），后续 backend 用它查询 peer 与添加端口转发。 */
    fun cliExecutable(pkg: EasyTierPackage = packageInfo()): Path = directory(pkg).resolve(pkg.cliFile)

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
                require(Files.isRegularFile(archive)) { I18n.t("multiplayer.easyTierInstaller.1", archive) }
                logger(I18n.t("multiplayer.easyTierInstaller.2", archive), false)
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
                    require(status.installed) { I18n.t("multiplayer.easyTierInstaller.3") }
                    logger(I18n.t("multiplayer.easyTierInstaller.4", pkg.version, status.directory), false)
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
        require(status.installed) { I18n.t("multiplayer.easyTierInstaller.5") }
        logger(I18n.t("multiplayer.easyTierInstaller.6", pkg.version, status.directory), false)
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
        val sources = orderedSources(pkg, probe, logger, rankingCache, clock())
        logger(I18n.t("multiplayerB.easyTierInstaller.sourceOrder", sources.joinToString()), false)
        var failure: Throwable? = null
        repeat(ATTEMPTS) { attempt ->
            val source = sources[attempt % sources.size]
            failure = tryDownloadSource(source, attempt, sources.size, pkg, target, onProgress)
            if (failure == null) return
        }
        val reason = failure?.message.orEmpty()
        throw IOException(I18n.t("multiplayer.easyTierInstaller.7", reason, sources.size), failure)
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
                I18n.t(
                    "multiplayerB.easyTierInstaller.attempt",
                    attempt + 1,
                    ATTEMPTS,
                    attempt + 1,
                    sourceCount,
                    source,
                ),
                false,
            )
            onProgress(null)
            var lastLoggedPercent = -1
            val reportProgress: (Float?) -> Unit = { progress ->
                onProgress(progress)
                val percent = progress?.let { (it * PERCENT).toInt().coerceIn(0, PERCENT) }
                if (percent != null && percent >= lastLoggedPercent + PROGRESS_LOG_STEP) {
                    lastLoggedPercent = percent
                    logger(I18n.t("multiplayerB.easyTierInstaller.downloadProgress", percent), false)
                }
            }
            downloader.download(source, target, pkg.downloadBytes, reportProgress)
            verifyArchive(pkg, target)
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (downloadFailure: IOException) {
            logger(
                I18n.t("multiplayerB.easyTierInstaller.sourceFailed", source, downloadFailure.message),
                true,
            )
            downloadFailure
        } catch (downloadFailure: IllegalStateException) {
            logger(
                I18n.t("multiplayerB.easyTierInstaller.sourceVerifyFailed", source, downloadFailure.message),
                true,
            )
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
    ) {
        coroutineContext.ensureActive()
        withHttpConnection(url) { connection ->
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.setRequestProperty("User-Agent", EASY_TIER_USER_AGENT)
            val code = connection.responseCode
            check(code in HTTP_OK) { "HTTP $code" }
            val totalBytes = connection.contentLengthLong.takeIf { it > 0 } ?: expectedBytes
            connection.inputStream.use { input ->
                Files.newOutputStream(target).use { output ->
                    transfer(input, output, totalBytes, onProgress)
                }
            }
        }
    }

    private suspend fun transfer(
        input: InputStream,
        output: OutputStream,
        totalBytes: Long,
        onProgress: (Float?) -> Unit,
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
            if (totalBytes > 0 && received - reported >= PROGRESS_STEP_BYTES) {
                reported = received
                onProgress((received.toFloat() / totalBytes).coerceIn(ZERO, ONE))
            }
        }
        onProgress(ONE)
    }
}

/** 同一进程内复用测速排序，避免每次重试都重新测速；失败来源仍按顺序兜底。 */
private class SourceRankingCache {
    var order: List<String>? = null
    var rankedAt = 0L
}

/** 纯按实测速度降序选择下载源，不偏向官方源；测速失败的来源保留在末尾兜底。 */
private suspend fun orderedSources(
    pkg: EasyTierPackage,
    probe: EasyTierSourceProbe,
    logger: (message: String, error: Boolean) -> Unit,
    cache: SourceRankingCache,
    now: Long,
): List<String> {
    val candidates = (listOf(pkg.url) + pkg.mirrors).distinct()
    cache.order?.takeIf { now - cache.rankedAt < RANKING_TTL_MILLIS && it.toSet() == candidates.toSet() }?.let { cached ->
        logger(I18n.t("multiplayerB.easyTierInstaller.reuseRanking", cached.joinToString()), false)
        return cached
    }
    val measured =
        coroutineScope {
            candidates.map { source -> async(Dispatchers.IO) {
                probe.measureSpeed(source)?.let { source to it }
            } }.awaitAll().filterNotNull()
        }
    measured.forEach { (source, speed) ->
        logger(I18n.t("multiplayerB.easyTierInstaller.speedMeasured", speed / KIB, source), false)
    }
    val ranked = measured.sortedByDescending { it.second }.map { it.first }
    val unscored = candidates.filterNot { it in ranked }
    unscored.forEach { source ->
        logger(I18n.t("multiplayerB.easyTierInstaller.speedUnscored", source), false)
    }
    val order = ranked + unscored
    cache.order = order
    cache.rankedAt = now
    return order
}

/** 校验安装包大小与 SHA-256。 */
private fun verifyArchive(
    pkg: EasyTierPackage,
    archive: Path,
) {
    val actualBytes = Files.size(archive)
    check(actualBytes == pkg.downloadBytes) {
        I18n.t("multiplayer.easyTierInstaller.8", actualBytes, pkg.downloadBytes)
    }
    check(sha256(archive) == pkg.sha256.lowercase(Locale.ROOT)) {
        I18n.t("multiplayer.easyTierInstaller.9")
    }
}

/** 确认解包结果里必需文件都在。 */
private fun requireRequiredFiles(
    pkg: EasyTierPackage,
    staging: Path,
) {
    val missing = pkg.requiredFiles.filterNot { name -> isUsableFile(staging.resolve(name)) }
    require(missing.isEmpty()) { I18n.t("multiplayer.easyTierInstaller.10", missing.joinToString()) }
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
                    logger(
                        I18n.t(
                            "multiplayerB.easyTierInstaller.executablePermissionFailed",
                            file.fileName,
                            failure.message,
                        ),
                        true,
                    )
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
                .onFailure { failure ->
                    logger(I18n.t("multiplayerB.easyTierInstaller.cleanupFailed", failure.message), true)
                }
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
private const val RANKING_TTL_MILLIS = 10 * 60 * 1_000L
private const val PROGRESS_STEP_BYTES = 512L * 1024
private const val PROGRESS_LOG_STEP = 10
private const val PERCENT = 100
private const val KIB = 1024L
private val HTTP_OK = 200..299
private const val ONE = 1f
private const val ZERO = 0f
private val EXECUTABLE_PERMISSIONS: Set<PosixFilePermission> =
    PosixFilePermissions.fromString("rwxr-xr-x")
