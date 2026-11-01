// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 整合包导入测试用的假 Mojang 服务：manifest、profile 与可切换 404 的 client.jar。

package dev.molifox.core

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer

/** 假 Mojang 服务：manifest、profile、assets 与可切换 404 的 client.jar。 */
internal fun fixtureServer(
    client: ByteArray,
    rejectClient: () -> Boolean,
): MockWebServer {
    val server = MockWebServer()
    val base = server.loopbackUrl("/").toString().removeSuffix("/")
    server.dispatcher =
        fixtureDispatcher(base, fixtureProfile(base, client), client, rejectClient)
    return server
}

internal fun fixtureProfile(
    base: String,
    client: ByteArray,
): String =
    gson.toJson(
        mapOf(
            "id" to "fixture",
            "mainClass" to "FixtureMinecraft",
            "type" to "release",
            "libraries" to emptyList<Any>(),
            "downloads" to
                mapOf(
                    "client" to
                        mapOf(
                            "url" to "$base/client.jar",
                            "sha1" to fixtureHash(client, "SHA-1"),
                            "size" to client.size,
                        ),
                ),
            "assetIndex" to mapOf("id" to "fixture", "url" to "$base/assets.json"),
        ),
    )

/** 版本清单：只列一个 fixture 版本，指向 profile.json。 */
internal fun fixtureManifest(
    base: String,
    profile: String,
): String =
    gson.toJson(
        mapOf(
            "versions" to
                listOf(
                    mapOf(
                        "id" to "fixture",
                        "type" to "release",
                        "url" to "$base/profile.json",
                        "sha1" to fixtureHash(profile.toByteArray(), "SHA-1"),
                    ),
                ),
        ),
    )

internal fun fixtureDispatcher(
    base: String,
    profile: String,
    client: ByteArray,
    rejectClient: () -> Boolean,
): Dispatcher =
    object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
            when (request.path) {
                "/manifest.json" ->
                    MockResponse().setBody(fixtureManifest(base, profile))
                "/profile.json" -> MockResponse().setBody(profile)
                "/assets.json" -> MockResponse().setBody("{\"objects\":{}}")
                "/client.jar" ->
                    if (rejectClient()) {
                        MockResponse().setResponseCode(404)
                    } else {
                        MockResponse().setBody(Buffer().write(client))
                    }
                else -> MockResponse().setResponseCode(404)
            }
    }
