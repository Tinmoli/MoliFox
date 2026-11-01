// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责各服务端点的请求：文本、JSON、图片与认证写请求。

package dev.molifox.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI

/** 各类响应体上限：百科页、缩略图与皮肤纹理。 */
private const val MAX_ENCYCLOPEDIA_BYTES = 2 * 1024 * 1024
private const val MAX_THUMBNAIL_BYTES = 2 * 1024 * 1024
private const val MAX_SKIN_TEXTURE_BYTES = 1024 * 1024
private const val MAX_SKIN_UPLOAD_BYTES = 1024 * 1024

/** GitHub 把 403 与 429 都当作限流。 */
private const val HTTP_FORBIDDEN = 403

/** 元数据端点返回非 2xx：保留状态码，供调用方区分“该版本不支持”和真正的网络故障。 */
internal class HttpStatusException(
    val statusCode: Int,
    val host: String,
) : IOException(I18n.t("core.networkEndpointActions.1", statusCode, host))

/** 只读端点：文本与 JSON。 */
internal interface NetworkTextApi {
    suspend fun text(
        url: String,
        sourceOverride: String? = null,
    ): String

    suspend fun json(
        url: String,
        sourceOverride: String? = null,
    ): JsonElement
}

/** 带体积上限或凭据的端点：图片、表单与认证写请求。 */
internal interface NetworkPayloadApi {
    suspend fun thumbnail(url: String): ByteArray

    suspend fun skinTexture(url: String): ByteArray

    suspend fun post(
        url: String,
        body: JsonObject,
        bearer: String? = null,
    ): JsonObject

    suspend fun authorizedJson(
        url: String,
        token: String,
    ): JsonObject

    suspend fun form(
        url: String,
        fields: Map<String, String>,
    ): JsonObject
}

/** 文本与 JSON 请求：按候选源顺序依次尝试，全部失败时抛最后一次错误。 */
internal abstract class NetworkTextActions(
    settings: NetworkClientSettings,
) : NetworkSourceActions(settings),
    NetworkTextApi {
    override suspend fun text(
        url: String,
        sourceOverride: String?,
    ): String =
        withContext(Dispatchers.IO) {
            var lastError: Exception? = null
            for (candidate in orderedCandidates(url, sourceOverride ?: settings.source())) {
                try {
                    execute(builder(candidate).build()).use { response ->
                        if (!response.isSuccessful) {
                            throw HttpStatusException(response.code, URI(url).host)
                        }
                        return@withContext response.body?.string() ?: error(I18n.t("core.networkEndpointActions.2"))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: IOException) {
                    lastError = error
                } catch (error: IllegalStateException) {
                    lastError = error
                } catch (error: IllegalArgumentException) {
                    lastError = error
                }
            }
            throw IOException(I18n.t("core.networkEndpointActions.3", URI(url).host, lastError?.message), lastError)
        }

    override suspend fun json(
        url: String,
        sourceOverride: String?,
    ): JsonElement = JsonParser.parseString(text(url, sourceOverride))

    suspend fun githubJson(url: String): JsonElement =
        withContext(Dispatchers.IO) {
            val uri = URI(url)
            require(
                (uri.scheme == "https" && uri.host == "api.github.com") ||
                    (uri.scheme == "http" && uri.host == "127.0.0.1"),
            ) {
                I18n.t("core.networkEndpointActions.4")
            }
            execute(builder(url).header("Accept", "application/vnd.github+json").build())
                .use { response ->
                    check(response.isSuccessful) {
                        when (response.code) {
                            NET_HTTP_NOT_FOUND -> I18n.t("core.networkEndpointActions.5")
                            HTTP_FORBIDDEN,
                            NET_HTTP_TOO_MANY_REQUESTS,
                            -> I18n.t("core.networkEndpointActions.6")
                            else -> I18n.t("core.networkEndpointActions.7", response.code)
                        }
                    }
                    JsonParser.parseString(response.body?.string() ?: error(I18n.t("core.networkEndpointActions.8")))
                }
        }

    suspend fun encyclopediaText(url: String): String =
        withContext(Dispatchers.IO) {
            val uri = URI(url)
            require(
                uri.scheme == "https" &&
                    uri.host in setOf("www.mcmod.cn", "search.mcmod.cn") &&
                    uri.userInfo == null,
            )
            execute(builder(url).build(), attempts = 1).readCancellable { response ->
                check(response.isSuccessful) { I18n.t("core.networkEndpointActions.9") }
                val body = response.body ?: error(I18n.t("core.networkEndpointActions.10"))
                require(body.contentLength() <= MAX_ENCYCLOPEDIA_BYTES)
                val bytes = body.byteStream().readNBytes(MAX_ENCYCLOPEDIA_BYTES + 1)
                require(bytes.size <= MAX_ENCYCLOPEDIA_BYTES)
                bytes.toString(Charsets.UTF_8)
            }
        }
}

/** 图片、表单与认证写请求；每个端点自己校验允许的域名与体积上限。 */
internal abstract class NetworkPayloadActions(
    settings: NetworkClientSettings,
) : NetworkTextActions(settings),
    NetworkPayloadApi {
    /** CurseForge 公共镜像与自建代理都不需要凭据，因此请求不携带任何认证头。 */
    suspend fun curseForgeJson(
        url: String,
        body: JsonObject? = null,
    ): JsonObject =
        withContext(Dispatchers.IO) {
            requireCurseForgeUrl(url)
            val request =
                builder(url, allowInsecureHttp = true).header("Accept", "application/json")
            body?.let {
                request.post(it.toString().toRequestBody("application/json".toMediaType()))
            }
            try {
                execute(request.build(), redirects = false).use { response ->
                    readCurseForgeJson(response)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                throw IOException(I18n.t("core.networkEndpointActions.11"))
            }
        }

    /** Resource icons are small, public CDN images; never forward account credentials. */
    override suspend fun thumbnail(url: String): ByteArray =
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
                require(body.contentLength() <= MAX_THUMBNAIL_BYTES) { "Thumbnail too large" }
                val bytes = body.byteStream().readNBytes(MAX_THUMBNAIL_BYTES + 1)
                require(bytes.size <= MAX_THUMBNAIL_BYTES) { "Thumbnail too large" }
                bytes
            }
        }

    /** 玩家皮肤纹理只允许来自 Mojang 官方 CDN，限制大小， 不携带任何账户凭据，也不经过镜像重写。 */
    override suspend fun skinTexture(url: String): ByteArray =
        withContext(Dispatchers.IO) {
            val normalized = normalizeSkinTextureUrl(url)
            execute(builder(normalized).build(), redirects = false).use { response ->
                check(response.isSuccessful) { I18n.t("core.networkEndpointActions.12", response.code) }
                val body = response.body ?: error(I18n.t("core.networkEndpointActions.13"))
                require(body.contentLength() <= MAX_SKIN_TEXTURE_BYTES) { I18n.t("core.networkEndpointActions.14") }
                val bytes = body.byteStream().readNBytes(MAX_SKIN_TEXTURE_BYTES + 1)
                require(bytes.size <= MAX_SKIN_TEXTURE_BYTES) { I18n.t("core.networkEndpointActions.14") }
                bytes
            }
        }

    override suspend fun post(
        url: String,
        body: JsonObject,
        bearer: String?,
    ): JsonObject =
        withContext(Dispatchers.IO) {
            val request =
                builder(url).post(body.toString().toRequestBody("application/json".toMediaType()))
            bearer?.let { request.header("Authorization", "Bearer $it") }
            execute(request.build()).readCancellable { response ->
                if (!response.isSuccessful) throw ServiceHttpException(response.code, URI(url).host)
                val responseBody = requireNotNull(response.body) { I18n.t("core.networkEndpointActions.15") }
                JsonParser.parseString(responseBody.string()).asJsonObject
            }
        }

    /**
     * Token-bearing skin writes are restricted to the official endpoint (or an explicit loopback
     * fixture).
     */
    suspend fun uploadMinecraftSkin(
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
            I18n.t("core.networkEndpointActions.16")
        }
        require(variant in setOf("classic", "slim") && png.size in 1..MAX_SKIN_UPLOAD_BYTES) {
            I18n.t("core.networkEndpointActions.17")
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

    override suspend fun authorizedJson(
        url: String,
        token: String,
    ): JsonObject =
        withContext(Dispatchers.IO) {
            execute(builder(url).header("Authorization", "Bearer $token").build()).use { response ->
                if (response.code == NET_HTTP_NOT_FOUND) {
                    error(I18n.t("core.networkEndpointActions.18"))
                }
                check(response.isSuccessful) { I18n.t("core.networkEndpointActions.19", response.code) }
                val responseBody = requireNotNull(response.body) { I18n.t("core.networkEndpointActions.15") }
                JsonParser.parseString(responseBody.string()).asJsonObject
            }
        }

    override suspend fun form(
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
                val responseBody = requireNotNull(response.body) { I18n.t("core.networkEndpointActions.15") }
                val result = JsonParser.parseString(responseBody.string()).asJsonObject
                if (!response.isSuccessful && !result.has("error")) {
                    error(I18n.t("core.networkEndpointActions.20", response.code))
                }
                result
            }
        }
}
