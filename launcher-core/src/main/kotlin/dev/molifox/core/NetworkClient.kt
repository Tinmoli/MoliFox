// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责网络请求、下载校验、重试和断点续传。

package dev.molifox.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.molifox.core.net.CandidateSource
import dev.molifox.core.net.DOWNLOAD_BUDGET_BYTES
import dev.molifox.core.net.DownloadConcurrency
import dev.molifox.core.net.DownloadSourceCatalog
import dev.molifox.core.net.DownloadSourceFailureException
import dev.molifox.core.net.SourceAttempt
import dev.molifox.core.net.SourceHealth
import dev.molifox.core.net.SourceThrottle
import dev.molifox.core.net.TransferBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionSpec
import okhttp3.Dispatcher
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.TlsVersion
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
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.coroutines.coroutineContext
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile

data class DownloadArtifact(
    val url: String,
    val target: Path,
    val sha1: String = "",
    val size: Long = 0,
    val hashAlgorithm: String = "SHA-1",
)

/** 下载收到非 2xx 状态码：5xx/408/429 可重试，其它 4xx 直接失败。 */
internal class DownloadHttpException(
    val code: Int,
    val host: String,
) : IOException("下载失败 ($code)：$host")

/** 大小或哈希不符：内容不可用，必须删除 `.part` 后重下，不能留下坏文件。 */
internal class DownloadVerificationException(
    message: String,
) : IOException(message)

/** 可恢复失败才重试：IO 中断、超时、5xx 以及校验失败。 其它 4xx 只在还有备用镜像时继续试（保持镜像回退），单一下载源直接失败，避免反复打 404。 */
private fun isRecoverableDownloadFailure(
    error: Throwable,
    mirrorFallback: Boolean,
): Boolean =
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
    /** 文件级并发数（设置项「并发下载数」）：只用来换算传输许可，派发仍由调度器决定。 */
    private val maxConcurrent: () -> Int = { 8 },
    /** 镜像类主机的并发上限（设置项「镜像并发」）。 */
    private val mirrorConcurrency: () -> Int = { 4 },
    /** 单任务下载预算：并发数 × 文件大小超过它时按大小分配传输许可。 */
    private val downloadBudgetBytes: Long = DOWNLOAD_BUDGET_BYTES,
    /** 源登记表：官方、BMCLAPI 与各 Maven 镜像。 */
    private val catalog: DownloadSourceCatalog = DownloadSourceCatalog.DEFAULT,
    /** 本轮任务的源健康度：连续失败禁用、403/429 退避。 */
    val health: SourceHealth = SourceHealth(),
    /** 源被禁用、限流等需要留痕的事件。 */
    private val logger: (String, Boolean) -> Unit = { _, _ -> },
    interceptors: List<okhttp3.Interceptor> = emptyList(),
) : AutoCloseable {
    internal val client =
        OkHttpClient
            .Builder()
            .apply {
                interceptors.forEach(::addInterceptor)
                windowsTlsConfiguration()?.let { (factory, manager) ->
                    sslSocketFactory(factory, manager)
                }
            }.dispatcher(
                Dispatcher().apply {
                    maxRequests = 64
                    maxRequestsPerHost = 32
                },
            ).connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES)
            .followRedirects(true)
            .connectionSpecs(
                listOf(
                    ConnectionSpec.MODERN_TLS,
                    ConnectionSpec
                        .Builder(ConnectionSpec.MODERN_TLS)
                        .tlsVersions(TlsVersion.TLS_1_2)
                        .build(),
                    ConnectionSpec.CLEARTEXT,
                ),
            ).proxySelector(
                object : ProxySelector() {
                    override fun select(uri: URI?): List<Proxy> {
                        if (uri?.host?.let { isLoopbackHost(it) } == true) {
                            return listOf(Proxy.NO_PROXY)
                        }
                        proxyProvider()?.let {
                            return listOf(it)
                        }
                        return if (systemProxyEnabled()) {
                            systemProxySelector.select(uri)
                        } else {
                            listOf(Proxy.NO_PROXY)
                        }
                    }

                    override fun connectFailed(
                        uri: URI?,
                        sa: SocketAddress?,
                        error: IOException?,
                    ) {
                        if (systemProxyEnabled()) systemProxySelector.connectFailed(uri, sa, error)
                    }
                },
            ).build()

    private val noRedirectClient by lazy {
        client
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /** 自动源测速专用：连接、读取与整次调用都压在同一段短超时内（与启动器的镜像探测一致）， 复用同一连接池与调度器，不额外占线程。 */
    private val probeClient by lazy {
        client
            .newBuilder()
            .connectTimeout(AUTO_PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .readTimeout(AUTO_PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .callTimeout(AUTO_PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .build()
    }

    /** 按目标路径分片的下载锁，避免同一文件被并发写入同一份 `.part`。 */
    private val downloadLocks = List(16) { Mutex() }
    private val downloadJournals =
        java.util.concurrent.ConcurrentHashMap<String, TaskDownloadJournal>()

    /** 传输闸门：并发数按任务快照，设置改动只影响下一个任务。 */
    private val transferBudgetLock = Mutex()

    @Volatile private var transferBudget: TransferBudget? = null

    @Volatile private var transferBudgetKey: Pair<Int, String>? = null

    private suspend fun transferBudget(): TransferBudget {
        val units = maxConcurrent().coerceIn(1, 32)
        val taskId = coroutineContext[TaskDownloadContext]?.task?.id ?: ""
        val key = units to taskId
        if (transferBudgetKey == key) transferBudget?.let { return it }
        return transferBudgetLock.withLock {
            if (transferBudgetKey == key) transferBudget?.let { return@withLock it }
            TransferBudget(units, downloadBudgetBytes).also {
                transferBudget = it
                transferBudgetKey = key
            }
        }
    }

    private fun logClampedConcurrency(value: DownloadConcurrency) {
        value.note?.let { logger(it, false) }
    }

    /** 新一轮安装开始时调用：清空上一轮的源禁用与限流状态，并让预算重新记一次日志。 */
    fun resetSourceHealth() {
        health.reset()
        transferBudget = null
        transferBudgetKey = null
    }

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
                        if (proxy != null) {
                            "手动 ${proxy.type()} 代理"
                        } else if (systemProxyEnabled()) {
                            "系统代理"
                        } else {
                            "直接连接"
                        }
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

    private suspend fun executeOnce(
        request: Request,
        redirects: Boolean,
    ): Response =
        suspendCancellableCoroutine { continuation ->
            val timeout = requestTimeoutSeconds().coerceIn(10, 180)
            val configured =
                (if (redirects) client else noRedirectClient).let {
                    if (timeout == 45) {
                        it
                    } else {
                        it
                            .newBuilder()
                            .connectTimeout(minOf(timeout, 30).toLong(), TimeUnit.SECONDS)
                            .readTimeout(timeout.toLong(), TimeUnit.SECONDS)
                            .build()
                    }
                }
            val call = configured.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        val cancellable =
                            response
                                .newBuilder()
                                .request(
                                    response.request
                                        .newBuilder()
                                        .tag(
                                            ResponseCancellation::class.java,
                                            ResponseCancellation(call::cancel),
                                        ).build(),
                                ).build()
                        continuation.resume(cancellable) { _, value, _ ->
                            call.cancel()
                            value.close()
                        }
                    }
                },
            )
        }

    private fun builder(
        url: String,
        allowInsecureHttp: Boolean = false,
    ): Request.Builder {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase()
        require(
            scheme == "https" ||
                (
                    scheme == "http" &&
                        (allowInsecureHttp || uri.host in setOf("localhost", "127.0.0.1"))
                ),
        ) {
            "只允许 HTTPS 请求"
        }
        return Request
            .Builder()
            .url(url)
            .header("User-Agent", "MoliFox/$LAUNCHER_VERSION (Minecraft Java Edition launcher)")
    }

    /** 某个地址的候选源；自动模式给多个，手动模式只给选中的源。 */
    internal fun candidateSources(
        url: String,
        selection: String = source(),
    ): List<CandidateSource> = catalog.candidates(url, selection)

    internal fun downloadCandidates(
        url: String,
        selection: String = source(),
    ): List<String> = candidateSources(url, selection).map { it.url }.distinct()

    /**
     * 实际请求用的源与地址顺序：自动模式先按测速结果（最快优先），再按本轮源健康度重排
     * （禁用的源剔除、限流的排到末尾）。官方与镜像地址相同的主机不测速，也不会多发探测请求。
     */
    internal suspend fun orderedSources(
        url: String,
        selection: String = source(),
    ): List<CandidateSource> {
        val base = candidateSources(url, selection)
        if (base.isEmpty()) return base
        val ordered = if (selection == DownloadSourceCatalog.AUTO) orderByProbe(base) else base
        return health.arrange(ordered)
    }

    /** 自动模式：只对官方/BMCLAPI 这一对做测速，Maven 镜像仍排在回退位。 */
    private suspend fun orderByProbe(candidates: List<CandidateSource>): List<CandidateSource> {
        if (candidates.none { it.source.key == DownloadSourceCatalog.BMCLAPI }) return candidates
        val rank = autoMirrorOrder()
        val primary =
            candidates.filter { it.source.key in rank }.sortedBy { rank.indexOf(it.source.key) }
        if (primary.isEmpty()) return candidates
        return primary + candidates.filter { it.source.key !in rank }
    }

    internal suspend fun orderedCandidates(
        url: String,
        selection: String = source(),
    ): List<String> = orderedSources(url, selection).map { it.url }.distinct()

    /** 官方与镜像并发测速；同一任务共用选定顺序，任务外每次查询重新测速。 */
    private suspend fun autoMirrorOrder(): List<String> =
        taskSourceOrder(AUTO_PROBE_SOURCES.map { mirrorUrl(AUTO_PROBE_URL, it) }) {
            coroutineScope {
                val measured =
                    AUTO_PROBE_SOURCES
                        .map { mirror ->
                            async(Dispatchers.IO) { mirror to measureSource(mirror) }
                        }.awaitAll()
                measured.filter { it.second != null }.sortedBy { it.second }.map { it.first } +
                    measured.filter { it.second == null }.map { it.first }
            }
        }

    private suspend fun taskSourceOrder(
        urls: List<String>,
        measure: suspend () -> List<String>,
    ): List<String> =
        coroutineContext[TaskSourceSelection]?.order(this, source(), urls, measure)
            ?: measure()

    /** 单源测速：取版本清单开头的一小段确认真的能取到内容，返回毫秒；不可达返回 null。 */
    private suspend fun measureSource(source: String): Long? =
        measureUrl(mirrorUrl(AUTO_PROBE_URL, source))

    /**
     * 给 Java 元数据源测速并排序，不可达的源排最后；只在当前任务中共用初始化顺序。
     */
    internal suspend fun fastestFirst(urls: List<String>): List<String> {
        val distinct = urls.distinct()
        if (distinct.size < 2) return distinct
        return taskSourceOrder(distinct) {
            coroutineScope {
                distinct
                    .map { url -> async(Dispatchers.IO) { url to measureUrl(url) } }
                    .awaitAll()
                    .sortedWith(compareBy({ it.second == null }, { it.second ?: Long.MAX_VALUE }))
                    .map { it.first }
            }
        }
    }

    private suspend fun measureUrl(url: String): Long? =
        withContext(Dispatchers.IO) {
            val started = System.nanoTime()
            try {
                probeClient
                    .newCall(builder(url).header("Range", "bytes=0-255").build())
                    .execute()
                    .use { response ->
                        if (!response.isSuccessful) return@withContext null
                        response.body?.byteStream()?.read(ByteArray(48))
                        (System.nanoTime() - started) / 1_000_000
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }

    suspend fun text(
        url: String,
        sourceOverride: String? = null,
    ): String =
        withContext(Dispatchers.IO) {
            var lastError: Exception? = null
            for (candidate in orderedCandidates(url, sourceOverride ?: source())) {
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

    suspend fun json(
        url: String,
        sourceOverride: String? = null,
    ): com.google.gson.JsonElement = JsonParser.parseString(text(url, sourceOverride))

    internal suspend fun encyclopediaText(url: String): String =
        withContext(Dispatchers.IO) {
            val uri = URI(url)
            require(
                uri.scheme == "https" &&
                    uri.host in setOf("www.mcmod.cn", "search.mcmod.cn") &&
                    uri.userInfo == null,
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
                    (uri.scheme == "http" && uri.host == "127.0.0.1"),
            ) {
                "无效的 GitHub API 地址"
            }
            execute(builder(url).header("Accept", "application/vnd.github+json").build())
                .use { response ->
                    check(response.isSuccessful) {
                        when (response.code) {
                            404 -> "无法读取项目发布页；仓库尚未公开或尚无可读取的发布版本"
                            403,
                            429,
                            -> "GitHub 请求次数受限，请稍后重试"
                            else -> "GitHub 更新检查失败（HTTP ${response.code}）"
                        }
                    }
                    JsonParser.parseString(response.body?.string() ?: error("GitHub 返回了空响应"))
                }
        }

    /** CurseForge 公共镜像与自建代理都不需要凭据，因此请求不携带任何认证头。 */
    internal suspend fun curseForgeJson(
        url: String,
        body: JsonObject? = null,
    ): JsonObject =
        withContext(Dispatchers.IO) {
            val uri = URI(url)
            val scheme = uri.scheme?.lowercase()
            require(
                (scheme == "https" || scheme == "http") &&
                    !uri.host.isNullOrBlank() &&
                    uri.userInfo == null &&
                    uri.rawFragment == null,
            ) {
                "无效的 CurseForge 服务地址"
            }
            val request =
                builder(url, allowInsecureHttp = true).header("Accept", "application/json")
            body?.let {
                request.post(it.toString().toRequestBody("application/json".toMediaType()))
            }
            try {
                execute(request.build(), redirects = false).use { response ->
                    if (!response.isSuccessful) {
                        throw CurseForgeHttpException(
                            response.code,
                            when (response.code) {
                                404 -> "CurseForge 项目或文件不存在"
                                429 -> "CurseForge 请求过于频繁，请稍后重试"
                                in 300..399 -> "CurseForge 服务返回重定向，已停止请求"
                                else -> "CurseForge 请求失败 (${response.code})"
                            },
                        )
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
                    ),
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

    suspend fun post(
        url: String,
        body: JsonObject,
        bearer: String? = null,
    ): JsonObject =
        withContext(Dispatchers.IO) {
            val request =
                builder(url).post(body.toString().toRequestBody("application/json".toMediaType()))
            bearer?.let { request.header("Authorization", "Bearer $it") }
            execute(request.build()).use { response ->
                if (!response.isSuccessful) throw ServiceHttpException(response.code, URI(url).host)
                val responseBody = requireNotNull(response.body) { "服务返回了空响应" }
                JsonParser.parseString(responseBody.string()).asJsonObject
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
    ) = withContext(Dispatchers.IO) {
        val uri = URI(url)
        require(
            (
                uri.scheme == "https" &&
                    uri.host == "api.minecraftservices.com" &&
                    uri.port == -1 || uri.scheme == "http" && uri.host == "127.0.0.1"
            ) &&
                uri.path == "/minecraft/profile/skins" &&
                uri.userInfo == null &&
                uri.query == null &&
                uri.fragment == null,
        ) {
            "无效的皮肤上传地址"
        }
        require(variant in setOf("classic", "slim") && png.size in 1..1024 * 1024) {
            "皮肤上传参数无效"
        }
        val body =
            MultipartBody
                .Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("variant", variant)
                .addFormDataPart(
                    "file",
                    "skin.png",
                    png.toRequestBody("image/png".toMediaType()),
                ).build()
        execute(
            builder(url).header("Authorization", "Bearer $token").post(body).build(),
            redirects = false,
        ).use { response ->
            if (!response.isSuccessful) throw ServiceHttpException(response.code, uri.host)
        }
    }

    suspend fun authorizedJson(
        url: String,
        token: String,
    ): JsonObject =
        withContext(Dispatchers.IO) {
            execute(builder(url).header("Authorization", "Bearer $token").build()).use { response ->
                if (response.code == 404) error("此账户没有 Minecraft Java 版档案，请确认已拥有游戏并创建角色")
                check(response.isSuccessful) { "读取游戏档案失败 (${response.code})" }
                val responseBody = requireNotNull(response.body) { "服务返回了空响应" }
                JsonParser.parseString(responseBody.string()).asJsonObject
            }
        }

    suspend fun form(
        url: String,
        fields: Map<String, String>,
    ): JsonObject =
        withContext(Dispatchers.IO) {
            val form =
                FormBody
                    .Builder()
                    .apply { fields.forEach { (key, value) -> add(key, value) } }
                    .build()
            execute(builder(url).post(form).build()).use { response ->
                val responseBody = requireNotNull(response.body) { "服务返回了空响应" }
                val result = JsonParser.parseString(responseBody.string()).asJsonObject
                if (!response.isSuccessful && !result.has("error")) {
                    error("认证服务请求失败 (${response.code})")
                }
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
    ) = withContext(Dispatchers.IO) {
        val target = artifact.target
        withTargetLock(target) {
            coroutineContext.ensureActive()
            coroutineContext[TaskDownloadContext]?.task?.beforeDirectoryCreation(target.parent)
            target.parent.createDirectories()
            val partial = partialFile(target)
            val path = target.toAbsolutePath().normalize()
            val context = coroutineContext[TaskDownloadContext]?.task
            val journal =
                context?.let {
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
            val candidates = orderedSources(artifact.url)
            try {
                fetchFromCandidates(
                    artifact,
                    partial,
                    candidates,
                    attempts.coerceIn(1, 8),
                    progress,
                )
                if (!existed) journal?.completed(path)
            } catch (cancelled: CancellationException) {
                discardPartial(target)
                throw cancelled
            } finally {
                if (!existed && target.isRegularFile()) journal?.completed(path)
                if (!coroutineContext.isActive) discardPartial(target)
            }
        }
    }

    /**
     * 依次尝试候选源：一个源失败立刻换下一个；403/429 不是失败，按 1→2→4s 退避后重试
     * 同一个源，且不计入禁用。所有源都失败时抛出带「每个源尝试的 URL 与状态码」的异常。
     */
    private suspend fun fetchFromCandidates(
        artifact: DownloadArtifact,
        partial: Path,
        candidates: List<CandidateSource>,
        attempts: Int,
        progress: (Long, Long) -> Unit,
    ) {
        val failures = mutableListOf<SourceAttempt>()
        if (candidates.isEmpty()) {
            throw DownloadSourceFailureException(
                artifact.target.toString(),
                failures,
            )
        }
        val perSource = if (candidates.size > 1) 1 else attempts
        val budget = transferBudget()
        var lastError: Exception? = null
        for (candidate in candidates) {
            var attempt = 0
            var rateLimitRetries = 0
            while (true) {
                coroutineContext.ensureActive()
                attempt++
                try {
                    // 传输闸门（内存预算）在外、源闸门（镜像并发与间隔）在内，覆盖整次文件传输。
                    budget.run(artifact.size, ::logClampedConcurrency) {
                        SourceThrottle.run(candidate.source, mirrorConcurrency()) {
                            val segmented =
                                SegmentedDownloader { url, range ->
                                    execute(
                                        builder(url).header("Range", range).build(),
                                        attempts = 1,
                                    )
                                }
                            if (
                                !segmented.download(
                                    artifact,
                                    partial,
                                    candidate.url,
                                    downloadSegments(),
                                    progress,
                                )
                            ) {
                                fetch(artifact, partial, candidate.url, progress)
                            }
                        }
                    }
                    health.recordSuccess(candidate.source.key)
                    return
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    coroutineContext.ensureActive()
                    lastError = error
                    val status = (error as? DownloadHttpException)?.code
                    val rateLimited = status != null && status in RATE_LIMIT_STATUSES
                    if (rateLimited && rateLimitRetries < MAX_RATE_LIMIT_RETRIES) {
                        rateLimitRetries++
                        val wait = health.recordRateLimited(candidate.source.key)
                        logger("源 ${candidate.source.title} 返回 $status，${wait}ms 后重试同一源", false)
                        delay(wait)
                        attempt--
                        continue
                    }
                    failures +=
                        SourceAttempt(
                            candidate.source.key,
                            candidate.source.title,
                            candidate.url,
                            status,
                            error.message ?: error.javaClass.simpleName,
                        )
                    if (!rateLimited && health.recordFailure(candidate.source.key)) {
                        val count = health.state(candidate.source.key).consecutiveFailures
                        logger("源 ${candidate.source.title} 已禁用：连续 $count 次失败", true)
                    }
                    if (attempt < perSource &&
                        isRecoverableDownloadFailure(error, candidates.size > 1)
                    ) {
                        delay(minOf(500L shl (attempt - 1), 8_000L))
                        continue
                    }
                    break
                }
            }
        }
        // 只有一个候选源时保留原来的异常类型与中文消息（调用方按状态码分类）；
        // 多个源都失败时汇总，逐个列出尝试过的 URL 与状态码。
        if (candidates.size ==
            1
        ) {
            throw lastError
                ?: DownloadSourceFailureException(artifact.target.toString(), failures)
        }
        throw DownloadSourceFailureException(artifact.target.toString(), failures)
    }

    /**
     * 可选文件：所有候选源都失败时只警告并跳过，不中断整批任务。
     * 必需文件仍用 [download]，失败时消息里列出每个源尝试的 URL 与状态码。
     */
    suspend fun downloadOptional(
        artifact: DownloadArtifact,
        attempts: Int = downloadAttempts().coerceIn(1, 8),
        progress: (Long, Long) -> Unit = { _, _ -> },
    ): Boolean =
        try {
            download(artifact, attempts, progress)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            logger("可选文件下载失败，已跳过：${artifact.target.fileName}（${error.message}）", false)
            false
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
        throw DownloadHttpException(416, URI(url).host)
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
        if (!response.isSuccessful) {
            // 报告实际请求的源（可能是镜像），否则失败信息会把镜像 404 说成官方地址。
            throw DownloadHttpException(response.code, response.request.url.host)
        }
        val body = response.body ?: throw IOException("下载内容为空")
        val total = totalBytes(artifact, response, start)
        val digest = MessageDigest.getInstance(artifact.hashAlgorithm)
        if (start > 0) updateDigest(partial, digest, start)
        var received = 0L
        body.byteStream().use { input ->
            Files
                .newOutputStream(
                    partial,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND,
                ).use { output ->
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
                "文件大小校验失败：${artifact.target.fileName}（期望 ${artifact.size} 字节，实际 $size 字节）",
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
    private fun resumeOffset(
        artifact: DownloadArtifact,
        partial: Path,
    ): Long {
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
    private fun updateDigest(
        partial: Path,
        digest: MessageDigest,
        length: Long,
    ) {
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
    private fun totalBytes(
        artifact: DownloadArtifact,
        response: Response,
        start: Long,
    ): Long {
        if (artifact.size > 0) return artifact.size
        if (response.code == 206) {
            contentRange(response.header("Content-Range"))?.let {
                return it.second
            }
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
    private suspend fun <T> withTargetLock(
        target: Path,
        block: suspend () -> T,
    ): T {
        val key = taskPathKey(target)
        val index = Math.floorMod(key.hashCode(), downloadLocks.size)
        return downloadLocks[index].withLock { block() }
    }

    /**
     * 兼容旧调用方的批量下载：文件级并发交给 [DownloadScheduler]，逐文件失败在整批结束后
     * 一次性抛 [DownloadBatchException]（不在第一个 404 就放弃其余文件）。
     */
    suspend fun downloadAll(
        artifacts: List<DownloadArtifact>,
        concurrency: Int,
        context: JobContext,
        label: String,
        from: Float = 0f,
        to: Float = 1f,
    ) {
        val progress = BatchRangeProgress(context, label, from, to)
        val failures = DownloadScheduler(this).run(artifacts, context, concurrency, progress)
        if (failures.isNotEmpty()) throw DownloadBatchException(failures)
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

        /** 自动源只在官方与 BMCLAPI 之间挑，和设置里的两个手动源一一对应。 */
        private val AUTO_PROBE_SOURCES = listOf("official", "bmclapi")

        /** 403/429：限流而不是失败，退避后重试同一源，不计入禁用。 */
        private val RATE_LIMIT_STATUSES = setOf(403, 429)

        /** 单个文件对同一源的限流重试次数；1s→2s→4s 之后仍被拒就换下一个源。 */
        private const val MAX_RATE_LIMIT_RETRIES = 3

        /** 探测用的短超时：慢就当作不可用，不能拖住真正的下载。 */
        const val AUTO_PROBE_TIMEOUT_MILLIS = 1_500L

        /** 探测目标：两个源都提供同一份版本清单，只读开头 256 字节确认可达。 */
        private const val AUTO_PROBE_URL =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    }
}

/** 认证写请求仅在连接/握手阶段重试；证书与主机名验证失败不做重试。 */
internal fun canRetryRequest(
    method: String,
    failure: IOException,
): Boolean {
    val causes = generateSequence<Throwable>(failure) { it.cause }.take(16).toList()
    if (causes.any { it is CertificateException || it is SSLPeerUnverifiedException }) return false
    return method == "GET" || failure is SSLHandshakeException || failure is ConnectException
}

fun mirrorUrl(
    url: String,
    source: String,
): String {
    val secure =
        if (url.startsWith("http://") && URI(url).host !in setOf("localhost", "127.0.0.1")) {
            url.replaceFirst("http://", "https://")
        } else {
            url
        }
    if (source != "bmclapi") return secure
    val uri = URI(secure)
    return when (uri.host) {
        "piston-meta.mojang.com",
        "launchermeta.mojang.com",
        "piston-data.mojang.com",
        "launcher.mojang.com",
        -> "https://bmclapi2.bangbang93.com${uri.rawPath}"
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
            ),
    ) {
        "不支持的整合包下载域名：${uri.host}"
    }
    return url
}
