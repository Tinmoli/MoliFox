// 并行读取公开更新清单；有效新版本先返回，旧缓存或失败不阻断其它线路。
package dev.molifox.core

import com.google.gson.JsonArray
import com.google.gson.JsonParseException
import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.URI

private const val RELEASE_SOURCE_TIMEOUT_MILLIS = 10_000L
private const val RELEASE_PAGE_SIZE = 100
private const val MAX_RELEASE_PAGES = 10

private data class ReleaseListResult(
    val value: JsonArray? = null,
    val fresh: Boolean = false,
    val failure: Exception? = null,
)

internal suspend fun NetworkClient.gitHubReleaseList(
    url: String,
    options: GitHubSourceOptions = actions.settings.githubSources(),
    accept: (JsonArray) -> Boolean = { true },
): JsonArray = coroutineScope {
    val candidates = gitHubDownloadCandidates(url, options).let { listOf(url) + it.filterNot { source -> source == url } }
    val responses = Channel<ReleaseListResult>(candidates.size)
    val workers = candidates.map { candidate ->
        launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            responses.send(releaseListResult(candidate, accept))
        }
    }
    var fallback: JsonArray? = null
    var failure: Exception? = null
    try {
        repeat(candidates.size) {
            val result = responses.receive()
            val value = result.value
            if (value != null) {
                if (result.fresh) return@coroutineScope value
                if (fallback == null) fallback = value
            }
            result.failure?.let { failure = it }
        }
        fallback ?: throw IOException(
            I18n.t("networkB1.gitHubReleaseSources.listUnreadable", failure?.message), failure,
        )
    } finally {
        workers.forEach { it.cancel() }
        responses.cancel()
    }
}

private suspend fun NetworkClient.releaseListResult(url: String, accept: (JsonArray) -> Boolean): ReleaseListResult =
    try {
        val value = withTimeoutOrNull(RELEASE_SOURCE_TIMEOUT_MILLIS) { readReleasePages(url) }
        if (value == null) {
            ReleaseListResult(failure = IOException(I18n.t("settings.github.timeout")))
        } else {
            ReleaseListResult(value, accept(value))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: IOException) {
        ReleaseListResult(failure = failure)
    } catch (failure: JsonParseException) {
        ReleaseListResult(failure = failure)
    } catch (failure: IllegalStateException) {
        ReleaseListResult(failure = failure)
    } catch (failure: IllegalArgumentException) {
        ReleaseListResult(failure = failure)
    }

private suspend fun NetworkClient.readReleasePages(url: String): JsonArray {
    val all = JsonArray()
    repeat(MAX_RELEASE_PAGES) { page ->
        val address = if (page == 0) url else "$url${if ('?' in url) '&' else '?'}page=${page + 1}"
        val response = if (URI(address).host in setOf("api.github.com", "127.0.0.1")) {
            githubJson(address)
        } else {
            json(address, sourceOverride = "official")
        }.asJsonArray
        all.addAll(response)
        if (response.size() < RELEASE_PAGE_SIZE) return all
    }
    return all
}
