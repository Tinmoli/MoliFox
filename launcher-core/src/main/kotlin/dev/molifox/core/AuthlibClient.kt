// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责外置认证服务器的元数据和 Yggdrasil 认证请求。

package dev.molifox.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

private const val MAX_SERVER_URL_LENGTH = 2048
private const val MIN_PRINTABLE_CHAR_CODE = 0x20
private const val HTTP_MIN_SUCCESS = 200
private const val HTTP_MAX_SUCCESS = 299
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404

/** 外置认证（authlib-injector / Yggdrasil）失败类别；界面据此区分提示，不必解析文案。 */
enum class AuthlibError {
    /** 地址格式无效或服务器不提供 Yggdrasil 接口。 */
    InvalidServer,

    /** 地址可访问，但返回的不是 Yggdrasil 元数据。 */
    NotYggdrasil,

    /** 超时、断网或无法建立连接。 */
    Network,

    /** 用户名或密码错误。 */
    InvalidCredentials,

    /** 令牌已失效，需要用户重新输入密码。 */
    SessionExpired,

    /** 账户在该服务器上没有可用角色。 */
    NoProfile,

    /** 服务器返回其它错误状态码。 */
    ServerError,

    /** 服务器返回的 JSON 无法解析或缺少必要字段。 */
    InvalidResponse,

    /** authlib-injector 下载失败。 */
    DownloadFailed,
}

/** 带类别与中文可操作提示的外置认证异常；不会把原始异常文本直接抛给界面。 */
class AuthlibException(
    val error: AuthlibError,
    message: String,
) : Exception(message)

data class AuthlibProfile(
    val id: String,
    val name: String,
)

data class AuthlibSession(
    val server: String,
    val accessToken: String,
    val clientToken: String,
    val username: String,
    val selectedProfile: AuthlibProfile?,
    val availableProfiles: List<AuthlibProfile> = emptyList(),
)

/** 服务器元数据（Yggdrasil API 根地址的 JSON）；[rawJson] 用于 authlib-injector 预取参数。 */
data class AuthlibMetadata(
    val apiRoot: String,
    val name: String,
    val rawJson: String,
)

/** 规范化用户输入的认证服务器地址：补 https://、去首尾空白与末尾 `/`。 只接受 http(s) 且不含账号密码、查询串或片段的地址；无法识别时返回 null。 */
fun normalizeAuthlibServerUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed.length > MAX_SERVER_URL_LENGTH) return null
    if (trimmed.any { it.isWhitespace() || it.code < MIN_PRINTABLE_CHAR_CODE }) return null
    val candidate = if ("://" in trimmed) trimmed else "https://$trimmed"
    val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "https" && scheme != "http") return null
    if (uri.host.isNullOrBlank() || uri.userInfo != null) return null
    if (uri.query != null || uri.fragment != null) return null
    return candidate.trimEnd('/').ifBlank { null }
}

/**
 * Yggdrasil 客户端。
 *
 * 只负责协议与错误归类：认证服务器由用户自行填写（LittleSkin 等）， 因此请求直接使用 [NetworkClient] 的代理配置，但允许 http(s) 地址—— 启动器其它接口仍强制
 * HTTPS。选择 http 会让密码与令牌明文传输，由用户自行承担。
 */
class AuthlibClient(
    private val network: NetworkClient,
) {
    private val http: OkHttpClient by lazy {
        network.client
            .newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 读取并校验服务器元数据。先请求用户填写的地址，失败后再尝试 `<地址>/api/yggdrasil`， 兼容只填写站点根地址（如 https://littleskin.cn）的情况。
     */
    suspend fun metadata(serverUrl: String): AuthlibMetadata {
        val base = normalizeAuthlibServerUrl(serverUrl) ?: throw invalidServer()
        val candidates =
            buildList {
                add(base)
                if (!base.endsWith("/api/yggdrasil")) add("$base/api/yggdrasil")
            }
        var lastError: AuthlibException = invalidServer()
        for (candidate in candidates) {
            val found = fetchMetadata(candidate) { lastError = it }
            if (found != null) return found
        }
        throw lastError
    }

    /** 读取一个候选地址的元数据；可继续尝试的失败交给 [onFailure]，其余异常原样抛出。 */
    private suspend fun fetchMetadata(
        candidate: String,
        onFailure: (AuthlibException) -> Unit,
    ): AuthlibMetadata? {
        val result =
            try {
                request(candidate, null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: AuthlibException) {
                if (error.error == AuthlibError.Network) {
                    onFailure(error)
                    return null
                }
                throw error
            }
        return evaluateMetadataResponse(candidate, result, onFailure)
    }

    /** 按 HTTP 状态归类元数据响应：可继续尝试的失败交给 [onFailure] 并返回 null。 */
    private fun evaluateMetadataResponse(
        candidate: String,
        result: HttpResult,
        onFailure: (AuthlibException) -> Unit,
    ): AuthlibMetadata? =
        when {
            result.code == HTTP_UNAUTHORIZED ||
                result.code == HTTP_FORBIDDEN ||
                result.code == HTTP_NOT_FOUND -> {
                onFailure(invalidServer())
                null
            }
            result.code !in HTTP_MIN_SUCCESS..HTTP_MAX_SUCCESS -> {
                onFailure(
                    AuthlibException(
                        AuthlibError.ServerError,
                        "认证服务器返回错误 (${result.code})",
                    ),
                )
                null
            }
            else -> {
                val text = result.body.orEmpty()
                val json = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
                if (json == null || !isYggdrasilMetadata(json)) {
                    onFailure(notYggdrasil())
                    null
                } else {
                    AuthlibMetadata(candidate, metadataName(json, candidate), text)
                }
            }
        }

    /** 用户名密码登录；[server] 应为已规范化的 API 根地址。 */
    suspend fun authenticate(
        server: String,
        username: String,
        password: String,
    ): AuthlibSession {
        val root = normalizeAuthlibServerUrl(server) ?: throw invalidServer()
        val clientToken = UUID.randomUUID().toString()
        val body =
            JsonObject().apply {
                add(
                    "agent",
                    JsonObject().apply {
                        addProperty("name", "Minecraft")
                        addProperty("version", 1)
                    },
                )
                addProperty("username", username)
                addProperty("password", password)
                addProperty("clientToken", clientToken)
                addProperty("requestUser", true)
            }
        val json =
            authJson(
                "$root/authserver/authenticate",
                body,
                AuthlibError.InvalidCredentials,
                "用户名或密码错误",
            )
        return session(root, json, username, clientToken)
    }

    /** 用 refreshToken（Yggdrasil 中即 accessToken）续期。 */
    suspend fun refresh(
        server: String,
        accessToken: String,
        clientToken: String,
    ): AuthlibSession {
        val root = normalizeAuthlibServerUrl(server) ?: throw invalidServer()
        val body =
            JsonObject().apply {
                addProperty("accessToken", accessToken)
                addProperty("clientToken", clientToken)
                addProperty("requestUser", true)
            }
        val json =
            authJson(
                "$root/authserver/refresh",
                body,
                AuthlibError.SessionExpired,
                "外置账户登录已过期，请重新登录",
            )
        return session(root, json, "", clientToken)
    }

    /** 令牌是否仍有效；403 返回 false，网络错误继续抛出，避免把断网误判成需要重新登录。 */
    suspend fun validate(
        server: String,
        accessToken: String,
        clientToken: String,
    ): Boolean {
        val body =
            JsonObject().apply {
                addProperty("accessToken", accessToken)
                addProperty("clientToken", clientToken)
            }
        return request("$server/authserver/validate", body)
            .code in HTTP_MIN_SUCCESS..HTTP_MAX_SUCCESS
    }

    private suspend fun authJson(
        url: String,
        body: JsonObject,
        deniedError: AuthlibError,
        deniedMessage: String,
    ): JsonObject {
        val result = request(url, body)
        if (result.code == HTTP_UNAUTHORIZED || result.code == HTTP_FORBIDDEN) {
            throw AuthlibException(deniedError, deniedMessage)
        }
        if (result.code !in HTTP_MIN_SUCCESS..HTTP_MAX_SUCCESS) {
            throw AuthlibException(
                AuthlibError.ServerError,
                "认证服务器请求失败 (${result.code})",
            )
        }
        return parseAuthJson(result.body.orEmpty())
    }

    private fun parseAuthJson(text: String): JsonObject =
        runCatching { JsonParser.parseString(text).asJsonObject }
            .getOrNull() ?: throw AuthlibException(AuthlibError.InvalidResponse, "认证服务器返回的数据无效")

    private fun session(
        server: String,
        json: JsonObject,
        fallbackUsername: String,
        fallbackClientToken: String,
    ): AuthlibSession {
        val accessToken = json.string("accessToken").trim()
        if (accessToken.isBlank()) {
            throw AuthlibException(AuthlibError.InvalidResponse, "认证服务器返回的数据无效")
        }
        val clientToken = json.string("clientToken").trim().ifBlank { fallbackClientToken }
        val user = json.get("user")?.takeIf { it.isJsonObject }?.asJsonObject
        val username =
            user
                ?.string("username")
                ?.trim()
                .orEmpty()
                .ifBlank { fallbackUsername }
        val profilesJson = json.get("availableProfiles")?.takeIf { it.isJsonArray }?.asJsonArray
        val available =
            profilesJson
                ?.mapNotNull { element ->
                    element.takeIf { it.isJsonObject }?.asJsonObject?.let(::profile)
                }.orEmpty()
        val selected =
            json
                .get("selectedProfile")
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.let(::profile)
        return AuthlibSession(server, accessToken, clientToken, username, selected, available)
    }

    private fun profile(value: JsonObject): AuthlibProfile? {
        val id = value.string("id").replace("-", "").trim()
        val name = value.string("name").trim()
        if (!Regex("[0-9a-fA-F]{32}").matches(id) || name.isBlank()) return null
        return AuthlibProfile(id.lowercase(), name)
    }

    private data class HttpResult(
        val code: Int,
        val body: String?,
    )

    private suspend fun request(
        url: String,
        body: JsonObject?,
    ): HttpResult {
        val request =
            try {
                Request
                    .Builder()
                    .url(url)
                    .header(
                        "User-Agent",
                        "MoliFox/$LAUNCHER_VERSION (Minecraft Java Edition launcher)",
                    ).header("Accept", "application/json")
                    .apply {
                        if (body == null) {
                            get()
                        } else {
                            post(body.toString().toRequestBody(jsonMediaType))
                        }
                    }.build()
            } catch (_: IllegalArgumentException) {
                throw invalidServer()
            }
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        if (continuation.isActive) {
                            continuation.resumeWith(Result.failure(connectionError()))
                        }
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        val result =
                            try {
                                response.use {
                                    val bytes =
                                        it.body?.byteStream()?.readNBytes(MAX_RESPONSE_BYTES + 1)
                                            ?: ByteArray(0)
                                    if (bytes.size > MAX_RESPONSE_BYTES) {
                                        throw AuthlibException(
                                            AuthlibError.InvalidResponse,
                                            "认证服务器返回的数据无效",
                                        )
                                    }
                                    HttpResult(it.code, bytes.toString(Charsets.UTF_8))
                                }
                            } catch (error: AuthlibException) {
                                if (continuation.isActive) {
                                    continuation.resumeWith(Result.failure(error))
                                }
                                return
                            } catch (_: IOException) {
                                if (continuation.isActive) {
                                    continuation.resumeWith(Result.failure(connectionError()))
                                }
                                return
                            }
                        if (continuation.isActive) continuation.resume(result)
                    }
                },
            )
        }
    }

    private companion object {
        val jsonMediaType = "application/json".toMediaType()
        const val MAX_RESPONSE_BYTES = 1024 * 1024

        fun invalidServer() = AuthlibException(AuthlibError.InvalidServer, "不是有效的认证服务器地址")

        fun notYggdrasil() = AuthlibException(AuthlibError.NotYggdrasil, "该地址返回的不是 Yggdrasil 元数据")

        fun connectionError() = AuthlibException(AuthlibError.Network, "无法连接认证服务器（超时或网络错误）")
    }
}

private fun isYggdrasilMetadata(json: JsonObject): Boolean {
    val meta = json.get("meta")
    return (meta != null && meta.isJsonObject) ||
        json.has("signaturePublickey") ||
        json.has("skinDomains")
}

private fun metadataName(
    json: JsonObject,
    candidate: String,
): String {
    val minecraft = json.get("meta")?.takeIf { it.isJsonObject }?.asJsonObject
    val serverName =
        minecraft
            ?.get("serverName")
            ?.takeUnless { it.isJsonNull }
            ?.asString
            ?.trim()
            .orEmpty()
    return serverName.ifBlank {
        runCatching { URI(candidate).host }.getOrNull().orEmpty().ifBlank { candidate }
    }
}
