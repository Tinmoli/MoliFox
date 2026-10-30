// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 在 HTTP 应用拦截器中返回固定接口数据与夹具图片，所有请求都不会进入真实网络链。

package dev.molifox.desktop.checks

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.HttpURLConnection.HTTP_NOT_FOUND
import java.net.HttpURLConnection.HTTP_OK
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

internal class FixtureNetwork : Interceptor {
    val requested = CopyOnWriteArrayList<String>()
    val unexpected = CopyOnWriteArrayList<String>()

    /** 夹具提供的静态资源（光影头像等），按完整 URL 精确命中，不触发真实下载。 */
    private val served = ConcurrentHashMap<String, ByteArray>()

    fun serve(
        url: String,
        bytes: ByteArray,
    ) {
        served[url] = bytes
    }

    fun requestCount(url: String): Int = requested.count { it == url }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        requested += url
        val (code, bytes, type) = resolve(request, url)
        return response(request, code, bytes, type)
    }

    private fun resolve(
        request: Request,
        url: String,
    ): Triple<Int, ByteArray, MediaType> {
        val cached = served[url]
        val (code, body) =
            when {
                cached != null -> return Triple(HTTP_OK, cached, IMAGE_PNG)
                // CurseForge 镜像的更新检查走公共镜像；夹具只回空数据，让本地内容保持「无法确认」占位。
                request.url.host.endsWith(MIRROR_HOST) -> HTTP_OK to EMPTY_DATA
                else -> route(request.url.encodedPath, url)
            }
        return Triple(code, body.toByteArray(), APPLICATION_JSON)
    }

    private fun route(
        path: String,
        url: String,
    ): Pair<Int, String> =
        when {
            // 未登记的光影包按摘要识别：夹具明确返回未收录，验证占位路径而不是伪造项目。
            path.contains("/v2/version_file/") -> HTTP_NOT_FOUND to NOT_FOUND
            path.endsWith("/version_files") ||
                path.endsWith("/version_files/update") -> HTTP_OK to "{}"
            path.endsWith("version_manifest_v2.json") -> HTTP_OK to manifest
            path.endsWith("/tag/game_version") -> HTTP_OK to GAME_VERSIONS
            path.endsWith("/tag/loader") -> HTTP_OK to LOADERS
            path.endsWith("/search") -> HTTP_OK to SEARCH
            path.contains("/v2/versions/") -> HTTP_OK to "[]"
            path.endsWith("maven-metadata.xml") -> HTTP_OK to MAVEN_METADATA
            path.endsWith("/categories") -> HTTP_OK to EMPTY_DATA
            path.endsWith("/releases/latest") -> HTTP_NOT_FOUND to NOT_FOUND_RELEASE
            else -> {
                unexpected += url
                HTTP_OK to """{"error":"unhandled fixture request"}"""
            }
        }

    private fun response(
        request: Request,
        code: Int,
        bytes: ByteArray,
        type: MediaType,
    ): Response =
        Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("UI fixture")
            .body(bytes.toResponseBody(type))
            .build()

    private val manifest =
        """
        {
          "latest":{"release":"1.20.1","snapshot":"24w14a"},
          "versions":[
            {"id":"1.20.1","type":"release","url":"https://fixture.invalid/1.20.1.json",
             "time":"2023-06-12T13:25:51Z","releaseTime":"2023-06-12T13:25:51Z"},
            {"id":"24w14a","type":"snapshot","url":"https://fixture.invalid/24w14a.json",
             "time":"2024-04-03T12:00:00Z","releaseTime":"2024-04-03T12:00:00Z"}
          ]
        }
        """.trimIndent()

    private companion object {
        val IMAGE_PNG = "image/png".toMediaType()
        val APPLICATION_JSON = "application/json".toMediaType()
        const val MIRROR_HOST = "mcimirror.top"
        const val EMPTY_DATA = """{"data":[]}"""
        const val NOT_FOUND = """{"error":"not_found"}"""
        const val NOT_FOUND_RELEASE = """{"message":"Not Found"}"""
        const val GAME_VERSIONS = """[{"version":"1.20.1","version_type":"release"}]"""
        const val LOADERS = """[{"name":"fabric","supported_project_types":["mod"]}]"""
        const val SEARCH = """{"hits":[],"offset":0,"limit":20,"total_hits":0}"""
        const val MAVEN_METADATA = "<metadata><versioning><versions/></versioning></metadata>"
    }
}
