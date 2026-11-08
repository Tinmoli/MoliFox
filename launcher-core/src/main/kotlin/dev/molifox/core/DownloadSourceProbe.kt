// 使用同一网络会话并行抽样下载源，按小块传输速度排序；取消会关闭探测请求，不落盘。
package dev.molifox.core

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val MAX_PROBE_SOURCES = 4
private const val SAMPLE_BYTES = 64 * 1024
private const val PROBE_TIMEOUT_MS = 3_000L
private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val MIN_PROBE_NANOS = 1L

suspend fun NetworkClient.rankDownloadSources(urls: List<String>): List<String> =
    coroutineScope {
        val candidates = urls.distinct()
        val probeClient =
            client
                .newBuilder()
                .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()
        val ranked =
            candidates
                .take(MAX_PROBE_SOURCES)
                .map { url ->
                    async(Dispatchers.IO) {
                        url to
                            measureDownloadSource(
                                probeClient,
                                url,
                            ) { message -> actions.settings.logger(message, false) }
                    }
                }.awaitAll()
                .sortedWith(compareByDescending<Pair<String, Double?>> { it.second ?: -1.0 })
        ranked.map { it.first } + candidates.drop(MAX_PROBE_SOURCES)
    }

private suspend fun measureDownloadSource(
    client: OkHttpClient,
    url: String,
    report: (String) -> Unit,
): Double? =
    suspendCancellableCoroutine { continuation ->
        val request =
            Request
                .Builder()
                .url(url)
                .header("Range", "bytes=0-${SAMPLE_BYTES - 1}")
                .header("Accept-Encoding", "identity")
                .build()
        val call = client.newCall(request)
        val started = System.nanoTime()
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(DownloadProbeCallback(continuation, request, started, report))
    }

private class DownloadProbeCallback(
    private val continuation: CancellableContinuation<Double?>,
    private val request: Request,
    private val started: Long,
    private val report: (String) -> Unit,
) : Callback {
    override fun onFailure(
        call: Call,
        e: IOException,
    ) {
        report(I18n.t("networkA.probe.incomplete", request.url.host, e.message))
        if (continuation.isActive) continuation.resumeWith(Result.success(null))
    }

    override fun onResponse(
        call: Call,
        response: Response,
    ) {
        val speed =
            try {
                response.use { sampleDownloadSpeed(it, started) }
            } catch (failure: IOException) {
                report(I18n.t("networkA.probe.readFailed", request.url.host, failure.message))
                null
            }
        report(
            I18n.t(
                "networkA.probe.result",
                request.url.host,
                speed?.toLong()?.let { "$it B/s" } ?: I18n.t("networkA.probe.unavailable"),
            ),
        )
        if (continuation.isActive) continuation.resumeWith(Result.success(speed))
    }
}

private fun sampleDownloadSpeed(
    response: Response,
    started: Long,
): Double? {
    if (!response.isSuccessful ||
        response.header("Content-Type").orEmpty().contains("text/html")
    ) {
        return null
    }
    val body = response.body ?: return null
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var received = 0
    body.byteStream().use { input ->
        while (received < SAMPLE_BYTES) {
            val count = input.read(buffer, 0, minOf(buffer.size, SAMPLE_BYTES - received))
            if (count < 0) break
            received += count
        }
    }
    if (received == 0) return null
    return received * NANOS_PER_SECOND /
        (System.nanoTime() - started).coerceAtLeast(MIN_PROBE_NANOS)
}
