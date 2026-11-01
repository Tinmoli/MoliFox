// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验证传输假实现能在应用层响应请求，测试不进行 DNS 或公网连接。

package dev.molifox.core

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InjectedTransportTest {
    @Test
    fun `injected transport resolves an invalid host without a socket`() =
        runBlocking {
            val requested = mutableListOf<String>()
            val transport =
                Interceptor { chain ->
                    requested += chain.request().url.host
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("Fixture")
                        .body("{\"fixture\":true}".toResponseBody("application/json".toMediaType()))
                        .build()
                }
            NetworkClient(interceptors = listOf(transport)).use { network ->
                val json =
                    network.json(
                        "https://no-network.invalid/value.json",
                        sourceOverride = "official",
                    )
                assertTrue(json.asJsonObject.get("fixture").asBoolean)
            }
            assertEquals(listOf("no-network.invalid"), requested)
        }
}
