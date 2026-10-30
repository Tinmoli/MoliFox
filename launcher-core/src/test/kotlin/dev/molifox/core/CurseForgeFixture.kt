// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 提供 CurseForge 测试用的假项目/文件数据与 MockWebServer 路由。

package dev.molifox.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/** CurseForge 测试的固定夹具：假实例、假服务与记录下来的请求。 */
internal class CurseForgeFixture(
    val store: LauncherStore,
    val server: MockWebServer,
    val service: ContentService,
    val backend: CurseForgeBackend,
    val instance: GameInstance,
    val network: NetworkClient,
) {
    var mode = "normal"
    var resourcePack = false
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    fun project(id: Int): JsonObject =
        gson
            .toJsonTree(
                mapOf(
                    "id" to id,
                    "gameId" to 432,
                    "classId" to if (resourcePack) 12 else 6,
                    "name" to "Project $id",
                    "slug" to "project-$id",
                    "summary" to "Summary $id",
                    "authors" to listOf(mapOf("name" to "Author")),
                    "links" to
                        mapOf(
                            "websiteUrl" to
                                "https://www.curseforge.com/minecraft/mc-mods/project-$id",
                        ),
                    "downloadCount" to 100,
                    "allowModDistribution" to (mode != "blocked" || id !in setOf(2, 3)),
                ),
            ).asJsonObject

    fun file(id: Int): JsonObject {
        val project = id / 10
        val bytes = "file$id".toByteArray()
        return gson
            .toJsonTree(
                mapOf(
                    "id" to id,
                    "modId" to project,
                    "gameId" to if (mode == "wrong-game") 1 else 432,
                    "isAvailable" to true,
                    "isServerPack" to (mode == "server-pack"),
                    "displayName" to "File $id",
                    "fileName" to "$id.${if (resourcePack) "zip" else "jar"}",
                    "releaseType" to if (mode == "beta" && id == 12) 2 else 1,
                    "fileDate" to fileDate(id),
                    "fileLength" to bytes.size,
                    "hashes" to
                        listOf(
                            mapOf(
                                "algo" to 1,
                                "value" to if (mode == "bad-hash") "0" else hash(bytes),
                            ),
                        ),
                    "downloadUrl" to downloadUrl(id),
                    "gameVersions" to gameVersions(),
                    "dependencies" to dependencies(id),
                ),
            ).asJsonObject
    }

    private fun fileDate(id: Int): String =
        if (id == 11) "2025-01-01T00:00:00Z" else "2025-02-01T00:00:00Z"

    private fun downloadUrl(id: Int): String? =
        if (mode == "missing-url" && id == 21) {
            null
        } else {
            server.loopbackUrl("/files/$id").toString()
        }

    private fun gameVersions(): List<String> =
        listOf(
            if (mode == "wrong-version") "1.21" else "1.20.1",
            if (mode == "wrong-loader") "Forge" else "Fabric",
        )

    private fun dependencies(id: Int): List<Map<String, Int>> =
        if (id == 12) {
            listOf(
                mapOf(
                    "modId" to 2,
                    "relationType" to if (mode == "incompatible") 5 else 3,
                ),
                mapOf("modId" to 3, "relationType" to 2),
                mapOf("modId" to 4, "relationType" to 1),
            )
        } else {
            emptyList()
        }

    companion object {
        fun hash(bytes: ByteArray) =
            MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") {
                "%02x".format(it)
            }
    }
}

/** 把数据包成 CurseForge 的 `{"data": ...}` 响应。 */
private fun curseForgeData(value: Any): MockResponse =
    MockResponse().setBody(gson.toJson(mapOf("data" to value)))

/** 按路径分派假响应；判定顺序与原 `when` 一致，靠前的路由优先。 */
internal class CurseForgeMockDispatcher(
    private val fixture: CurseForgeFixture,
    private val server: MockWebServer,
    private val profile: String,
    private val client: ByteArray,
) : Dispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse {
        fixture.requests += request
        val url = checkNotNull(request.requestUrl)
        val path = url.encodedPath
        val detailPath = path.endsWith("/description") || path.endsWith("/changelog")
        return modeResponse(path, detailPath)
            ?: searchOrDownloadResponse(path)
            ?: modBatchResponse(request, path)
            ?: minecraftResponse(path)
            ?: catalogListResponse(path)
            ?: fingerprintResponse(request, path)
            ?: detailsResponse(path)
            ?: modResponse(url, path)
    }

    /** 先处理按 `mode` 注入的故障（损坏 JSON、500、缺详情、重定向）。 */
    private fun modeResponse(
        path: String,
        detailPath: Boolean,
    ): MockResponse? {
        val apiPath = path.startsWith("/v1/")
        if (fixture.mode == "malformed-response" && apiPath) {
            return MockResponse().setBody("invalid-json response body")
        }
        if (fixture.mode == "server-error" && apiPath) return MockResponse().setResponseCode(500)
        if (fixture.mode == "no-details" && apiPath && detailPath) {
            return MockResponse().setResponseCode(404)
        }
        if (fixture.mode == "redirect" && apiPath) {
            return MockResponse()
                .setResponseCode(302)
                .addHeader("Location", server.loopbackUrl("/leak"))
        }
        return null
    }

    private fun searchOrDownloadResponse(path: String): MockResponse? =
        when {
            path == "/v2/search" ->
                MockResponse()
                    .setBody(
                        gson.toJson(
                            mapOf(
                                "hits" to
                                    listOf(
                                        mapOf(
                                            "project_id" to "modrinth-project",
                                            "slug" to "modrinth-project",
                                            "title" to "Modrinth Project",
                                            "description" to "",
                                            "author" to "Author",
                                            "downloads" to 1,
                                            "project_type" to "mod",
                                            "icon_url" to "",
                                        ),
                                    ),
                                "total_hits" to 1,
                            ),
                        ),
                    )
            path.startsWith("/files/") ->
                if (fixture.mode == "download-fail" && path.endsWith("21")) {
                    MockResponse().setResponseCode(404)
                } else {
                    MockResponse()
                        .setBody(
                            if (fixture.mode == "corrupt-download") {
                                "corrupt"
                            } else {
                                "file${path.substringAfterLast('/')}"
                            },
                        )
                }
            else -> null
        }

    private fun modBatchResponse(
        request: RecordedRequest,
        path: String,
    ): MockResponse? =
        when (path) {
            "/v1/mods" ->
                curseForgeData(
                    JsonParser
                        .parseString(request.body.clone().readUtf8())
                        .asJsonObject
                        .array("modIds")
                        .map { fixture.project(it.asInt) },
                )
            "/v1/mods/files" ->
                curseForgeData(
                    JsonParser
                        .parseString(request.body.clone().readUtf8())
                        .asJsonObject
                        .array("fileIds")
                        .map { fixture.file(it.asInt) },
                )
            else -> null
        }

    private fun minecraftResponse(path: String): MockResponse? =
        when (path) {
            "/minecraft/manifest" ->
                MockResponse()
                    .setBody(
                        gson.toJson(
                            mapOf(
                                "versions" to
                                    listOf(
                                        mapOf(
                                            "id" to "1.20.1",
                                            "type" to "release",
                                            "url" to
                                                server
                                                    .loopbackUrl("/minecraft/profile")
                                                    .toString(),
                                            "sha1" to CurseForgeFixture.hash(profile.toByteArray()),
                                        ),
                                    ),
                            ),
                        ),
                    )
            "/minecraft/profile" -> MockResponse().setBody(profile)
            "/minecraft/assets" -> MockResponse().setBody("{\"objects\":{}}")
            "/minecraft/client.jar" -> MockResponse().setBody(okio.Buffer().write(client))
            else -> null
        }

    private fun catalogListResponse(path: String): MockResponse? =
        when (path) {
            "/v1/categories" ->
                curseForgeData(
                    listOf(
                        mapOf("id" to 6, "gameId" to 432, "slug" to "mc-mods", "isClass" to true),
                        mapOf(
                            "id" to 12,
                            "gameId" to 432,
                            "slug" to "texture-packs",
                            "isClass" to true,
                        ),
                    ),
                )
            "/v1/mods/search" ->
                MockResponse()
                    .setBody(
                        gson.toJson(
                            mapOf(
                                "data" to listOf(fixture.project(1)),
                                "pagination" to mapOf("totalCount" to 24),
                            ),
                        ),
                    )
            else -> null
        }

    private fun fingerprintResponse(
        request: RecordedRequest,
        path: String,
    ): MockResponse? {
        if (path != "/v1/fingerprints/432") return null
        val body =
            JsonParser
                .parseString(request.body.clone().readUtf8())
                .asJsonObject
        val file =
            fixture.file(11).apply {
                addProperty("fileFingerprint", body.array("fingerprints").first().asLong)
            }
        if (fixture.mode == "false-fingerprint") {
            file
                .array("hashes")
                .first()
                .asJsonObject
                .addProperty("value", "0".repeat(40))
        }
        return curseForgeData(mapOf("exactMatches" to listOf(mapOf("file" to file))))
    }

    private fun detailsResponse(path: String): MockResponse? =
        when {
            path.endsWith("/description") ->
                curseForgeData(
                    "<p>Project description</p>" + "<script>untrusted()</script>",
                )
            path.endsWith("/changelog") -> curseForgeData("<p>Changes</p>")
            else -> null
        }

    /** 模组详情、文件列表与单文件的路径看起来互相包含，因此按具体到笼统的顺序匹配。 */
    private fun modResponse(
        url: HttpUrl,
        path: String,
    ): MockResponse =
        when {
            Regex("/v1/mods/[0-9]+/files/[0-9]+").matches(path) ->
                curseForgeData(fixture.file(path.substringAfterLast('/').toInt()))
            Regex("/v1/mods/[0-9]+/files").matches(path) -> {
                val id = url.pathSegments[2].toInt()
                val items =
                    if (id == 1) {
                        listOf(fixture.file(12), fixture.file(11))
                    } else {
                        listOf(fixture.file(id * 10 + 1))
                    }
                MockResponse()
                    .setBody(
                        gson.toJson(
                            mapOf(
                                "data" to items,
                                "pagination" to mapOf("totalCount" to items.size),
                            ),
                        ),
                    )
            }
            Regex("/v1/mods/[0-9]+").matches(path) ->
                curseForgeData(fixture.project(path.substringAfterLast('/').toInt()))
            else -> MockResponse().setResponseCode(404)
        }
}
