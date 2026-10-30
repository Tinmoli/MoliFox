// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 在 HTTP 应用拦截器中返回固定接口数据，所有请求都不会进入真实网络链。

package dev.molifox.desktop.checks

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.HttpURLConnection.HTTP_NOT_FOUND
import java.net.HttpURLConnection.HTTP_OK
import java.util.concurrent.CopyOnWriteArrayList

internal class FixtureNetwork : Interceptor {
    val requested = CopyOnWriteArrayList<String>()
    val unexpected = CopyOnWriteArrayList<String>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        requested += request.url.toString()
        val path = request.url.encodedPath
        val body =
            when {
                path.endsWith("version_manifest_v2.json") -> manifest
                path.endsWith(
                    "/tag/game_version",
                ) -> """[{"version":"1.20.1","version_type":"release"}]"""
                path.endsWith(
                    "/tag/loader",
                ) -> """[{"name":"fabric","supported_project_types":["mod"]}]"""
                path.endsWith("/search") -> """{"hits":[],"offset":0,"limit":20,"total_hits":0}"""
                path.contains("/v2/versions/") -> "[]"
                path.endsWith(
                    "maven-metadata.xml",
                ) -> "<metadata><versioning><versions/></versioning></metadata>"
                path.endsWith("/categories") -> """{"data":[]}"""
                path.endsWith("/releases/latest") -> """{"message":"Not Found"}"""
                else -> {
                    unexpected += request.url.toString()
                    """{"error":"unhandled fixture request"}"""
                }
            }
        return Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(if (path.endsWith("/releases/latest")) HTTP_NOT_FOUND else HTTP_OK)
            .message("UI fixture")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }

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
}
