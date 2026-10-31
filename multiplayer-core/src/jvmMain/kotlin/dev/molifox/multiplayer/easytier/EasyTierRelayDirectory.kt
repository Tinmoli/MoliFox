// EasyTier 中继发现：启动时实时拉取 PCL CE 的活动节点接口，失败时退回固定节点。
package dev.molifox.multiplayer.easytier

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException

/** 拉取节点列表文本；测试注入假实现，避免单测访问网络。 */
fun interface RelayListFetcher {
    suspend fun fetch(url: String): Result<String>
}

/**
 * 拉取 PCL CE 使用的 `/api/nodes` 接口。
 *
 * 只做一次 GET，带连接/读取超时和响应大小上限；任何失败都转成 [Result]，由
 * [EasyTierRelayDirectory] 决定是否退回固定节点，不阻塞房间创建。
 */
class HttpRelayListFetcher(
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    private val maxBytes: Long = MAX_RESPONSE_BYTES,
) : RelayListFetcher {
    override suspend fun fetch(url: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching { readResponse(url) }.onFailure { failure ->
                if (failure is CancellationException) throw failure
            }
        }

    private fun readResponse(url: String): String {
        val connection =
            URI(url).toURL().openConnection().let { created ->
                created as? HttpURLConnection
                    ?: throw IOException("EasyTier 节点列表地址必须是 HTTP(S)：$url")
            }
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            if (status !in HTTP_OK_RANGE) {
                throw IOException("EasyTier 节点列表接口返回 HTTP $status")
            }
            return readBody(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBody(connection: HttpURLConnection): String {
        val expected = connection.contentLengthLong
        if (expected > maxBytes) throw IOException("EasyTier 节点列表响应过大：$expected 字节")
        connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            val builder = StringBuilder()
            val buffer = CharArray(READ_BUFFER_CHARS)
            var total = 0L
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw IOException("EasyTier 节点列表响应过大")
                builder.append(buffer, 0, read)
            }
            return builder.toString()
        }
    }

    private companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 3_000
        const val DEFAULT_READ_TIMEOUT_MILLIS = 5_000
        const val MAX_RESPONSE_BYTES = 256L * 1024
        const val READ_BUFFER_CHARS = 8 * 1024
        val HTTP_OK_RANGE = 200..299
    }
}

/** 解析 PCL CE 活动节点 JSON；只保留 `is_active && allow_relay` 的 `address`。 */
internal fun parseActiveRelays(json: String): Result<List<String>> =
    runCatching {
        val root = JsonParser.parseString(json)
        if (!root.isJsonObject) throw IOException("EasyTier 节点列表不是 JSON 对象")
        val data = root.asJsonObject.getAsJsonObject("data") ?: throw IOException("节点列表缺少 data")
        val items = data.getAsJsonArray("items") ?: throw IOException("节点列表缺少 data.items")
        items.mapNotNull(::parseActiveRelay).distinct()
    }

private fun parseActiveRelay(element: JsonElement): String? {
    if (!element.isJsonObject) return null
    val item = element.asJsonObject
    if (!item.booleanOrFalse("is_active") || !item.booleanOrFalse("allow_relay")) return null
    val address = item.stringOrNull("address")?.trim().orEmpty()
    return address.takeIf { RELAY_PATTERN.matches(it) }
}

private fun JsonObject.stringOrNull(key: String): String? {
    val value = get(key) ?: return null
    return if (value.isJsonPrimitive) value.asJsonPrimitive.asString else null
}

private fun JsonObject.booleanOrFalse(key: String): Boolean =
    get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.asBoolean ?: false

private val RELAY_PATTERN = Regex("^(tcp|udp|ws|wss|quic|ring|faketcp|https?)://\\S+$")

/** 解析 EasyTier HTTP 节点列表的纯文本响应：每行一个 `tcp://host:port`。 */
internal fun parseRelayUrls(text: String): List<String> =
    text
        .lineSequence()
        .map(String::trim)
        .filter { line -> RELAY_PATTERN.matches(line) && !line.startsWith("http") }
        .distinct()
        .toList()

/**
 * 一次会话内的中继列表：自定义优先，其次是官方活动节点接口，再是社区节点列表。
 *
 * `uptime.easytier.cn` 已关停，因此：
 * - 接口失败只打一条 INFO 日志，不再每次建房都报错；
 * - 社区 HTTP 列表会由启动器解析成具体节点；解析失败时退化为把列表地址交给 EasyTier；
 * - 全部失败时使用 [EasyTierRelays.DEFAULT]（官方固定节点）。
 *
 * 成功与失败结果都会缓存：成功 10 分钟，失败 2 分钟后再重试。
 */
class EasyTierRelayDirectory(
    private val fetcher: RelayListFetcher = HttpRelayListFetcher(),
    private val logger: (message: String, error: Boolean) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
    private val cacheValidityMillis: Long = DEFAULT_CACHE_VALIDITY_MILLIS,
    private val maxDynamicRelays: Int = DEFAULT_MAX_DYNAMIC_RELAYS,
) {
    private val lock = Mutex()
    private var cached: List<String>? = null
    private var cachedUntil = 0L
    private var jsonFailureReported = false
    private var communityFailureReported = false

    /** 本次房间要追加到参数里的中继：自定义 + 实时/社区节点，去重后限制条数。 */
    suspend fun resolve(customRelays: List<String>): List<String> =
        (customRelays.map(String::trim).filter(String::isNotEmpty) + liveRelays())
            .distinct()
            .take(maxDynamicRelays)

    /** 后台预热缓存；失败只写日志，不影响后续房间。 */
    suspend fun prefetch() {
        liveRelays()
    }

    private suspend fun liveRelays(): List<String> =
        lock.withLock {
            val now = clock()
            val current = cached
            if (current != null && now < cachedUntil) return current
            val apiNodes = fetchActiveNodes()
            val communityNodes = fetchCommunityNodes()
            val combined = (apiNodes + communityNodes).distinct()
            val result = combined.ifEmpty { EasyTierRelays.HTTP_NODE_LISTS }
            cached = result
            cachedUntil =
                now + if (combined.isEmpty()) FAILURE_RETRY_MILLIS else cacheValidityMillis
            result
        }

    private suspend fun fetchActiveNodes(): List<String> {
        val fetched = fetcher.fetch(EasyTierRelays.NODE_LIST_URL)
        val nodes =
            fetched.fold(
                onSuccess = { body ->
                    parseActiveRelays(body).getOrElse { failure ->
                        reportJsonFailure(failure)
                        emptyList()
                    }
                },
                onFailure = { failure ->
                    reportJsonFailure(failure)
                    emptyList()
                },
            )
        if (nodes.isNotEmpty()) jsonFailureReported = false
        return nodes
    }

    private suspend fun fetchCommunityNodes(): List<String> =
        EasyTierRelays.HTTP_NODE_LISTS
            .flatMap { url ->
                fetcher
                    .fetch(url)
                    .getOrNull()
                    ?.let(::parseRelayUrls)
                    .orEmpty()
            }.distinct()
            .also { nodes ->
                if (nodes.isEmpty() && !communityFailureReported) {
                    communityFailureReported = true
                    logger("社区节点列表暂未返回可用节点，将把列表地址交给 EasyTier 自行解析", false)
                }
            }

    private fun reportJsonFailure(failure: Throwable) {
        if (jsonFailureReported) return
        jsonFailureReported = true
        logger("EasyTier 官方节点列表接口不可用（${describeFailure(failure)}），改用社区节点列表", false)
    }

    /** 常见网络异常给出人话说明，避免日志里只剩一个域名。 */
    private fun describeFailure(failure: Throwable): String =
        when (failure) {
            is UnknownHostException -> "域名无法解析：${failure.message}"
            is SocketTimeoutException -> "连接超时"
            is ConnectException -> "无法连接服务器"
            else -> failure.message ?: failure::class.simpleName ?: "未知错误"
        }

    private companion object {
        const val DEFAULT_CACHE_VALIDITY_MILLIS = 10 * 60 * 1_000L
        const val FAILURE_RETRY_MILLIS = 2 * 60 * 1_000L
        const val DEFAULT_MAX_DYNAMIC_RELAYS = 8
    }
}
