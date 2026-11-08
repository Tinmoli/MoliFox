// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责网络请求、下载校验、重试和断点续传。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import dev.molifox.core.net.DOWNLOAD_BUDGET_BYTES
import dev.molifox.core.net.DownloadSourceCatalog
import dev.molifox.core.net.SourceHealth
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.ConnectException
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.nio.file.Path
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** 默认总尝试次数（含首次）：首次 + 4 次重试。 */
private const val DEFAULT_DOWNLOAD_ATTEMPT_COUNT = 5

/** 分段下载的默认分段数。 */
private const val DEFAULT_DOWNLOAD_SEGMENTS = 1

/** 请求超时默认秒数。 */
private const val DEFAULT_REQUEST_TIMEOUT_SECONDS = 45

/** 文件级并发数与镜像并发数的默认值。 */
private val DEFAULT_MAX_CONCURRENT_DOWNLOADS = DEFAULT_DOWNLOAD_THREADS
private val DEFAULT_MIRROR_CONCURRENCY = DEFAULT_DOWNLOAD_THREADS

/** 默认的 HTTPS 端口。 */
private const val HTTPS_PORT = 443

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
) : IOException(I18n.t("networkA.downloadHttp.failed", code, host))

/** 大小或哈希不符：内容不可用，必须删除 `.part` 后重下，不能留下坏文件。 */
internal class DownloadVerificationException(
    message: String,
) : IOException(message)

/**
 * 网络客户端入口：请求、候选源与文件传输的实现各自独立，本类只持有同一份会话状态。
 *
 * 方法体分散在 [NetworkTextApi]、[NetworkPayloadApi] 与 [NetworkTransferApi] 的委托实现里，
 * 调用点仍是 `network.text(...)` 这样的成员调用，公开签名与行为保持不变。
 */
class NetworkClient
    internal constructor(
        internal val actions: NetworkActions,
    ) : AutoCloseable,
        NetworkTextApi by actions,
        NetworkPayloadApi by actions,
        NetworkTransferApi by actions {
        @JvmOverloads
        constructor(
            source: () -> String = { "auto" },
            // 每次请求时读取代理设置，保存设置后无需重建客户端。
            proxyProvider: () -> Proxy? = { null },
            systemProxyEnabled: () -> Boolean = { false },
            systemProxySelector: ProxySelector = SystemProxyResolver(),
            downloadSegments: () -> Int = { DEFAULT_DOWNLOAD_SEGMENTS },
            downloadAttempts: () -> Int = { DEFAULT_DOWNLOAD_ATTEMPT_COUNT },
            requestTimeoutSeconds: () -> Int = { DEFAULT_REQUEST_TIMEOUT_SECONDS },
            /** 文件级并发数（设置项「并发下载数」）：只用来换算传输许可，派发仍由调度器决定。 */
            maxConcurrent: () -> Int = { DEFAULT_MAX_CONCURRENT_DOWNLOADS },
            /** 镜像类主机的并发上限（设置项「镜像并发」）。 */
            mirrorConcurrency: () -> Int = { DEFAULT_MIRROR_CONCURRENCY },
            /** 单任务下载预算：并发数 × 文件大小超过它时按大小分配传输许可。 */
            downloadBudgetBytes: Long = DOWNLOAD_BUDGET_BYTES,
            /** 源登记表：官方、BMCLAPI 与各 Maven 镜像。 */
            catalog: DownloadSourceCatalog = DownloadSourceCatalog.DEFAULT,
            /** 本轮任务的源健康度：连续失败禁用、403/429 退避。 */
            health: SourceHealth = SourceHealth(),
            /** 源被禁用、限流等需要留痕的事件。 */
            logger: (String, Boolean) -> Unit = { _, _ -> },
            interceptors: List<okhttp3.Interceptor> = emptyList(),
            githubSources: () -> GitHubSourceOptions = { GitHubSourceOptions() },
        ) : this(
            NetworkActions(
                NetworkClientSettings(
                    source = source,
                    proxyProvider = proxyProvider,
                    systemProxyEnabled = systemProxyEnabled,
                    systemProxySelector = systemProxySelector,
                    downloadSegments = downloadSegments,
                    downloadAttempts = downloadAttempts,
                    requestTimeoutSeconds = requestTimeoutSeconds,
                    maxConcurrent = maxConcurrent,
                    mirrorConcurrency = mirrorConcurrency,
                    downloadBudgetBytes = downloadBudgetBytes,
                    catalog = catalog,
                    health = health,
                    logger = logger,
                    interceptors = interceptors,
                    githubSources = githubSources,
                ),
            ),
        )

        /** 本轮任务的源健康度，供设置页与诊断读取。 */
        val health: SourceHealth
            get() = actions.settings.health

        internal val client: OkHttpClient
            get() = actions.client

        /** 下载到临时文件，校验整体大小与摘要后替换目标；网络失败可续传，显式取消则清理。 */
        suspend fun download(
            artifact: DownloadArtifact,
            attempts: Int = actions.transport.attemptLimit(),
            progress: (Long, Long) -> Unit = { _, _ -> },
        ) = actions.download(artifact, attempts, progress)

        /**
         * 可选文件：所有候选源都失败时只警告并跳过，不中断整批任务。
         * 必需文件仍用 [download]，失败时消息里列出每个源尝试的 URL 与状态码。
         */
        suspend fun downloadOptional(
            artifact: DownloadArtifact,
            attempts: Int = actions.transport.attemptLimit(),
            progress: (Long, Long) -> Unit = { _, _ -> },
        ): Boolean = actions.downloadOptional(artifact, attempts, progress)

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

        fun mirror(url: String): String = mirrorUrl(url, actions.settings.source())

        override fun close() {
            actions.transport.close()
        }

        companion object {
            /** 默认总尝试次数（含首次）：首次 + 4 次重试；重试沿用已有 `.part`。 */
            const val DEFAULT_DOWNLOAD_ATTEMPTS = DEFAULT_DOWNLOAD_ATTEMPT_COUNT

            /** 探测用的短超时：慢就当作不可用，不能拖住真正的下载。 */
            const val AUTO_PROBE_TIMEOUT_MILLIS = 1_500L
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
            uri.port in setOf(-1, HTTPS_PORT) &&
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
        I18n.t("networkA.packUrl.unsupportedHost", uri.host)
    }
    return url
}
