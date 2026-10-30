// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责下载失败的分类与 CurseForge 响应的解析。

package dev.molifox.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Response
import java.io.IOException
import java.net.URI

/** 只有 5xx 与 408 视为可重试的 HTTP 状态；403/429 由限流策略处理。 */
private const val HTTP_SERVER_ERROR_MIN = 500
private const val HTTP_STATUS_REQUEST_TIMEOUT = 408

/** CurseForge 元数据与重定向状态码。 */
internal const val NET_HTTP_TOO_MANY_REQUESTS = 429
internal const val NET_HTTP_NOT_FOUND = 404
private const val HTTP_REDIRECT_MIN = 300
private const val HTTP_REDIRECT_MAX = 399

/** CurseForge 元数据响应的体积上限。 */
private const val MAX_CURSEFORGE_RESPONSE_BYTES = 8 * 1024 * 1024

/** 可恢复失败才重试：IO 中断、超时、5xx 以及校验失败。 其它 4xx 只在还有备用镜像时继续试（保持镜像回退），单一下载源直接失败，避免反复打 404。 */
internal fun isRecoverableDownloadFailure(
    error: Throwable,
    mirrorFallback: Boolean,
): Boolean =
    when (error) {
        is DownloadVerificationException -> true
        is DownloadHttpException ->
            error.code >= HTTP_SERVER_ERROR_MIN ||
                error.code == HTTP_STATUS_REQUEST_TIMEOUT ||
                error.code == NET_HTTP_TOO_MANY_REQUESTS ||
                mirrorFallback
        is IOException -> true
        else -> false
    }

/** 只接受 http/https、必须有主机，且不允许内嵌凭据或片段。 */
internal fun requireCurseForgeUrl(url: String) {
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
}

/** 读取整段响应：非 2xx、体积过大与 JSON 损坏都在这里报错。 */
internal fun readCurseForgeJson(response: Response): JsonObject {
    if (!response.isSuccessful) throw curseForgeHttpException(response.code)
    val bytes =
        response.body?.byteStream()?.use { it.readNBytes(MAX_CURSEFORGE_RESPONSE_BYTES + 1) }
            ?: error("CurseForge 响应为空")
    require(bytes.size <= MAX_CURSEFORGE_RESPONSE_BYTES) { "CurseForge 响应过大" }
    return try {
        JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
    } catch (_: Exception) {
        error("CurseForge 响应格式无效")
    }
}

internal fun curseForgeHttpException(code: Int): CurseForgeHttpException =
    CurseForgeHttpException(
        code,
        when (code) {
            NET_HTTP_NOT_FOUND -> "CurseForge 项目或文件不存在"
            NET_HTTP_TOO_MANY_REQUESTS -> "CurseForge 请求过于频繁，请稍后重试"
            in HTTP_REDIRECT_MIN..HTTP_REDIRECT_MAX -> "CurseForge 服务返回重定向，已停止请求"
            else -> "CurseForge 请求失败 ($code)"
        },
    )
