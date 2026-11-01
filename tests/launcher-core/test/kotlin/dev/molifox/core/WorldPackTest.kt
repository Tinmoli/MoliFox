// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证世界分类的内容源、包结构校验与 saves/ 安装行为。

package dev.molifox.core

import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorldPackTest {
    /** 本次测试的数据目录：项目 `tests/.tmp/` 下，测试结束（含失败）递归删除。 */
    private val temporary: Path = workTestDirectory("launcher-core-worlds")

    @AfterEach
    fun cleanUp() {
        removeTestTree(temporary)
    }

    // ---- 世界包内容源 ----

    @Test
    fun `world search always uses curseforge class id 17 even when settings select modrinth`() =
        withWorldFixture(emptyMap()) { fixture ->
            val result =
                fixture.service.search(
                    "sky",
                    "world",
                    fixture.instance.id,
                    0,
                    ContentFilter("1.20.1"),
                )
            assertEquals(1, result.total)
            val hit = result.hits.single()
            assertEquals("world", hit.type)
            assertEquals("curseforge:900", hit.id)
            assertEquals(
                "https://www.curseforge.com/minecraft/worlds/sky-world",
                contentWebsite(hit),
            )
            assertTrue(
                fixture.requests.any {
                    it.requestUrl?.encodedPath == "/v1/mods/search" &&
                        it.requestUrl?.queryParameter("classId") == "17"
                },
            )
        }

    @Test
    fun `world categories come from the curseforge class category list`() =
        withWorldFixture(emptyMap()) { fixture ->
            val options = fixture.service.contentCategories("world")
            assertEquals(listOf("生存"), options.map { it.name })
            assertTrue(
                fixture.requests.any {
                    it.requestUrl?.encodedPath == "/v1/categories" &&
                        it.requestUrl?.queryParameter("classId") == "17"
                },
            )
        }

    // ---- 安装 ----

    @Test
    fun `wrapped world pack installs under wrapper name and skips session lock`() =
        withWorldFixture(
            linkedMapOf(
                "My World/level.dat" to levelDat("我的世界"),
                "My World/region/r.0.0.mca" to "region".toByteArray(),
                "My World/session.lock" to "locked".toByteArray(),
            ),
        ) { fixture ->
            fixture.install()
            val saves = fixture.saves()
            val world = saves.resolve("My World")
            assertTrue(world.resolve("level.dat").isRegularFile())
            assertEquals("region", world.resolve("region/r.0.0.mca").readText())
            assertFalse(world.resolve("session.lock").exists())
            assertFalse(saves.listDirectoryEntries().any { it.name.startsWith(".molifox-") })
        }

    @Test
    fun `root level dat world uses level name as the saves directory`() =
        withWorldFixture(
            linkedMapOf(
                "level.dat" to levelDat("我的世界"),
                "region/r.0.0.mca" to "region".toByteArray(),
            ),
        ) { fixture ->
            fixture.install()
            val world = fixture.saves().resolve("我的世界")
            assertTrue(world.resolve("level.dat").isRegularFile())
            assertEquals("region", world.resolve("region/r.0.0.mca").readText())
        }

    @Test
    fun `unsafe wrapper name falls back to the level name`() =
        withWorldFixture(
            linkedMapOf(
                "CON/level.dat" to levelDat("正常世界"),
                "CON/region/r.0.0.mca" to "region".toByteArray(),
            ),
        ) { fixture ->
            fixture.install()
            assertTrue(fixture.saves().resolve("正常世界/level.dat").isRegularFile())
        }

    @Test
    fun `duplicate world name is rejected and leaves no temp directory`() =
        withWorldFixture(
            linkedMapOf(
                "My World/level.dat" to levelDat("我的世界"),
                "My World/region/r.0.0.mca" to "region".toByteArray(),
            ),
        ) { fixture ->
            fixture.install()
            val failure = assertFailsWith<IllegalArgumentException> { fixture.install() }
            assertTrue(failure.message.orEmpty().contains("已有同名世界"))
            assertFalse(
                fixture.saves().listDirectoryEntries().any { it.name.startsWith(".molifox-") },
            )
        }

    @Test
    fun `world pack without level dat is rejected before writing saves`() =
        withWorldFixture(linkedMapOf("region/r.0.0.mca" to "region".toByteArray())) { fixture ->
            val failure = assertFailsWith<IllegalArgumentException> { fixture.install() }
            assertTrue(failure.message.orEmpty().contains("level.dat"))
            val saves = fixture.saves()
            assertFalse(saves.exists() && saves.listDirectoryEntries().isNotEmpty())
        }

    @Test
    fun `world pack with several wrapped worlds is rejected`() =
        withWorldFixture(
            linkedMapOf(
                "World A/level.dat" to levelDat("世界 A"),
                "World B/level.dat" to levelDat("世界 B"),
            ),
        ) { fixture ->
            val failure = assertFailsWith<IllegalArgumentException> { fixture.install() }
            assertTrue(failure.message.orEmpty().contains("多个世界目录"))
        }

    @Test
    fun `world pack version incompatible with the instance is rejected`() =
        withWorldFixture(
            linkedMapOf("My World/level.dat" to levelDat("我的世界")),
            gameVersions = listOf("1.21"),
        ) { fixture ->
            val failure = assertFailsWith<IllegalArgumentException> { fixture.install() }
            assertTrue(failure.message.orEmpty().contains("不支持实例的游戏版本"))
        }

    // ---- 夹具 ----

    private fun levelDat(levelName: String): ByteArray =
        gzipBytes(
            TestNbtWriter().root {
                nested("Data") {
                    string("LevelName", levelName)
                }
            },
        )

    private fun zipBytes(entries: Map<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }

    private fun newStore(): LauncherStore = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))

    private fun newInstance(store: LauncherStore): GameInstance {
        val instance =
            GameInstance(
                name = "Worlds",
                gameVersion = "1.20.1",
                versionId = "1.20.1",
                installed = true,
            )
        store.mutate { it.copy(instances = listOf(instance)) }
        store.instancePath(instance.id).createDirectories()
        return store.instance(instance.id)
    }

    /** 世界安装夹具：假 CurseForge 服务返回一个固定的世界项目与世界包。 */
    private class WorldFixture(
        val store: LauncherStore,
        val instance: GameInstance,
        private val archive: ByteArray,
        private val server: MockWebServer,
        private val gameVersions: List<String>,
        val service: ContentService,
    ) {
        val requests = CopyOnWriteArrayList<RecordedRequest>()

        fun install() =
            runBlocking {
                service.installWorld(
                    worldHit(),
                    WORLD_VERSION_ID,
                    instance.id,
                    JobContext(store, "world-install"),
                )
            }

        fun saves(): Path = store.instancePath(instance.id).resolve("saves")

        fun dispatcher(): Dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val path = request.requestUrl?.encodedPath.orEmpty()
                    return when {
                        path == "/v1/categories" &&
                            request.requestUrl?.queryParameter("classId") == null ->
                            data(classIndex())
                        path == "/v1/categories" ->
                            data(
                                listOf(
                                    mapOf("id" to 101, "name" to "生存", "slug" to "survival"),
                                ),
                            )
                        path == "/v1/mods/search" ->
                            MockResponse()
                                .setBody(
                                    gson.toJson(
                                        mapOf(
                                            "data" to listOf(project()),
                                            "pagination" to mapOf("totalCount" to 1),
                                        ),
                                    ),
                                )
                        path == "/v1/mods/900" || path == "/v1/mods/900/description" ->
                            if (path.endsWith("/description")) data("") else data(project())
                        path == "/v1/mods/900/files/901/changelog" -> data("")
                        path == "/v1/mods/900/files/901" -> data(file())
                        path == "/files/901" -> MockResponse().setBody(Buffer().write(archive))
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }

        private fun classIndex(): List<Map<String, Any>> =
            listOf(mapOf("id" to 6, "gameId" to 432, "slug" to "mc-mods", "isClass" to true))

        private fun project(): JsonObject =
            gson
                .toJsonTree(
                    mapOf(
                        "id" to 900,
                        "gameId" to 432,
                        "classId" to 17,
                        "name" to "Sky World",
                        "slug" to "sky-world",
                        "summary" to "A world",
                        "authors" to listOf(mapOf("name" to "Author")),
                        "downloadCount" to 100,
                        "allowModDistribution" to true,
                    ),
                ).asJsonObject

        private fun file(): JsonObject =
            gson
                .toJsonTree(
                    mapOf(
                        "id" to 901,
                        "modId" to 900,
                        "gameId" to 432,
                        "isAvailable" to true,
                        "isServerPack" to false,
                        "displayName" to "1.0",
                        "fileName" to "SkyWorld.zip",
                        "releaseType" to 1,
                        "fileDate" to "2025-01-01T00:00:00Z",
                        "fileLength" to archive.size,
                        "hashes" to listOf(mapOf("algo" to 1, "value" to sha1Hex(archive))),
                        "downloadUrl" to server.loopbackUrl("/files/901").toString(),
                        "gameVersions" to gameVersions,
                        "dependencies" to emptyList<Any>(),
                    ),
                ).asJsonObject

        private fun data(value: Any): MockResponse = MockResponse().setBody(gson.toJson(mapOf("data" to value)))
    }

    private fun withWorldFixture(
        entries: Map<String, ByteArray>,
        gameVersions: List<String> = listOf("1.20.1"),
        action: suspend (WorldFixture) -> Unit,
    ) = runBlocking {
        MockWebServer().use { server ->
            val store = newStore()
            val instance = newInstance(store)
            store.mutate {
                it.copy(
                    settings =
                        it.settings.copy(
                            contentDownloadSource = "modrinth",
                            curseForgeBaseUrl = server.loopbackUrl("").toString().trimEnd('/'),
                        ),
                )
            }
            val archive = zipBytes(entries)
            NetworkClient().use { network ->
                val service =
                    ContentService(
                        store,
                        network,
                        MinecraftInstaller(store, network, JavaService(store, network)),
                        server.loopbackUrl("/v2").toString(),
                    )
                val fixture =
                    WorldFixture(store, instance, archive, server, gameVersions, service)
                server.dispatcher = fixture.dispatcher()
                action(fixture)
            }
        }
    }
}

private const val WORLD_VERSION_ID = "curseforge:900:901"

private fun worldHit() = ContentHit("curseforge:900", "sky-world", "Sky World", "", "Author", 1, "world", null)

private fun sha1Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
