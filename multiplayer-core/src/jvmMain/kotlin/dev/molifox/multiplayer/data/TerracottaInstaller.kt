// 下载、校验并安装陶瓦联机核心：安装包与包内文件都用 SHA-512 校验，失败不留下半成品。
package dev.molifox.multiplayer.data

import dev.molifox.multiplayer.common.CoreStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * 联机核心安装器：目标目录 `<运行数据>/runtimes/terracotta/<版本>/<平台>/`。
 *
 * 规则：
 * - 已安装且每个文件哈希一致时不再下载；不一致则重新安装（覆盖旧文件）。
 * - 下载地址按网络区域排序，逐个尝试；每次尝试失败都记录日志，全部失败才报错。
 * - 解压与校验在临时目录完成，成功后整体替换目标目录；任何一步失败都清理临时内容。
 */
open class TerracottaInstaller(
    protected val runtimesRoot: Path,
    private val logger: (message: String, error: Boolean) -> Unit = { _, _ -> },
    private val packageProvider: () -> TerracottaPackage? = TerracottaCatalog::current,
    private val urlProvider: (TerracottaPackage) -> List<String> = {
        TerracottaCatalog.downloadUrls(TerracottaCatalog.prefersMirror())
    },
) {
    /** 当前平台的安装包信息；不支持的平台给出明确提示。 */
    open fun packageInfo(): TerracottaPackage =
        packageProvider()
            ?: throw IllegalStateException("当前平台暂不支持陶瓦联机（需要 Windows 或 Linux x86_64）。")

    fun directory(pkg: TerracottaPackage = packageInfo()): Path =
        runtimesRoot.resolve("terracotta").resolve(pkg.version).resolve(pkg.classifier)

    open fun executable(pkg: TerracottaPackage = packageInfo()): Path =
        directory(pkg).resolve(pkg.executable)

    /** 检查本地核心是否完整；读取失败按未安装处理，不抛出。 */
    open fun inspect(): CoreStatus {
        val pkg =
            runCatching { packageInfo() }.getOrElse {
                return CoreStatus(false, TerracottaCatalog.VERSION, unsupportedPlatform(), 0, "")
            }
        val directory = directory(pkg)
        val installed =
            runCatching {
                pkg.files.all { (name, digest) ->
                    val file = directory.resolve(name)
                    Files.isRegularFile(file) && sha512(file) == digest
                }
            }.getOrDefault(false)
        return CoreStatus(
            installed = installed,
            version = pkg.version,
            platform = pkg.classifier,
            downloadBytes = pkg.downloadBytes,
            directory = directory.toString(),
        )
    }

    /** 下载并安装；[onProgress] 收到 0..1 的进度，未知时为 null。 */
    open suspend fun install(onProgress: (Float?) -> Unit): Result<CoreStatus> =
        withContext(Dispatchers.IO) {
            runCatching {
                val pkg = packageInfo()
                if (inspect().installed) return@runCatching inspect()
                onProgress(null)
                Files.createDirectories(runtimesRoot)
                val work = Files.createTempDirectory(runtimesRoot, "terracotta-install-")
                try {
                    val archive = work.resolve("terracotta-pkg.tar.gz")
                    download(pkg, archive, onProgress)
                    verifyArchive(pkg, archive)
                    val extracted = work.resolve("extracted")
                    TarGzExtractor.extract(archive, extracted)
                    verifyFiles(pkg, extracted)
                    commit(pkg, extracted, directory(pkg))
                    inspect().also { check(it.installed) { "联机核心安装后校验失败" } }
                } finally {
                    deleteRecursively(work)
                }
            }
        }

    private suspend fun download(
        pkg: TerracottaPackage,
        target: Path,
        onProgress: (Float?) -> Unit,
    ) {
        val urls = urlProvider(pkg)
        require(urls.isNotEmpty()) { "没有可用的联机核心下载地址" }
        var lastFailure: Throwable? = null
        urls.forEach { url ->
            repeat(ATTEMPTS_PER_URL) { attempt ->
                try {
                    logger("下载联机核心（第 ${attempt + 1} 次）：$url", false)
                    fetch(url, target, pkg.downloadBytes, onProgress)
                    return
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: IOException) {
                    lastFailure = failure
                    logger("从 $url 下载联机核心失败：${failure.message}", true)
                } catch (failure: IllegalStateException) {
                    lastFailure = failure
                    logger("从 $url 下载联机核心失败：${failure.message}", true)
                }
            }
        }
        throw IOException(
            "下载联机核心失败：${lastFailure?.message ?: "所有下载地址都不可用"}。请检查网络后重试。",
            lastFailure,
        )
    }

    private suspend fun fetch(
        url: String,
        target: Path,
        expectedBytes: Long,
        onProgress: (Float?) -> Unit,
    ) {
        coroutineContext.ensureActive()
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "MoliFox-Launcher")
        try {
            val code = connection.responseCode
            check(code in HTTP_OK) { "HTTP $code" }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: expectedBytes
            connection.inputStream.use { input ->
                Files.newOutputStream(target).use { output ->
                    transfer(input, output, total, onProgress)
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}

private suspend fun transfer(
    input: InputStream,
    output: OutputStream,
    total: Long,
    onProgress: (Float?) -> Unit,
) {
    val buffer = ByteArray(BUFFER_BYTES)
    var copied = 0L
    var lastReported = -1
    while (true) {
        coroutineContext.ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        output.write(buffer, 0, count)
        copied += count
        val percent = ((copied * 100) / total).toInt().coerceIn(0, 100)
        if (percent != lastReported) {
            lastReported = percent
            onProgress(copied.toDouble().div(total).toFloat())
        }
    }
}

private fun verifyArchive(
    pkg: TerracottaPackage,
    archive: Path,
) {
    val actual = sha512(archive)
    check(actual == pkg.archiveSha512) { "联机核心安装包校验失败（SHA-512 不一致），请重新下载" }
}

private fun verifyFiles(
    pkg: TerracottaPackage,
    extracted: Path,
) {
    pkg.files.forEach { (name, digest) ->
        val file = extracted.resolve(name)
        check(Files.isRegularFile(file)) { "联机核心安装包缺少文件：$name" }
        check(sha512(file) == digest) { "联机核心文件校验失败：$name" }
    }
}

private fun commit(
    pkg: TerracottaPackage,
    extracted: Path,
    target: Path,
) {
    Files.createDirectories(target.parent)
    deleteRecursively(target)
    Files.createDirectories(target)
    pkg.files.keys.forEach { name ->
        val destination = target.resolve(name)
        Files.move(
            extracted.resolve(name),
            destination,
            StandardCopyOption.REPLACE_EXISTING,
        )
        if (!pkg.executable.endsWith(".exe")) {
            runCatching {
                Files.setPosixFilePermissions(
                    destination,
                    java.nio.file.attribute.PosixFilePermissions
                        .fromString("rwxr-xr-x"),
                )
            }
        }
    }
}

private fun sha512(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-512")
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

private fun deleteRecursively(path: Path) {
    if (!Files.exists(path)) return
    Files.walk(path).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach { child ->
            runCatching { Files.deleteIfExists(child) }
        }
    }
}

private fun unsupportedPlatform(): String =
    "${System.getProperty("os.name").orEmpty()} " +
        System.getProperty("os.arch").orEmpty()

private const val ATTEMPTS_PER_URL = 3
private const val CONNECT_TIMEOUT_MILLIS = 20_000
private const val READ_TIMEOUT_MILLIS = 60_000
private const val BUFFER_BYTES = 64 * 1024
private val HTTP_OK = 200..299
