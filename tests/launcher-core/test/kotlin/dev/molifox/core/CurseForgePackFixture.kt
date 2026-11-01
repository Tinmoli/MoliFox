// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责为 CurseForge 安装测试提供可控的假服务端响应。

package dev.molifox.core

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer

/**
 * CurseForge 安装测试用的假服务端：Mojang 侧文件与 CurseForge 查询都由它回答。
 * [failDownload] 控制客户端 JAR 是否 404，用来制造一次失败再修复。
 */
internal class CurseForgePackFixture(
    private val base: String,
    private val requests: MutableList<RecordedRequest>,
    private val client: ByteArray,
    private val resource: ByteArray,
) {
    var failDownload = true

    private val profile: String = buildProfile()

    /** 让 [server] 用这套固定响应。 */
    fun serve(server: MockWebServer) {
        server.dispatcher = FixtureDispatcher()
    }

    /** 指向本机 MockWebServer 的客户端 JAR 与资源索引。 */
    private fun buildProfile(): String =
        gson.toJson(
            mapOf(
                "id" to "1.20.1",
                "type" to "release",
                "mainClass" to "fixture.Main",
                "libraries" to emptyList<String>(),
                "downloads" to
                    mapOf(
                        "client" to
                            mapOf(
                                "url" to "$base/client.jar",
                                "sha1" to packSha1(client),
                                "size" to client.size,
                            ),
                    ),
                "assetIndex" to mapOf("id" to "fixture", "url" to "$base/assets.json"),
            ),
        )

    private inner class FixtureDispatcher : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request
            val path = checkNotNull(request.requestUrl).encodedPath
            return coreResponse(path) ?: apiResponse(path)
        }

        /** 启动器自己取的文件：manifest、profile、assets、客户端与资源包。 */
        private fun coreResponse(path: String): MockResponse? =
            when (path) {
                "/manifest.json" ->
                    MockResponse()
                        .setBody(
                            gson.toJson(
                                mapOf(
                                    "versions" to
                                        listOf(
                                            mapOf(
                                                "id" to "1.20.1",
                                                "type" to "release",
                                                "url" to "$base/profile.json",
                                                "sha1" to
                                                    packSha1(profile.toByteArray()),
                                            ),
                                        ),
                                ),
                            ),
                        )
                "/profile.json" -> MockResponse().setBody(profile)
                "/assets.json" -> MockResponse().setBody("{\"objects\":{}}")
                "/client.jar" ->
                    if (failDownload) {
                        MockResponse().setResponseCode(404)
                    } else {
                        MockResponse().setBody(Buffer().write(client))
                    }
                "/resource.zip" ->
                    MockResponse().setBody(Buffer().write(resource))
                else -> null
            }

        /** CurseForge 查询：模组、文件与分类。 */
        private fun apiResponse(path: String): MockResponse =
            when (path) {
                "/v1/mods" -> data(modsPayload())
                "/v1/mods/files" -> data(filesPayload())
                "/v1/categories" -> data(categoriesPayload())
                else -> MockResponse().setResponseCode(404)
            }

        /** 允许分发的模组条目，供文件列表引用。 */
        private fun modsPayload(): List<Any> =
            listOf(
                mapOf(
                    "id" to 100,
                    "gameId" to 432,
                    "classId" to 12,
                    "slug" to "fixture",
                    "allowModDistribution" to true,
                ),
            )

        /** 单个可下载文件：大小与 SHA-1 与资源包内容一致。 */
        private fun filesPayload(): List<Any> =
            listOf(
                mapOf(
                    "id" to 200,
                    "modId" to 100,
                    "gameId" to 432,
                    "isAvailable" to true,
                    "isServerPack" to false,
                    "displayName" to "Resource",
                    "fileName" to "resource.zip",
                    "releaseType" to 1,
                    "fileDate" to "2025-01-01T00:00:00Z",
                    "fileLength" to resource.size,
                    "hashes" to
                        listOf(
                            mapOf(
                                "algo" to 1,
                                "value" to packSha1(resource),
                            ),
                        ),
                    "downloadUrl" to "$base/resource.zip",
                    "gameVersions" to listOf("1.20.1"),
                    "dependencies" to emptyList<String>(),
                ),
            )

        /** 资源包分类。 */
        private fun categoriesPayload(): List<Any> =
            listOf(
                mapOf(
                    "id" to 12,
                    "gameId" to 432,
                    "isClass" to true,
                    "slug" to "texture-packs",
                ),
            )
    }

    /** 把内容包成 ContentService 期望的 `{"data": ...}` 响应。 */
    private fun data(value: Any): MockResponse = MockResponse().setBody(gson.toJson(mapOf("data" to value)))
}
