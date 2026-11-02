// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责 OkHttp 客户端、请求重试与失败翻译。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import dev.molifox.core.net.DownloadSourceCatalog
import dev.molifox.core.net.SourceHealth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionSpec
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.TlsVersion
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** 单文件尝试次数上限与两处重试的等待基数。 */
internal const val MAX_DOWNLOAD_ATTEMPT_LIMIT = 8
private const val RETRY_DELAY_STEP_MILLIS = 250L

/** 测速只读响应开头的一小段，并把纳秒换算成毫秒。 */
private const val PROBE_READ_BYTES = 48
private const val NANOS_PER_MILLI = 1_000_000L
private const val MIN_REQUEST_TIMEOUT_SECONDS = 10
private const val MAX_REQUEST_TIMEOUT_SECONDS = 180
private const val DEFAULT_REQUEST_TIMEOUT_SECONDS = 45
private const val MAX_CONNECT_TIMEOUT_SECONDS = 30

/** 网络客户端的全部配置来源：设置改动通过 lambda 在每次请求时读取。 */
internal class NetworkClientSettings(
    val source: () -> String,
    val proxyProvider: () -> Proxy?,
    val systemProxyEnabled: () -> Boolean,
    val systemProxySelector: ProxySelector,
    val downloadSegments: () -> Int,
    val downloadAttempts: () -> Int,
    val requestTimeoutSeconds: () -> Int,
    val maxConcurrent: () -> Int,
    val mirrorConcurrency: () -> Int,
    val downloadBudgetBytes: Long,
    val catalog: DownloadSourceCatalog,
    val health: SourceHealth,
    val logger: (String, Boolean) -> Unit,
    val interceptors: List<Interceptor>,
    val githubSources: () -> GitHubSourceOptions,
)

private fun requestLimit(settings: NetworkClientSettings): Int {
    val files = settings.maxConcurrent().coerceIn(1, MAX_DOWNLOAD_THREADS)
    val segments = settings.downloadSegments().coerceIn(1, MAX_DOWNLOAD_SEGMENTS)
    return files * segments
}

private fun configureRequestLimits(
    client: OkHttpClient,
    settings: NetworkClientSettings,
) {
    val limit = requestLimit(settings)
    if (client.dispatcher.maxRequests != limit) client.dispatcher.maxRequests = limit
    if (client.dispatcher.maxRequestsPerHost != limit) client.dispatcher.maxRequestsPerHost = limit
}

private fun NetworkClientSettings.boundedRequestTimeout(): Int =
    requestTimeoutSeconds().coerceIn(MIN_REQUEST_TIMEOUT_SECONDS, MAX_REQUEST_TIMEOUT_SECONDS)

private data class NetworkClientVariants(
    val regular: OkHttpClient,
    val noRedirect: OkHttpClient,
    val direct: OkHttpClient,
    val directNoRedirect: OkHttpClient,
) {
    fun configured(
        timeout: Int,
        redirects: Boolean,
        directConnection: Boolean,
    ): OkHttpClient {
        val base =
            when {
                directConnection && redirects -> direct
                directConnection -> directNoRedirect
                redirects -> regular
                else -> noRedirect
            }
        if (timeout == DEFAULT_REQUEST_TIMEOUT_SECONDS) return base
        return base
            .newBuilder()
            .connectTimeout(minOf(timeout, MAX_CONNECT_TIMEOUT_SECONDS).toLong(), TimeUnit.SECONDS)
            .readTimeout(timeout.toLong(), TimeUnit.SECONDS)
            .build()
    }
}

/** 请求执行：连接池、超时、重试与中文错误翻译。 */
internal class NetworkTransport(
    private val settings: NetworkClientSettings,
) {
    val client =
        OkHttpClient
            .Builder()
            .apply {
                settings.interceptors.forEach(::addInterceptor)
                windowsTlsConfiguration()?.let { (factory, manager) ->
                    sslSocketFactory(factory, manager)
                }
            }.dispatcher(
                Dispatcher().apply {
                    maxRequests = requestLimit(settings)
                    maxRequestsPerHost = maxRequests
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
                    override fun select(uri: URI?): List<Proxy> = selectProxy(uri)

                    override fun connectFailed(
                        uri: URI?,
                        sa: SocketAddress?,
                        error: IOException?,
                    ) {
                        if (settings.systemProxyEnabled()) {
                            settings.systemProxySelector.connectFailed(uri, sa, error)
                        }
                    }
                },
            ).build()

    val noRedirectClient by lazy {
        client
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /** 代理失败后的兜底：绕开代理再直接试一次，避免代理节点异常时整次启动被拖垮。 */
    private val directClient by lazy { client.newBuilder().proxy(Proxy.NO_PROXY).build() }

    private val directNoRedirectClient by lazy {
        directClient
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
    private val clientVariants by lazy {
        NetworkClientVariants(client, noRedirectClient, directClient, directNoRedirectClient)
    }

    private fun selectProxy(uri: URI?): List<Proxy> {
        if (uri?.host?.let { isLoopbackHost(it) } == true) {
            return listOf(Proxy.NO_PROXY)
        }
        settings.proxyProvider()?.let {
            return listOf(it)
        }
        return if (settings.systemProxyEnabled()) {
            settings.systemProxySelector.select(uri)
        } else {
            listOf(Proxy.NO_PROXY)
        }
    }

    /** 本次请求是否真的走了代理；用于失败说明与直连兜底。 */
    private fun proxiedRoute(request: Request): Proxy? =
        selectProxy(request.url.toUri()).firstOrNull { it.type() != Proxy.Type.DIRECT }

    /** 自动源测速专用：连接、读取与整次调用都压在同一段短超时内（与启动器的镜像探测一致）， 复用同一连接池与调度器，不额外占线程。 */
    val probeClient by lazy {
        client
            .newBuilder()
            .connectTimeout(NetworkClient.AUTO_PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .readTimeout(NetworkClient.AUTO_PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .callTimeout(NetworkClient.AUTO_PROBE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .build()
    }

    /** 单文件尝试次数：设置值夹在 1..8 之间。 */
    fun attemptLimit(): Int = settings.downloadAttempts().coerceIn(1, MAX_DOWNLOAD_ATTEMPT_LIMIT)

    suspend fun execute(
        request: Request,
        redirects: Boolean = true,
        attempts: Int = attemptLimit(),
    ): Response {
        for (attempt in 1..attempts) {
            try {
                return executeOnce(request, redirects)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                handleFailure(request, redirects, attempt, attempts, failure)?.let { return it }
            }
        }
        error("Network retry state exhausted")
    }

    /** 最后一次尝试先改直连，再抛出带原因的错误；其余情况退避后重试。 */
    private suspend fun handleFailure(
        request: Request,
        redirects: Boolean,
        attempt: Int,
        attempts: Int,
        failure: IOException,
    ): Response? {
        if (attempt == attempts || !canRetryRequest(request.method, failure)) {
            retryDirect(request, redirects, failure)?.let { return it }
            throw connectionFailure(request, attempt, failure, proxyRoute(request))
        }
        delay(RETRY_DELAY_STEP_MILLIS * attempt)
        return null
    }

    /**
     * 代理路径最终失败后，改走直连再试一次：代理节点或规则异常时这是唯一还能救回请求的办法。
     * 证书校验失败不重试（换直连只会重复失败），非幂等请求也不重试。
     */
    private suspend fun retryDirect(
        request: Request,
        redirects: Boolean,
        failure: IOException,
    ): Response? {
        if (proxiedRoute(request) == null) return null
        if (!canRetryRequest(request.method, failure)) return null
        val route = proxyRoute(request)
        settings.logger(
            I18n.t(
                "networkA.transport.directRetry",
                route,
                request.url.host,
                failure.javaClass.simpleName,
            ),
            true,
        )
        return try {
            executeOnce(request, redirects, direct = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (directFailure: IOException) {
            settings.logger(
                I18n.t("networkA.transport.directRetryFailed", directFailure.javaClass.simpleName),
                true,
            )
            null
        }
    }

    private suspend fun executeOnce(
        request: Request,
        redirects: Boolean,
        direct: Boolean = false,
    ): Response {
        configureRequestLimits(client, settings)
        val configured = clientVariants.configured(settings.boundedRequestTimeout(), redirects, direct)
        return suspendCancellableCoroutine { continuation ->
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
    }

    /** 文件与分段已有独立上限；HTTP 队列只跟随当前设置，不额外压低同主机请求。 */
    private fun proxyRoute(request: Request): String {
        val proxy = proxiedRoute(request) ?: return I18n.t("networkA.transport.direct")
        val label =
            if (settings.proxyProvider() != null) {
                I18n.t("networkA.transport.manualProxy", proxy.type())
            } else {
                I18n.t("networkA.transport.systemProxy")
            }
        val address = proxy.address()
        val detail =
            if (address is java.net.InetSocketAddress) {
                "${address.hostString}:${address.port}"
            } else {
                I18n.t("networkA.transport.unresolvedAddress")
            }
        return "$label $detail"
    }

    fun builder(
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
            I18n.t("networkA.transport.httpsOnly")
        }
        return Request
            .Builder()
            .url(url)
            .header("User-Agent", "MoliFox/$LAUNCHER_VERSION (Minecraft Java Edition launcher)")
    }

    /** 单源测速：取版本清单开头的一小段确认真的能取到内容，返回毫秒；不可达返回 null。 */
    suspend fun measureUrl(url: String): Long? =
        withContext(Dispatchers.IO) {
            val started = System.nanoTime()
            try {
                probeClient
                    .newCall(builder(url).header("Range", "bytes=0-255").build())
                    .execute()
                    .use { response ->
                        if (!response.isSuccessful) return@withContext null
                        response.body?.byteStream()?.read(ByteArray(PROBE_READ_BYTES))
                        (System.nanoTime() - started) / NANOS_PER_MILLI
                    }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }

    fun close() {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}

/** 网络动作共用的配置与请求入口：请求、源选择与下载都挂在同一份会话状态上。 */
internal abstract class NetworkActionBase(
    internal val settings: NetworkClientSettings,
) {
    internal val transport = NetworkTransport(settings)

    internal val client: OkHttpClient
        get() = transport.client

    internal fun builder(
        url: String,
        allowInsecureHttp: Boolean = false,
    ): Request.Builder = transport.builder(url, allowInsecureHttp)

    internal suspend fun execute(
        request: Request,
        redirects: Boolean = true,
        attempts: Int = transport.attemptLimit(),
    ): Response = transport.execute(request, redirects, attempts)

    internal suspend fun measureUrl(url: String): Long? = transport.measureUrl(url)
}

/** 把底层 IO 失败翻译成带证书与代理说明的中文错误，原始异常保留为 cause。 */
private fun connectionFailure(
    request: Request,
    attempt: Int,
    failure: IOException,
    route: String,
): IOException {
    val reason = failureReason(failure)
    return IOException(
        I18n.t(
            "networkA.transport.connectionFailed",
            request.url.host,
            reason,
            route,
            attempt,
            failure.javaClass.simpleName,
        ),
        failure,
    )
}

private fun failureReason(failure: IOException): String {
    val causes = generateSequence<Throwable>(failure) { it.cause }.take(16).toList()
    return when {
        causes.any { it is CertificateException || it is SSLPeerUnverifiedException } ->
            I18n.t("networkA.transport.certFailed")
        failure is SSLHandshakeException -> I18n.t("networkA.transport.tlsHandshakeFailed")
        else -> I18n.t("networkA.transport.networkFailed")
    }
}
