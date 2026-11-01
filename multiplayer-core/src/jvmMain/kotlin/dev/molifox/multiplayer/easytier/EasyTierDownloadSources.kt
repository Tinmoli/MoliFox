// 安装包下载源探测与 TLS 兼容：镜像测速、系统信任库回退，供安装器选择最快来源。
package dev.molifox.multiplayer.easytier

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/** 探测下载源速度；测试注入假实现，生产走真实 Range 请求。 */
fun interface EasyTierSourceProbe {
    /** 返回实测下载速度（字节/秒）；不可用或样本不足返回 null。 */
    suspend fun measureSpeed(url: String): Long?
}

/**
 * 真实测速：对每个下载源发一个 Range 请求，在固定时间预算内尽量多读，
 * 用「读到的字节数 / 实际耗时」作为速度。这样不会因为只看前 64 KiB 的首包突发
 * 而把实际已经限速的源选成最快。
 *
 * 时间预算默认 5 秒、最多读 1 MiB；预算内读完 1 MiB 也算完成。
 */
class HttpEasyTierSourceProbe(
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    private val sampleBytes: Int = DEFAULT_SAMPLE_BYTES,
    private val timeBudgetMillis: Long = DEFAULT_TIME_BUDGET_MILLIS,
) : EasyTierSourceProbe {
    override suspend fun measureSpeed(url: String): Long? =
        runCatching {
            withHttpConnection(url) { connection ->
                connection.requestMethod = "GET"
                connection.setRequestProperty("Range", "bytes=0-${sampleBytes - 1}")
                connection.setRequestProperty("User-Agent", EASY_TIER_USER_AGENT)
                val status = connection.responseCode
                if (status !in HTTP_OK) throw IOException("HTTP $status")
                val startedNanos = System.nanoTime()
                val received =
                    connection.inputStream.use { input ->
                        readWithinBudget(input, startedNanos)
                    }
                val elapsedMillis =
                    maxOf(
                        MIN_ELAPSED_MILLIS,
                        (System.nanoTime() - startedNanos) / NANOS_PER_MILLI,
                    )
                if (received < MIN_SAMPLE_BYTES) throw IOException(I18n.t("multiplayer.easyTierDownloadSources.1"))
                received * MILLIS_PER_SECOND / elapsedMillis
            }
        }.onFailure { failure ->
            if (failure is CancellationException) throw failure
        }.getOrNull()

    private fun readWithinBudget(
        input: InputStream,
        startedNanos: Long,
    ): Int {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0
        var stopped = false
        while (!stopped && total < sampleBytes && elapsedMillis(startedNanos) < timeBudgetMillis) {
            val count =
                try {
                    input.read(buffer, 0, minOf(buffer.size, sampleBytes - total))
                } catch (failure: SocketTimeoutException) {
                    if (total == 0) throw failure
                    -1
                }
            if (count < 0) {
                stopped = true
            } else {
                total += count
            }
        }
        return total
    }

    private fun elapsedMillis(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / NANOS_PER_MILLI

    private companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 4_000
        const val DEFAULT_READ_TIMEOUT_MILLIS = 3_000
        const val DEFAULT_SAMPLE_BYTES = 1024 * 1024
        const val DEFAULT_TIME_BUDGET_MILLIS = 5_000L
        const val BUFFER_BYTES = 16 * 1024
        const val MIN_SAMPLE_BYTES = 8 * 1024
        const val MIN_ELAPSED_MILLIS = 1L
        const val NANOS_PER_MILLI = 1_000_000L
        const val MILLIS_PER_SECOND = 1_000L
        val HTTP_OK = 200..299
    }
}

/**
 * 打开连接并在 TLS 握手失败时用**系统信任库**重试一次。
 *
 * 部分 Windows 环境（企业代理、杀毒软件中间人）的根证书只在系统证书库里，JDK 自带
 * cacerts 验证 GitHub 会报 `PKIX path building failed`。这里只放宽 TLS 信任来源，
 * 下载内容仍由官方 SHA-256 校验，不会安装被篡改的文件。
 */
internal suspend fun <T> withHttpConnection(
    url: String,
    block: suspend (HttpURLConnection) -> T,
): T {
    val first = openConnection(url, systemTrust = false)
    try {
        return block(first)
    } catch (failure: SSLException) {
        val factory = SystemTrust.socketFactory() ?: throw failure
        val retry = openConnection(url, systemTrust = true, socketFactory = factory)
        try {
            return block(retry)
        } finally {
            retry.disconnect()
        }
    } finally {
        first.disconnect()
    }
}

private fun openConnection(
    url: String,
    systemTrust: Boolean,
    socketFactory: SSLSocketFactory? = null,
): HttpURLConnection {
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    connection.instanceFollowRedirects = true
    connection.useCaches = false
    if (systemTrust && connection is HttpsURLConnection) {
        socketFactory?.let { factory -> connection.sslSocketFactory = factory }
    }
    return connection
}

/** 懒加载系统信任库；不可用时返回 null，调用方保留默认 TLS 行为。 */
private object SystemTrust {
    private val factory: SSLSocketFactory? by lazy { load() }

    fun socketFactory(): SSLSocketFactory? = factory

    private fun load(): SSLSocketFactory? =
        runCatching {
            val merged = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            var added = addWindowsRoots(merged)
            if (added == 0) added = addLinuxCaBundle(merged)
            if (added == 0) return null
            val managers =
                TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm(),
                )
            managers.init(merged)
            SSLContext
                .getInstance(
                    "TLS",
                ).apply { init(null, managers.trustManagers, null) }
                .socketFactory
        }.getOrNull()

    private fun addWindowsRoots(target: KeyStore): Int {
        if (!isWindows()) return 0
        return runCatching {
            val roots = KeyStore.getInstance("Windows-ROOT").apply { load(null, null) }
            var added = 0
            roots.aliases().toList().forEach { alias ->
                val certificate = roots.getCertificate(alias)
                if (certificate is X509Certificate) {
                    target.setCertificateEntry("windows-$alias", certificate)
                    added++
                }
            }
            added
        }.getOrDefault(0)
    }

    private fun addLinuxCaBundle(target: KeyStore): Int {
        LINUX_CA_FILES.forEach { path ->
            val file = java.io.File(path)
            if (!file.isFile) return@forEach
            val added =
                runCatching {
                    val certificates =
                        file.inputStream().use { input ->
                            CertificateFactory.getInstance("X.509").generateCertificates(input)
                        }
                    var count = 0
                    certificates.forEachIndexed { index, certificate ->
                        target.setCertificateEntry("system-$path-$index", certificate)
                        count++
                    }
                    count
                }.getOrDefault(0)
            if (added > 0) return added
        }
        return 0
    }

    private fun isWindows(): Boolean =
        System
            .getProperty("os.name")
            .orEmpty()
            .lowercase(Locale.ROOT)
            .startsWith("windows")

    private val LINUX_CA_FILES =
        listOf(
            "/etc/ssl/certs/ca-certificates.crt",
            "/etc/ssl/certs/ca-bundle.crt",
            "/etc/pki/tls/certs/ca-bundle.crt",
        )
}

/** 下载与测速共用的 UA；不包含用户标识。 */
internal const val EASY_TIER_USER_AGENT = "MoliFox-Launcher"
