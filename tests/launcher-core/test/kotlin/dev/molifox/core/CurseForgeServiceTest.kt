// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证 CurseForge 服务地址、错误处理与批量元数据请求的边界。

package dev.molifox.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.io.path.appendBytes
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** 服务地址回退、错误脱敏、指纹流式处理与批量请求的行为与边界。 */
class CurseForgeServiceTest : CurseForgeTestBase() {
    @Test
    fun `empty or invalid service falls back to the default mirror with a log line`() =
        fixture {
            store.mutate { it.copy(settings = it.settings.copy(curseForgeBaseUrl = "")) }
            assertEquals(
                DEFAULT_CURSE_FORGE_BASE_URL,
                store.data.settings.effectiveCurseForgeBaseUrl,
            )
            assertEquals(
                "$DEFAULT_CURSE_FORGE_BASE_URL/v1",
                CurseForgeBackend(store, network).api,
            )
            assertTrue(
                store.state.value.logs
                    .any { it.message.contains("默认镜像") },
            )

            store.mutate {
                it.copy(
                    settings = it.settings.copy(curseForgeBaseUrl = "ftp://example.com/curseforge"),
                )
            }
            assertEquals(
                DEFAULT_CURSE_FORGE_BASE_URL,
                store.data.settings.effectiveCurseForgeBaseUrl,
            )
            assertEquals(
                "$DEFAULT_CURSE_FORGE_BASE_URL/v1",
                CurseForgeBackend(store, network).api,
            )
        }

    @Test
    fun `mirrors without description endpoints still expose installable metadata`() =
        fixture {
            mode = "no-details"
            val details = service.catalog.details(hit().id, instance.id)
            assertEquals("", details.body)
            assertEquals("", details.versions.first().changelog)
            assertEquals("curseforge:1:12", details.versions.first().id)
            assertTrue(
                service.planContent(hit(), instance.id, versionId = "curseforge:1:12").files.any {
                    it.projectId == "curseforge:1" && it.versionId == "curseforge:1:12"
                },
            )
        }

    @Test
    fun `custom service base is applied to every request`() =
        fixture {
            service.search("sodium", "mod", instance.id)
            val request =
                checkNotNull(
                    requests
                        .last {
                            checkNotNull(it.requestUrl).encodedPath.endsWith("/search")
                        }.requestUrl,
                )
            assertEquals(server.port, request.port)
            assertEquals("/v1/mods/search", request.encodedPath)
            assertEquals("sodium", request.queryParameter("searchFilter"))
        }

    @Test
    fun `failing CurseForge request is a normal error and other sources stay usable`() =
        fixture {
            mode = "server-error"
            val failure = assertFailsWith<IllegalStateException> { service.search("", "mod") }
            assertTrue(failure.message.orEmpty().contains("500"))
            store.mutate {
                it.copy(settings = it.settings.copy(contentDownloadSource = "modrinth"))
            }
            assertEquals(
                "modrinth-project",
                service
                    .search("", "mod")
                    .hits
                    .single()
                    .id,
            )
        }

    @Test
    fun `fingerprint ignores only defined whitespace and handles streaming block boundaries`() =
        runBlocking {
            val empty = temporary.resolve("empty").apply { writeText("") }
            assertEquals(0x5bd15e36L, curseForgeFingerprint(empty))
            val raw =
                temporary.resolve("raw").apply {
                    writeBytes(ByteArray(70000) { (it % 200 + 40).toByte() })
                }
            val spaced =
                temporary.resolve("spaced").apply {
                    writeBytes(
                        raw
                            .readBytes()
                            .flatMap {
                                listOf(it, 9.toByte(), 10.toByte(), 13.toByte(), 32.toByte())
                            }.toByteArray(),
                    )
                }
            assertEquals(curseForgeFingerprint(raw), curseForgeFingerprint(spaced))
            raw.appendBytes(byteArrayOf(11))
            assertNotEquals(curseForgeFingerprint(raw), curseForgeFingerprint(spaced))
        }

    @Test
    fun `malformed responses hide their body and invalid service schemes stop before network`() =
        fixture {
            mode = "malformed-response"
            val failure = assertFails { service.search("", "mod") }
            assertFalse(failure.stackTraceToString().contains("invalid-json"))
            val count = requests.size
            assertFailsWith<IllegalArgumentException> {
                network.curseForgeJson("ftp://example.com/v1/mods")
            }
            assertEquals(count, requests.size)

            mode = "redirect"
            val redirect = assertFails { service.search("", "mod") }
            assertTrue(redirect.message.orEmpty().contains("重定向"))
            assertTrue(requests.none { it.path == "/leak" })
        }

    @Test
    fun `large pack metadata batches project and file requests without fetching descriptions`() =
        fixture {
            val ids = (1..61).map { "curseforge:$it:${it * 10 + 1}" }
            val metadata = backend.packMetadata(ids)
            assertEquals(ids.toSet(), metadata.keys)
            assertEquals(5, requests.size)
            for (request in requests.filter { it.method == "POST" }) {
                val data = JsonParser.parseString(request.body.clone().readUtf8()).asJsonObject
                assertTrue(
                    (if (data.has("modIds")) data.array("modIds") else data.array("fileIds"))
                        .size() <= 50,
                )
            }
            assertTrue(
                requests.none {
                    checkNotNull(it.path).contains("changelog") ||
                        checkNotNull(it.path).contains("description")
                },
            )
        }
}
