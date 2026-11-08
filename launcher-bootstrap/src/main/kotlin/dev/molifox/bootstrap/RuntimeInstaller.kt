// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 运行时依赖的下载与校验：镜像探测在 Mirror.kt，缓存落盘判定在 RuntimeCache.kt。
package dev.molifox.bootstrap

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

// ---------- 下载节奏与重试令牌 ----------

private const val CONNECT_TIMEOUT_MILLIS = 8000
private const val READ_TIMEOUT_MILLIS = 15000
private const val FILE_TIMEOUT_MILLIS = 60_000L
private const val PARALLEL_DOWNLOADS = 4
private const val RETRY_LIMIT = 2
private const val MILLIS_PER_SECOND = 1000L

/**
 * 下载、校验与进度状态。所有可变状态都用并发容器保存，Swing 定时器只读取快照。
 *
 * 主流程（BootstrapMain.kt）负责驱动安装，准备窗口（BootstrapWindow.kt）负责读取进度，
 * 两边都要用到，因此从 private 提升为 internal。
 */
internal class RuntimeInstaller(
    private val cacheDirectory: Path,
    private val dependencies: List<RuntimeDependency>,
) {
    val totalFiles = dependencies.size
    val totalBytes = dependencies.sumOf { it.size }
    private val verified = ConcurrentHashMap<String, Long>()
    private val active = ConcurrentHashMap<String, Long>()
    private val failures = Collections.synchronizedMap(LinkedHashMap<String, String>())

    @Volatile private var mirrorOrder: List<Mirror> = emptyList()

    val mirror: Mirror?
        get() = mirrorOrder.firstOrNull()

    val canSwitchMirror: Boolean
        get() = mirrorOrder.size > 1

    val filesDone: Int
        get() = verified.size

    val bytesDone: Long
        get() = verified.values.sum() + active.values.sum()

    fun useMirrors(candidates: List<Mirror>) {
        mirrorOrder = candidates
    }

    /** 换到次快的镜像：把当前源移到队尾，重试时自然改用下一个。 */
    fun switchMirror() {
        if (mirrorOrder.size > 1) mirrorOrder = mirrorOrder.drop(1) + mirrorOrder.first()
    }

    /** 下载所有缺失文件；返回仍然失败的文件说明，空列表表示全部就绪。 */
    suspend fun install(): List<String> {
        failures.clear()
        val pending = dependencies.filterNot { verified.containsKey(it.filename) }
        val permits = Semaphore(PARALLEL_DOWNLOADS)
        coroutineScope {
            pending
                .map { dependency ->
                    async(Dispatchers.IO) {
                        permits.withPermit {
                            installOne(dependency)?.let { message ->
                                failures[dependency.filename] = "${dependency.filename}：$message"
                            }
                        }
                    }
                }.awaitAll()
        }
        return failureMessages()
    }

    private fun failureMessages(): List<String> = synchronized(failures) { failures.values.toList() }

    private suspend fun installOne(dependency: RuntimeDependency): String? {
        val target = cacheDirectory.resolve(dependency.filename)
        active[dependency.filename] = 0L
        try {
            return when {
                isRuntimeDependencyCached(target, dependency) -> {
                    verified[dependency.filename] = dependency.size
                    log(I18n.t("bootstrapB.download.reused", dependency.filename))
                    null
                }

                mirrorOrder.isEmpty() -> I18n.t("bootstrap.bootstrapMain.1")

                else -> downloadWithRetries(dependency, target)
            }
        } finally {
            active.remove(dependency.filename)
        }
    }

    /** 按镜像顺序重试下载；成功返回 null，全部失败返回最后一条错误（含重试次数）。 */
    private suspend fun downloadWithRetries(
        dependency: RuntimeDependency,
        target: Path,
    ): String? {
        var lastMessage = I18n.t("bootstrap.bootstrapMain.2")
        for (attempt in 0..RETRY_LIMIT) {
            val current = mirrorOrder[attempt % mirrorOrder.size]
            val part = Files.createTempFile(cacheDirectory, ".molifox-", ".part")
            try {
                val completed =
                    withTimeoutOrNull(FILE_TIMEOUT_MILLIS) {
                        transfer(current, dependency, part)
                        true
                    }
                if (completed == null) {
                    throw IOException(I18n.t("bootstrap.bootstrapMain.3", FILE_TIMEOUT_MILLIS / MILLIS_PER_SECOND))
                }
                moveRuntimeIntoPlace(part, target)
                verified[dependency.filename] = dependency.size
                log(I18n.t("bootstrapB.download.completed", dependency.filename, current.name))
                return null
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                lastMessage = error.message ?: error.javaClass.simpleName
                log(I18n.t("bootstrapB.download.fileFailed", dependency.filename, current.name, lastMessage))
            } finally {
                runCatching { Files.deleteIfExists(part) }
                active[dependency.filename] = 0L
            }
        }
        return I18n.t("bootstrap.bootstrapMain.4", lastMessage, RETRY_LIMIT)
    }

    /** 逐个尝试该镜像的根路径；HTTP 失败或校验失败都交给上层换镜像重试。 */
    private suspend fun transfer(
        mirror: Mirror,
        dependency: RuntimeDependency,
        part: Path,
    ) {
        val relative =
            dependency.group.replace('.', '/') +
                "/" +
                dependency.artifact +
                "/" +
                dependency.version +
                "/" +
                dependency.filename
        var lastFailure: IOException? = null
        for (root in mirror.roots) {
            try {
                downloadFrom(root + relative, dependency, part)
                return
            } catch (error: IOException) {
                lastFailure = error
                log(I18n.t("bootstrapB.download.sourceFailed", mirror.name, root, error.message))
            }
        }
        throw lastFailure ?: IOException(I18n.t("bootstrap.bootstrapMain.5"))
    }

    private suspend fun downloadFrom(
        url: String,
        dependency: RuntimeDependency,
        part: Path,
    ) {
        val connection =
            open(url).apply {
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                setRequestProperty("Accept", "application/octet-stream")
            }
        try {
            validateResponse(connection, dependency)
            writeVerifiedPart(connection, dependency, part)
        } finally {
            connection.disconnect()
        }
    }

    /** 落盘并校验：大小与 SHA-256 都对上才算成功，异常类型与提示保持与原实现一致。 */
    private suspend fun writeVerifiedPart(
        connection: HttpURLConnection,
        dependency: RuntimeDependency,
        part: Path,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        val written =
            Files.newOutputStream(part).use { output ->
                connection.inputStream.use { input ->
                    copyChunks(input, output, dependency, digest)
                }
            }
        if (written != dependency.size) {
            throw IOException(I18n.t("bootstrap.bootstrapMain.6", written, dependency.size))
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(dependency.sha256, ignoreCase = true)) {
            throw IOException(I18n.t("bootstrap.bootstrapMain.7"))
        }
    }

    /** 逐块写入 .part 并同步更新摘要与进度，可被取消；返回实际写入的字节数。 */
    private suspend fun copyChunks(
        input: InputStream,
        output: OutputStream,
        dependency: RuntimeDependency,
        digest: MessageDigest,
    ): Long {
        val buffer = ByteArray(64 * 1024)
        var written = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
            output.write(buffer, 0, count)
            written += count
            active[dependency.filename] = written
        }
        return written
    }
}

/** 状态码与声明长度先核对，异常类型与提示保持与原实现一致。 */
private fun validateResponse(
    connection: HttpURLConnection,
    dependency: RuntimeDependency,
) {
    val status = connection.responseCode
    if (status != HttpURLConnection.HTTP_OK) throw IOException("HTTP $status")
    val declared = connection.contentLengthLong
    if (declared > 0 && declared != dependency.size) {
        throw IOException(I18n.t("bootstrap.bootstrapMain.23", declared))
    }
}
