// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证远程来源元数据校验与查询失败提示。

package dev.molifox.core

import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MrpackExportSourceTest : MrpackExportTestBase() {
    @Test
    fun `source metadata must match both hashes size and official upload host rules`() =
        runBlocking {
            fixture { store, _, instance, root ->
                write(root, "mods/test.jar")
                MockWebServer().use { server ->
                    NetworkClient().use { network ->
                        val service = service(store, network, server)
                        val file =
                            service.previewMrpackExport(instance.id, metadata, false).files.single()
                        for (info in invalidRemoteResponses(file)) {
                            server.enqueue(
                                MockResponse().setBody(gson.toJson(mapOf(file.sha512 to info))),
                            )
                            val plan = service.previewMrpackExport(instance.id, metadata)
                            assertTrue(plan.defaultSelection.isEmpty())
                            assertNull(plan.files.single().downloadUrl)
                            assertTrue(plan.files.single().requiresPermission)
                        }
                    }
                }
            }
        }

    @Test
    fun `failed remote lookup is visible and never silently embeds resources`() =
        runBlocking {
            fixture { store, _, instance, root ->
                write(root, "mods/test.jar")
                MockWebServer().use { server ->
                    NetworkClient().use { network ->
                        server.enqueue(
                            MockResponse().setResponseCode(403).setBody("secret-service-message"),
                        )
                        val plan =
                            service(
                                store,
                                network,
                                server,
                            ).previewMrpackExport(instance.id, metadata)
                        assertEquals(1, plan.warnings.size)
                        assertFalse(plan.warnings.single().contains("secret-service-message"))
                        assertTrue(plan.defaultSelection.isEmpty())
                    }
                }
            }
        }

    /** 非法来源元数据：协议、主机后缀、用户信息、端口、空格、片段与非官方 CDN。 */
    private fun invalidRemoteResponses(file: MrpackExportFile): List<JsonObject> =
        listOf(
            "http://cdn.modrinth.com/test.jar",
            "https://cdn.modrinth.com.evil.example/test.jar",
            "https://user@cdn.modrinth.com/a.jar",
            "https://cdn.modrinth.com:8443/a.jar",
            "https://cdn.modrinth.com/a b.jar",
            "https://cdn.modrinth.com/a.jar#fragment",
        ).map { remote(file, it) } +
            listOf(
                remote(file).apply {
                    array("files")
                        .first()
                        .asJsonObject
                        .obj("hashes")
                        .addProperty("sha1", "0".repeat(40))
                },
                remote(file).apply {
                    array("files")
                        .first()
                        .asJsonObject
                        .addProperty("size", file.size + 1)
                },
                remote(file).apply {
                    array("files")
                        .first()
                        .asJsonObject
                        .obj("hashes")
                        .addProperty("sha512", "0".repeat(128))
                },
            )
}
