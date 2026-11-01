// 更新清单按传输测速选源，所有候选失败时保留原始原因，不将失败解释为没有更新。
package dev.molifox.core

import com.google.gson.JsonArray
import com.google.gson.JsonParseException
import dev.molifox.core.i18n.I18n
import java.io.IOException
import java.net.URI

internal suspend fun NetworkClient.gitHubReleaseList(url: String): JsonArray {
    val candidates = gitHubDownloadCandidates(url)
    val ordered = if (candidates.size > 1) rankDownloadSources(candidates) else candidates
    var lastFailure: Exception? = null
    for (candidate in ordered) {
        try {
            val releaseJson =
                if (URI(candidate).host in setOf("api.github.com", "127.0.0.1")) {
                    githubJson(candidate)
                } else {
                    json(candidate, sourceOverride = "official")
                }
            return releaseJson.asJsonArray
        } catch (failure: IOException) {
            lastFailure = failure
        } catch (failure: JsonParseException) {
            lastFailure = failure
        } catch (failure: IllegalStateException) {
            lastFailure = failure
        }
    }
    throw IOException(I18n.t("networkB1.gitHubReleaseSources.listUnreadable", lastFailure?.message), lastFailure)
}
