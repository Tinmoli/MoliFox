// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证模组更新的下载替换、失败回滚与批量查询行为。

package dev.molifox.core

import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModUpdatesApplyTest : ModUpdatesTestSupport() {
    @Test
    fun `update replaces file and preserves disabled state`() =
        runBlocking {
            val (store, instance, root) = fixture()
            val old = writeFoxJar(root.resolve("mods/fox.jar.disabled"))
            val oldBytes = old.readBytes()
            val newBytes = jarBytes("1.1.0")
            applyFoxUpdate(store, instance, "fox.jar.disabled", newBytes, "fox-1.1.0.jar")
            assertFalse(old.exists())
            val replacement = root.resolve("mods/fox-1.1.0.jar.disabled")
            assertTrue(replacement.exists())
            assertContentEquals(newBytes, replacement.readBytes())
            assertFalse(root.resolve("mods/fox-1.1.0.jar").exists())
            assertTrue(
                store.state.value.logs.any {
                    it.message.contains("更新模组：Fox 1.0.0 → 1.1.0")
                },
            )
            val trashed =
                temporary.resolve("trash").listDirectoryEntries().firstOrNull {
                    it.readBytes().contentEquals(oldBytes)
                }
            assertNotNull(trashed)
            Unit
        }

    private suspend fun applyFoxUpdate(
        store: LauncherStore,
        instance: GameInstance,
        oldName: String,
        newBytes: ByteArray,
        filename: String,
    ) {
        val remote = project("fox")
        val current = version(remote, "1.0.0", jarBytes("1.0.0"))
        val latest = version(remote, "1.1.0", newBytes, filename = filename)
        val source =
            FakeSource(
                "modrinth",
                fingerprints = mapOf(oldName to current),
                versionMap = mapOf(remote.id to listOf(current, latest)),
            )
        val service =
            ModUpdateService(
                store,
                ModMetadataReader(),
                listOf(source),
                { _, target, _ -> target.writeBytes(newBytes) },
            )
        val item = service.check(instance.id).items.single()
        assertEquals(ModUpdateStatus.Updatable, item.status)
        service.apply(instance.id, item, null)
    }

    @Test
    fun `failed download keeps the original jar`() =
        runBlocking {
            val (store, instance, root) = fixture()
            val old = writeFoxJar(root.resolve("mods/fox.jar"))
            val original = old.readBytes()
            val failure = applyFailingUpdate(store, instance)
            assertTrue(failure is IOException)
            assertTrue(old.exists())
            assertContentEquals(original, old.readBytes())
            assertFalse(root.resolve("mods/fox-1.1.0.jar").exists())
            assertTrue(
                root.resolve("mods").listDirectoryEntries().none {
                    it.name.startsWith(".molifox-update-")
                },
            )
        }

    private suspend fun applyFailingUpdate(
        store: LauncherStore,
        instance: GameInstance,
    ): Throwable? {
        val remote = project("fox")
        val current = version(remote, "1.0.0", jarBytes("1.0.0"))
        val latest = version(remote, "1.1.0", jarBytes("1.1.0"), filename = "fox-1.1.0.jar")
        val source =
            FakeSource(
                "modrinth",
                fingerprints = mapOf("fox.jar" to current),
                versionMap = mapOf(remote.id to listOf(current, latest)),
            )
        val service =
            ModUpdateService(
                store,
                ModMetadataReader(),
                listOf(source),
                { _, target, _ ->
                    target.writeBytes(jarBytes("partial"))
                    throw IOException("网络中断")
                },
            )
        val item = service.check(instance.id).items.single()
        return runCatching { service.apply(instance.id, item, null) }.exceptionOrNull()
    }

    @Test
    fun `fingerprint matched mods use one batch request per check and reuse hashes`() =
        runBlocking {
            val (store, instance, root) = fixture()
            val installed = installedFoxMods(root)
            val hashes = installed.associate { (name, bytes) -> name to sha512(bytes) }
            val (current, latest) = modrinthVersionBodies(installed, hashes)
            val requests = mutableListOf<String>()
            MockWebServer().use { server ->
                server.dispatcher = modrinthVersionDispatcher(current, latest, requests)
                server.start(InetAddress.getByName("127.0.0.1"), 0)
                val hashCounter = HashCounter()
                val service = batchUpdateService(store, server, hashCounter)
                val first = service.check(instance.id)
                assertEquals(6, first.items.size)
                assertEquals(6, first.updates)
                assertTrue(first.items.all { it.candidate?.versionNumber == "1.1.0" })
                // 6 个文件 = 1 次指纹 + 1 次批量版本，没有逐项目版本请求。
                assertEquals(1, requests.count { it == "/v2/version_files" })
                assertEquals(1, requests.count { it == "/v2/version_files/update" })
                assertTrue(requests.none { it.startsWith("/v2/project/") })
                assertEquals(6, hashCounter.calls)
                // 再检查一次：结果一致，摘要与元数据全部命中缓存，不会重新读 jar。
                requests.clear()
                val again = service.check(instance.id)
                assertEquals(6, again.items.size)
                assertEquals(6, hashCounter.calls)
                assertEquals(2, requests.size)
                assertEquals(1, requests.count { it == "/v2/version_files/update" })
            }
        }

    private class HashCounter {
        var calls = 0
    }

    private fun batchUpdateService(
        store: LauncherStore,
        server: MockWebServer,
        hashCounter: HashCounter,
    ): ModUpdateService =
        ModUpdateService(
            store,
            ModMetadataReader(),
            listOf(
                ModrinthModUpdateSource(
                    NetworkClient(),
                    "http://127.0.0.1:${server.port}/v2",
                ),
            ),
            { _, _, _ -> },
            hasher = { path, algorithm ->
                hashCounter.calls++
                digest(path, algorithm)
            },
        )

    private fun installedFoxMods(root: Path): List<Pair<String, ByteArray>> =
        (0 until 6).map { index ->
            val name = "mod-$index.jar"
            val bytes =
                jar(
                    root.resolve("mods/$name"),
                    mapOf(
                        "fabric.mod.json" to
                            """{"id":"mod$index","name":"Mod$index","version":"1.0.0"}""",
                    ),
                ).readBytes()
            name to bytes
        }

    private fun modrinthVersionBodies(
        installed: List<Pair<String, ByteArray>>,
        hashes: Map<String, String>,
    ): Pair<JsonObject, JsonObject> {
        val current =
            JsonObject().apply {
                installed.forEachIndexed { index, (name, _) ->
                    add(hashes.getValue(name), modrinthVersion("p$index", "1.0.0", name))
                }
            }
        val latest =
            JsonObject().apply {
                installed.forEachIndexed { index, (name, _) ->
                    add(
                        hashes.getValue(name),
                        modrinthVersion("p$index", "1.1.0", name.replace(".jar", "-1.1.0.jar")),
                    )
                }
            }
        return current to latest
    }

    private fun modrinthVersionDispatcher(
        current: JsonObject,
        latest: JsonObject,
        requests: MutableList<String>,
    ): Dispatcher =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                requests += path
                val body =
                    when (path) {
                        "/v2/version_files" -> current
                        "/v2/version_files/update" -> latest
                        else -> JsonObject()
                    }
                return MockResponse()
                    .setBody(body.toString())
                    .setHeader("Content-Type", "application/json")
            }
        }
}
