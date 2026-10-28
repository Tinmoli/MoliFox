// 负责网络请求、下载校验、重试和断点续传。

package dev.molifox.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.ConnectException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.coroutineContext
import kotlin.io.path.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

data class DownloadArtifact(
    val url: String,
    val target: Path,
    val sha1: String = "",
    val size: Long = 0,
    val hashAlgorithm: String = "SHA-1",
)

/** 下载收到非 2xx 状态码：5xx/408/429 可重试，其它 4xx 直接失败。 */
internal class DownloadHttpException(val code: Int, host: String) :
    IOException("下载失败 ($code)：$host")

/** 大小或哈希不符：内容不可用，必须删除 `.part` 后重下，不能留下坏文件。 */
internal class DownloadVerificationException(message: String) : IOException(message)

/** 可恢复失败才重试：IO 中断、超时、5xx 以及校验失败。 其它 4xx 只在还有备用镜像时继续试（保持镜像回退），单一下载源直接失败，避免反复打 404。 */
private fun isRecoverableDownloadFailure(error: Throwable, mirrorFallback: Boolean): Boolean =
    when (error) {
        is DownloadVerificationException -> true
        is DownloadHttpException ->
            error.code >= 500 || error.code == 408 || error.code == 429 || mirrorFallback
        is IOException -> true
        else -> false
    }

class NetworkClient(
    private val source: () -> String = { "auto" },
    // 每次请求时读取代理设置，保存设置后无需重建客户端。
    private val proxyProvider: () -> Proxy? = { null },
    private val systemProxyEnabled: () -> Boolean = { false },
    private val systemProxySelector: ProxySelector = SystemProxyResolver(),
    private val downloadSegments: () -> Int = { 4 },
    private val downloadAttempts: () -> Int = { 3 },
    private val requestTimeoutSeconds: () -> Int = { 45 },
) : AutoCloseable {
    internal val client =
        OkHttpClient.Builder()
            .apply {
                windowsTlsConfiguration()?.let { (factory, manager) ->
                    sslSocketFactory(factory, manager)
                }
            }
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = 64
                    maxRequestsPerHost = 32
                }
            )
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES)
            .followRedirects(true)
            .connectionSpecs(
                listOf(
                    ConnectionSpec.MODERN_TLS,
                    ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                        .tlsVersions(TlsVersion.TLS_1_2)
                        .build(),
                    ConnectionSpec.CLEARTEXT,
                )
            )
            .proxySelector(
                object : ProxySelector() {
                    override fun select(uri: URI?): List<Proxy> {
                        if (uri?.host?.let { isLoopbackHost(it) } == true)
                            return listOf(Proxy.NO_PROXY)
                        proxyProvider()?.let {
                            return listOf(it)
                        }
                        return if (systemProxyEnabled()) systemProxySelector.select(uri)
                        else listOf(Proxy.NO_PROXY)
                    }

                    override fun connectFailed(uri: URI?, sa: SocketAddress?, error: IOException?) {
                        if (systemProxyEnabled()) systemProxySelector.connectFailed(uri, sa, error)
                    }
                }
            )
            .build()

    private val noRedirectClient by lazy {
        client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    }

    /** 按目标路径分片的下载锁，避免同一文件被并发写入同一份 `.part`。 */
    private val downloadLocks = List(16) { Mutex() }
    private val downloadJournals =
        java.util.concurrent.ConcurrentHashMap<String, TaskDownloadJournal>()

    private suspend fun execute(
        request: Request,
        redirects: Boolean = true,
        attempts: Int = downloadAttempts().coerceIn(1, 8),
    ): Response {
        for (attempt in 1..attempts) {
            try {
                return executeOnce(request, redirects)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                if (attempt == attempts || !canRetryRequest(request.method, failure)) {
                    val causes = generateSequence<Throwable>(failure) { it.cause }.take(16).toList()
                    val reason =
                        when {
                            causes.any {
                                it is CertificateException || it is SSLPeerUnverifiedException
                            } -> "证书校验失败"
                            failure is SSLHandshakeException -> "TLS 握手失败"
                            else -> "网络连接失败"
                        }
                    val proxy = proxyProvider()
                    val route =
                        if (proxy != null) "手动 ${proxy.type()} 代理"
                        else if (systemProxyEnabled()) "系统代理" else "直接连接"
                    throw IOException(
                        "连接 ${request.url.host} 时$reason（$route，尝试 $attempt 次）：${failure.javaClass.simpleName}；请检查网络或代理后重试",
                        failure,
                    )
                }
                delay(250L * attempt)
            }
        }
        error("Network retry state exhausted")
    }

    private suspend fun executeOnce(request: Request, redirects: Boolean): Response =
        suspendCancellableCoroutine { continuation ->
            val timeout = requestTimeoutSeconds().coerceIn(10, 180)
            val configured =
                (if (redirects) client else noRedirectClient).let {
                    if (timeout == 45) it
                    else
                        it.newBuilder()
                            .connectTimeout(minOf(timeout, 30).toLong(), TimeUnit.SECONDS)
                            .readTimeout(timeout.toLong(), TimeUnit.SECONDS)
                            .build()
                }
            val call = configured.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val cancellable =
                            response
                                .newBuilder()
                                .request(
                                    response.request
                                        .newBuilder()
                                        .tag(
                                            ResponseCancellation::class.java,
                                            ResponseCancellation(call::cancel),
                                        )
                                        .build()
                                )
                                .build()
                        continuation.resume(cancellable) { _, value, _ ->
                            call.cancel()
                            value.close()
                        }
                    }
                }
            )
        }

    private fun builder(url: String): Request.Builder {
        val uri = URI(url)
        require(
            uri.scheme == "https" ||
                (uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1"))
        ) {
            "只允许 HTTPS 请求"
        }
        return Request.Builder()
            .url(url)
            .header("User-Agent", "MoliFox/$LAUNCHER_VERSION (Minecraft Java Edition launcher)")
    }

    internal fun downloadCandidates(url: String, selection: String = source()): List<String> {
        return if (selection == "auto")
            listOf(mirrorUrl(url, "official"), mirrorUrl(url, "bmclapi")).distinct()
        else listOf(mirrorUrl(url, selection))
    }

    suspend fun text(url: String, sourceOverride: String? = null): String =
        withContext(Dispatchers.IO) {
            var lastError: Exception? = null
            for (candidate in downloadCandidates(url, sourceOverride ?: source())) {
                try {
                    execute(builder(candidate).build()).use { response ->
                        check(response.isSuccessful) {
                            "网络请求失败 (${response.code})：${URI(url).host}"
                        }
                        return@withContext response.body?.string() ?: error("网络响应为空")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    lastError = error
                }
            }
            throw IOException("无法连接 ${URI(url).host}：${lastError?.message}", lastError)
        }

    suspend fun json(url: String, sourceOverride: String? = null): com.google.gson.JsonElement =
        JsonParser.parseString(text(url, sourceOverride))

    internal suspend fun encyclopediaText(url: String): String =
        withContext(Dispatchers.IO) {
            val uri = URI(url)
            require(
                uri.scheme == "https" &&
                    uri.host in setOf("www.mcmod.cn", "search.mcmod.cn") &&
                    uri.userInfo == null
            )
            execute(builder(url).build(), attempts = 1).readCancellable { response ->
                check(response.isSuccessful) { "百科页面暂不可用" }
                val body = response.body ?: error("百科页面为空")
                require(body.contentLength() <= 2 * 1024 * 1024)
                val bytes = body.byteStream().readNBytes(2 * 1024 * 1024 + 1)
                require(bytes.size <= 2 * 1024 * 1024)
                bytes.toString(Charsets.UTF_8)
            }
        }

    internal suspend fun githubJson(url: String): com.google.gson.JsonElement =
        withContext(Dispatchers.IO) {
            val uri = URI(url)
            require(
                (uri.scheme == "https" && uri.host == "api.github.com") ||
                    (uri.scheme == "http" && uri.host == "127.0.0.1")
            ) {
                "无效的 GitHub API 地址"
            }
            execute(builder(url).header("Accept", "application/vnd.github+json").build()).use {
                response ->
                check(response.isSuccessful) {
                    when (response.code) {
                        404 -> "无法读取项目发布页；仓库尚未公开或尚无可读取的发布版本"
                        403,
                        429 -> "GitHub 请求次数受限，请稍后重试"
                        else -> "GitHub 更新检查失败（HTTP ${response.code}）"
                    }
                }
                JsonParser.parseString(response.body?.string() ?: error("GitHub 返回了空响应"))
            }
        }

    /** Never attach a project API key to CDN requests, mirrors, or redirect targets. */
    internal suspend fun curseForgeJson(
        url: String,
        key: String,
        body: JsonObject? = null,
    ): JsonObject =
        withContext(Dispatchers.IO) {
            val uri = URI(url)
            require(
                (uri.scheme == "https" &&
                    uri.host == "api.curseforge.com" &&
                    uri.port in listOf(-1, 443) ||
                    uri.scheme == "http" && uri.host == "127.0.0.1") &&
                    uri.userInfo == null &&
                    uri.fragment == null &&
                    uri.path.startsWith("/v1/")
            ) {
                "无效的 CurseForge API 地址"
            }
            require(key.isNotBlank() && key.length <= 512 && key.all { it.code in 33..126 }) {
                "请在「设置 → 下载」填写有效的 CurseForge API Key"
            }
            val request = builder(url).header("x-api-key", key).header("Accept", "application/json")
            body?.let {
                request.post(it.toString().toRequestBody("application/json".toMediaType()))
            }
            try {
                execute(request.build(), redirects = false).use { response ->
                    check(response.isSuccessful) {
                        when (response.code) {
                            401,
                            403 -> "CurseForge API Key 无效或尚未获得访问权限"
                            429 -> "CurseForge 请求过于频繁，请稍后重试"
                            in 300..399 -> "CurseForge API 返回重定向，已停止请求以保护 API Key"
                            else -> "CurseForge 请求失败 (${response.code})"
                        }
                    }
                    val bytes =
                        response.body?.byteStream()?.use { it.readNBytes(8 * 1024 * 1024 + 1) }
                            ?: error("CurseForge 响应为空")
                    require(bytes.size <= 8 * 1024 * 1024) { "CurseForge 响应过大" }
                    try {
                        JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
                    } catch (_: Exception) {
                        error("CurseForge 响应格式无效")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                throw IOException("无法连接 CurseForge，请检查网络或代理设置")
            }
        }

    /** Resource icons are small, public CDN images; never forward account credentials. */
    suspend fun thumbnail(url: String): ByteArray =
        withContext(Dispatchers.IO) {
            val uri = URI(url)
            require(
                uri.scheme == "https" &&
                    uri.host in
                        setOf(
                            "cdn.modrinth.com",
                            "media.forgecdn.net",
                            "mediafilez.forgecdn.net",
                            "edge.forgecdn.net",
                        )
            ) {
                "Unsupported thumbnail host"
            }
            execute(builder(url).build()).use { response ->
                check(response.isSuccessful) { "Thumbnail unavailable" }
                val body = response.body ?: error("Empty thumbnail")
                require(body.contentLength() <= 2L * 1024 * 1024) { "Thumbnail too large" }
                val bytes = body.byteStream().readNBytes(2 * 1024 * 1024 + 1)
                require(bytes.size <= 2 * 1024 * 1024) { "Thumbnail too large" }
                bytes
            }
        }

    /** 玩家皮肤纹理只允许来自 Mojang 官方 CDN，限制大小， 不携带任何账户凭据，也不经过镜像重写。 */
    suspend fun skinTexture(url: String): ByteArray =
        withContext(Dispatchers.IO) {
            val normalized = normalizeSkinTextureUrl(url)
            execute(builder(normalized).build(), redirects = false).use { response ->
                check(response.isSuccessful) { "皮肤纹理获取失败 (${response.code})" }
                val body = response.body ?: error("皮肤纹理为空")
                require(body.contentLength() <= 1024L * 1024) { "皮肤纹理过大" }
                val bytes = body.byteStream().readNBytes(1024 * 1024 + 1)
                require(bytes.size <= 1024 * 1024) { "皮肤纹理过大" }
                bytes
            }
        }

    suspend fun post(url: String, body: JsonObject, bearer: String? = null): JsonObject =
        withContext(Dispatchers.IO) {
            val request =
                builder(url).post(body.toString().toRequestBody("application/json".toMediaType()))
            bearer?.let { request.header("Authorization", "Bearer $it") }
            execute(request.build()).use { response ->
                if (!response.isSuccessful) throw ServiceHttpException(response.code, URI(url).host)
                JsonParser.parseString(response.body!!.string()).asJsonObject
            }
        }

    /**
     * Token-bearing skin writes are restricted to the official endpoint (or an explicit loopback
     * fixture).
     */
    internal suspend fun uploadMinecraftSkin(
        url: String,
        token: String,
        variant: String,
        png: ByteArray,
    ) =
        withContext(Dispatchers.IO) {
            val uri = URI(url)
            require(
                (uri.scheme == "https" &&
                    uri.host == "api.minecraftservices.com" &&
                    uri.port == -1 || uri.scheme == "http" && uri.host == "127.0.0.1") &&
                    uri.path == "/minecraft/profile/skins" &&
                    uri.userInfo == null &&
                    uri.query == null &&
                    uri.fragment == null
            ) {
                "无效的皮肤上传地址"
            }
            require(variant in setOf("classic", "slim") && png.size in 1..1024 * 1024) {
                "皮肤上传参数无效"
            }
            val body =
                MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("variant", variant)
                    .addFormDataPart(
                        "file",
                        "skin.png",
                        png.toRequestBody("image/png".toMediaType()),
                    )
                    .build()
            execute(
                    builder(url).header("Authorization", "Bearer $token").post(body).build(),
                    redirects = false,
                )
                .use { response ->
                    if (!response.isSuccessful) throw ServiceHttpException(response.code, uri.host)
                }
        }

    suspend fun authorizedJson(url: String, token: String): JsonObject =
        withContext(Dispatchers.IO) {
            execute(builder(url).header("Authorization", "Bearer $token").build()).use { response ->
                if (response.code == 404) error("此账户没有 Minecraft Java 版档案，请确认已拥有游戏并创建角色")
                check(response.isSuccessful) { "读取游戏档案失败 (${response.code})" }
                JsonParser.parseString(response.body!!.string()).asJsonObject
            }
        }

    suspend fun form(url: String, fields: Map<String, String>): JsonObject =
        withContext(Dispatchers.IO) {
            val form =
                FormBody.Builder()
                    .apply { fields.forEach { (key, value) -> add(key, value) } }
                    .build()
            execute(builder(url).post(form).build()).use { response ->
                val result = JsonParser.parseString(response.body!!.string()).asJsonObject
                if (!response.isSuccessful && !result.has("error"))
                    error("认证服务请求失败 (${response.code})")
                result
            }
        }

    /** 断点缓存固定为 `<目标>.part`，与目标同目录，随实例目录一起迁移或清理。 */
    fun partialFile(target: Path): Path = target.resolveSibling("${target.fileName}.part")

    /** 删除普通与分段缓存；显式取消不保留断点，网络中断则允许重试续传。 */
    fun discardPartial(target: Path) {
        partialFile(target).deleteIfExists()
        SegmentedDownloader.discard(target)
    }

    /** 下载到临时文件，校验整体大小与摘要后替换目标；网络失败可续传，显式取消则清理。 */
    suspend fun download(
        artifact: DownloadArtifact,
        attempts: Int = downloadAttempts().coerceIn(1, 8),
        progress: (Long, Long) -> Unit = { _, _ -> },
    ) =
        withContext(Dispatchers.IO) {
            val target = artifact.target
            withTargetLock(target) {
                coroutineContext.ensureActive()
                target.parent.createDirectories()
                val partial = partialFile(target)
                val path = target.toAbsolutePath().normalize()
                val context = coroutineContext[TaskDownloadContext]?.task
                val journal = context?.let {
                    downloadJournals
                        .computeIfAbsent(it.id) { _ -> TaskDownloadJournal(it) }
                        .also { journal ->
                            it.onFinished(
                                this@NetworkClient,
                                cancel = {
                                    journal.clean { file, action ->
                                        withTargetLock(file) { action() }
                                    }
                                },
                                complete = { downloadJournals.remove(it.id) },
                            )
                        }
                }
                downloadJournals.values.filter { it !== journal }.forEach { it.borrowed(path) }
                val existed = target.exists()
                if (artifactProblem(artifact) == null) {
                    // 目标已经完整，残留的断点只会占空间。
                    discardPartial(target)
                    return@withTargetLock
                }
                val candidates = downloadCandidates(artifact.url)
                val tries = attempts.coerceIn(1, 8)
                try {
                    for (attempt in 1..tries) {
                        coroutineContext.ensureActive()
                        try {
                            val candidate = candidates[(attempt - 1) % candidates.size]
                            val segmented = SegmentedDownloader { url, range ->
                                execute(builder(url).header("Range", range).build(), attempts = 1)
                            }
                            if (
                                !segmented.download(
                                    artifact,
                                    partial,
                                    candidate,
                                    downloadSegments(),
                                    progress,
                                )
                            )
                                fetch(
                                    artifact,
                                    partial,
                                    candidate,
                                    progress,
                                )
                            if (!existed) journal?.completed(path)
                            return@withTargetLock
                        } catch (cancelled: CancellationException) {
                            discardPartial(target)
                            throw cancelled
                        } catch (error: Exception) {
                            coroutineContext.ensureActive()
                            val mirrorFallback = candidates.size > 1
                            if (
                                attempt == tries ||
                                    !isRecoverableDownloadFailure(error, mirrorFallback)
                            )
                                throw error
                            delay(minOf(500L shl (attempt - 1), 8_000L))
                        }
                    }
                } finally {
                    if (!existed && target.isRegularFile()) journal?.completed(path)
                    if (!coroutineContext.isActive) discardPartial(target)
                }
            }
        }

    /** 单次尝试：请求一次（416 时再请求一次），成功即完成改名，失败抛异常交给重试。 */
    private suspend fun fetch(
        artifact: DownloadArtifact,
        partial: Path,
        url: String,
        progress: (Long, Long) -> Unit,
    ) {
        repeat(2) {
            val offset = resumeOffset(artifact, partial)
            val request = builder(url)
            if (offset > 0) request.header("Range", "bytes=$offset-")
            var restart = true
            execute(request.build(), attempts = 1).readCancellable { response ->
                restart = receive(artifact, partial, offset, response, progress)
            }
            if (!restart) return
        }
        // 第二次仍未成功：断点或服务器状态异常，交给重试策略。
        throw DownloadHttpException(416, URI(artifact.url).host)
    }

    /** 处理一次响应：写完并校验后改名成功返回 `false`；需要清空 `.part` 重新请求时返回 `true`。 */
    private suspend fun receive(
        artifact: DownloadArtifact,
        partial: Path,
        offset: Long,
        response: Response,
        progress: (Long, Long) -> Unit,
    ): Boolean {
        if (response.code == 416) {
            // Range 起点超出服务器对象长度：断点已坏，丢掉后从头开始。
            partial.deleteIfExists()
            return true
        }
        var start = offset
        if (start > 0 && response.code == 200) {
            // 服务器不支持 Range：必须清空重写，不能把完整响应拼到已有字节后面。
            partial.deleteIfExists()
            start = 0
        } else if (response.code == 206) {
            val rangeStart = contentRange(response.header("Content-Range"))?.first
            if (rangeStart != start) {
                partial.deleteIfExists()
                return true
            }
        }
        if (!response.isSuccessful)
            throw DownloadHttpException(response.code, URI(artifact.url).host)
        val body = response.body ?: throw IOException("下载内容为空")
        val total = totalBytes(artifact, response, start)
        val digest = MessageDigest.getInstance(artifact.hashAlgorithm)
        if (start > 0) updateDigest(partial, digest, start)
        var received = 0L
        body.byteStream().use { input ->
            Files.newOutputStream(
                    partial,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND,
                )
                .use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        received += count
                        progress(start + received, total)
                    }
                }
        }
        val size = partial.fileSize()
        if (artifact.size > 0 && size != artifact.size) {
            partial.deleteIfExists()
            throw DownloadVerificationException(
                "文件大小校验失败：${artifact.target.fileName}（期望 ${artifact.size} 字节，实际 $size 字节）"
            )
        }
        if (artifact.sha1.isNotBlank()) {
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(artifact.sha1, true)) {
                partial.deleteIfExists()
                throw DownloadVerificationException("文件校验失败：${artifact.target.fileName}")
            }
        }
        Files.move(partial, artifact.target, StandardCopyOption.REPLACE_EXISTING)
        progress(size, if (total > 0) total else size)
        return false
    }

    /** 已有可续传的字节数；超过声明长度说明断点与目标不符，直接丢弃。 */
    private fun resumeOffset(artifact: DownloadArtifact, partial: Path): Long {
        if (!partial.isRegularFile()) return 0
        val size = partial.fileSize()
        if (size <= 0) return 0
        if (artifact.size > 0 && size > artifact.size) {
            partial.deleteIfExists()
            return 0
        }
        return size
    }

    /** 续传时先补齐已有字节的摘要，再继续累加新数据，最终校验的是整个文件。 */
    private fun updateDigest(partial: Path, digest: MessageDigest, length: Long) {
        Files.newInputStream(partial).use { input ->
            val buffer = ByteArray(65536)
            var remaining = length
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count < 0) break
                digest.update(buffer, 0, count)
                remaining -= count
            }
        }
    }

    /** 进度总长：优先用声明的完整大小；206 用 Content-Range 的总长；否则用本次剩余长度推算。 */
    private fun totalBytes(artifact: DownloadArtifact, response: Response, start: Long): Long {
        if (artifact.size > 0) return artifact.size
        if (response.code == 206)
            contentRange(response.header("Content-Range"))?.let {
                return it.second
            }
        val length = response.body?.contentLength() ?: -1L
        return if (length > 0) start + length else 0
    }

    /** 解析 `Content-Range: bytes 100-999/1000`，返回 (起点, 总长)。 */
    private fun contentRange(header: String?): Pair<Long, Long>? {
        val match = header?.let { Regex("bytes (\\d+)-(\\d+)/(\\d+)").find(it) } ?: return null
        val (start, _, total) = match.destructured
        return start.toLongOrNull()?.let { begin -> total.toLongOrNull()?.let { begin to it } }
    }

    /** 同一目标的并发下载共用一把锁：`.part` 只有一份，不能让两个协程同时写。 */
    private suspend fun <T> withTargetLock(target: Path, block: suspend () -> T): T {
        val key = taskPathKey(target)
        val index = Math.floorMod(key.hashCode(), downloadLocks.size)
        return downloadLocks[index].withLock { block() }
    }

    suspend fun downloadAll(
        artifacts: List<DownloadArtifact>,
        concurrency: Int,
        context: JobContext,
        label: String,
        from: Float = 0f,
        to: Float = 1f,
    ) = coroutineScope {
        val semaphore = Semaphore(concurrency.coerceIn(1, 32))
        val finished = AtomicInteger()
        val unique = artifacts.distinctBy { it.target }
        context.beginTransfers(unique.size)
        unique
            .map { artifact ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        download(artifact) { bytes, total ->
                            context.transfer(artifact.target.toString(), bytes, total)
                        }
                        val size = artifact.target.fileSize()
                        context.transfer(artifact.target.toString(), size, size, completed = true)
                        val count = finished.incrementAndGet()
                        context.progress(
                            from + (to - from) * count / maxOf(unique.size, 1),
                            "$label $count/${unique.size}",
                        )
                    }
                }
            }
            .awaitAll()
    }

    fun mirror(url: String): String = mirrorUrl(url, source())

    override fun close() {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        /** 默认总尝试次数（含首次）：首次 + 2 次重试；重试沿用已有 `.part`。 */
        const val DEFAULT_DOWNLOAD_ATTEMPTS = 3
    }
}

/** 认证写请求仅在连接/握手阶段重试；证书与主机名验证失败不做重试。 */
internal fun canRetryRequest(method: String, failure: IOException): Boolean {
    val causes = generateSequence<Throwable>(failure) { it.cause }.take(16).toList()
    if (causes.any { it is CertificateException || it is SSLPeerUnverifiedException }) return false
    return method == "GET" || failure is SSLHandshakeException || failure is ConnectException
}

fun mirrorUrl(url: String, source: String): String {
    val secure =
        if (url.startsWith("http://") && URI(url).host !in setOf("localhost", "127.0.0.1"))
            url.replaceFirst("http://", "https://")
        else url
    if (source != "bmclapi") return secure
    val uri = URI(secure)
    return when (uri.host) {
        "piston-meta.mojang.com",
        "launchermeta.mojang.com",
        "piston-data.mojang.com",
        "launcher.mojang.com" -> "https://bmclapi2.bangbang93.com${uri.rawPath}"
        "resources.download.minecraft.net" -> "https://bmclapi2.bangbang93.com/assets${uri.rawPath}"
        "libraries.minecraft.net" -> "https://bmclapi2.bangbang93.com/maven${uri.rawPath}"
        else -> secure
    }
}

fun validatePackUrl(url: String): String {
    val uri = URI(url)
    require(
        uri.scheme == "https" &&
            uri.userInfo == null &&
            uri.port in setOf(-1, 443) &&
            uri.host in
                setOf(
                    "cdn.modrinth.com",
                    "github.com",
                    "raw.githubusercontent.com",
                    "gitlab.com",
                    "objects.githubusercontent.com",
                    "mediafilez.forgecdn.net",
                    "edge.forgecdn.net",
                )
    ) {
        "不支持的整合包下载域名：${uri.host}"
    }
    return url
}
