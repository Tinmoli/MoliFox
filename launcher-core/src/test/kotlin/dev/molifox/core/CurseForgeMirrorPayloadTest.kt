// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责用真实镜像录制样本验证 CurseForge 搜索的请求构造与响应映射。

package dev.molifox.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 样本录自 `https://mod.mcimirror.top/curseforge/v1`（2026-10-29），字段与镜像实际返回一致：
 * 分类项的 `isClass` 可以是 `null`，项目里带 `links`/`logo`/`authors` 等真实字段。
 */
class CurseForgeMirrorPayloadTest {
    @TempDir lateinit var temporary: Path

    private fun recorded(name: String): String =
        requireNotNull(javaClass.getResource("/curseforge/$name")) { "缺少录制样本：$name" }.readText()

    /** 搜索样本只有一条，它是完整的 CurseForge 项目，可直接当项目详情样本。 */
    private fun recordedHit(): JsonObject =
        JsonParser
            .parseString(recorded("search-mods.json"))
            .asJsonObject
            .array("data")
            .first()
            .asJsonObject

    private fun searchPayload(): JsonObject =
        JsonParser.parseString(recorded("search-mods.json")).asJsonObject

    /** 复制一条改标识，用来验证「部分条目损坏」不会拖垮整页。 */
    private fun twoItemPayload(): JsonObject =
        searchPayload().apply {
            val copy = array("data").first().deepCopy().asJsonObject
            copy.addProperty("id", copy.int("id") + 1)
            copy.addProperty("slug", "${copy.string("slug")}-neighbor")
            copy.addProperty("name", "${copy.string("name")}（录制样本）")
            array("data").add(copy)
        }

    private fun brokenEntry(entry: JsonObject) {
        entry.addProperty("gameId", 1)
        entry.addProperty("id", 0)
    }

    /** 一份可通过文件校验的文件样本（镜像真实文件字段的子集，字段名一致）。 */
    private fun filePayload() =
        gson.toJson(
            mapOf(
                "data" to
                    listOf(
                        mapOf(
                            "id" to 11,
                            "modId" to 238222,
                            "gameId" to 432,
                            "isAvailable" to true,
                            "isServerPack" to false,
                            "displayName" to "jei.jar",
                            "fileName" to "jei.jar",
                            "releaseType" to 1,
                            "fileDate" to "2025-01-01T00:00:00Z",
                            "fileLength" to 3,
                            "hashes" to listOf(mapOf("algo" to 1, "value" to "a".repeat(40))),
                            "downloadUrl" to "https://edge.forgecdn.net/files/11/11/jei.jar",
                            "gameVersions" to listOf("26.3", "Fabric"),
                            "dependencies" to emptyList<Any>(),
                        ),
                    ),
                "pagination" to mapOf("totalCount" to 1),
            ),
        )

    internal class Fixture(
        val store: LauncherStore,
        val network: NetworkClient,
        val service: ContentService,
        val instance: GameInstance,
        val requests: CopyOnWriteArrayList<RecordedRequest>,
    ) {
        var classesPayload: String = ""
        var categoriesPayload: String = ""
        var searchBody: String = ""
        var projectBody: String = ""
        var filesBody: String = ""

        fun lastSearch() = checkNotNull(searches().last().requestUrl)

        fun searches() =
            requests.filter {
                checkNotNull(it.requestUrl).encodedPath ==
                    "/v1/mods/search"
            }

        fun logs() =
            store.state.value.logs
                .map { it.message }
    }

    private fun fixture(
        classesPayload: String = recorded("classes.json"),
        searchBody: String = recorded("search-mods.json"),
        projectBody: String = gson.toJson(mapOf("data" to recordedHit())),
        filesBody: String = "",
        action: suspend Fixture.() -> Unit,
    ) = runBlocking {
        MockWebServer().use { server ->
            val store = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))
            val instance =
                GameInstance(
                    name = "Mirror fixture",
                    gameVersion = "26.3",
                    loader = LoaderType.Fabric,
                    versionId = "26.3-fabric",
                    installed = true,
                )
            store.mutate {
                it.copy(
                    settings =
                        it.settings.copy(
                            contentDownloadSource = "curseforge",
                            curseForgeBaseUrl = server.loopbackUrl("").toString().trimEnd('/'),
                        ),
                    instances = listOf(instance),
                )
            }
            NetworkClient().use { network ->
                val fixture =
                    Fixture(
                        store,
                        network,
                        ContentService(
                            store,
                            network,
                            MinecraftInstaller(store, network, JavaService(store, network), ""),
                        ),
                        instance,
                        CopyOnWriteArrayList(),
                    ).apply {
                        this.classesPayload = classesPayload
                        this.searchBody = searchBody
                        this.projectBody = projectBody
                        this.filesBody = filesBody
                        this.categoriesPayload = recorded("categories-mod.json")
                    }
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            fixture.requests += request
                            val url = checkNotNull(request.requestUrl)
                            val body =
                                when {
                                    url.encodedPath == "/v1/categories" &&
                                        url.queryParameter("classesOnly") == "true" ->
                                        fixture.classesPayload
                                    url.encodedPath == "/v1/categories" ->
                                        fixture.categoriesPayload
                                    url.encodedPath == "/v1/mods/search" -> fixture.searchBody
                                    url.encodedPath == "/v1/mods/238222" &&
                                        fixture.projectBody.isNotBlank() ->
                                        fixture.projectBody
                                    url.encodedPath == "/v1/mods/238222/files" ->
                                        fixture.filesBody
                                    else -> return MockResponse().setResponseCode(404)
                                }
                            return MockResponse()
                                .setBody(body)
                                .addHeader("Content-Type", "application/json")
                        }
                    }
                fixture.action()
            }
        }
    }

    @Test
    fun `version tags come from real versions and never from directory ids`() {
        assertEquals("26.3", minecraftVersionTag("TwilightMC-26.3v1.0"))
        assertEquals("1.21.11", minecraftVersionTag("TwilightMC-1.21.11[彗星版]_v1.3"))
        assertEquals("26.1.2", minecraftVersionTag("TwilightMC-26.1.2v1.0"))
        assertEquals("26.2-snapshot-1", minecraftVersionTag("26.2-snapshot-1"))
        assertEquals("1.20.1", minecraftVersionTag("1.20.1"))
        assertNull(minecraftVersionTag("fabric-loader-0.16.14"))
        assertNull(minecraftVersionTag("整合包"))
        assertTrue(isMinecraftVersionTag("26.3"))
        assertFalse(isMinecraftVersionTag("TwilightMC-26.3v1.0"))
    }

    @Test
    fun `recorded mirror search maps the fields the mirror actually returns`() =
        fixture {
            val result = service.search("jei", "mod", instance.id)
            val hit = result.hits.single()
            assertEquals("curseforge:238222", hit.id)
            assertEquals("jei", hit.slug)
            assertEquals("Just Enough Items (JEI)", hit.title)
            assertEquals("View Items and Recipes", hit.description)
            assertEquals("mezz", hit.author)
            assertEquals(641561626L, hit.downloads)
            assertTrue(
                checkNotNull(
                    hit.iconUrl,
                ).startsWith("https://media.forgecdn.net/avatars/thumbnails/"),
            )
            assertEquals(452, result.total)
            val request = lastSearch()
            assertEquals("6", request.queryParameter("classId"))
            assertEquals("jei", request.queryParameter("searchFilter"))
            assertEquals("12", request.queryParameter("pageSize"))
            assertEquals("2", request.queryParameter("sortField"))
        }

    @Test
    fun `instance directory id is resolved to a real game version instead of zero results`() =
        fixture {
            // 复现原问题：实例是整合包目录 TwilightMC-26.3v1.0，旧实现把整串当 gameVersion → 镜像 0 条。
            val result =
                service.search(
                    "",
                    "mod",
                    instance.id,
                    0,
                    ContentFilter("TwilightMC-26.3v1.0", LoaderType.Fabric),
                )
            assertTrue(result.hits.isNotEmpty())
            val request = lastSearch()
            assertEquals("26.3", request.queryParameter("gameVersion"))
            assertEquals("4", request.queryParameter("modLoaderType"))
            assertTrue(logs().any { it.contains("TwilightMC-26.3v1.0 → 26.3") })
        }

    @Test
    fun `unrecognised version filter is dropped with one log line and never sent`() =
        fixture {
            val result = service.search("", "mod", instance.id, 0, ContentFilter("整合包-无版本"))
            assertTrue(result.hits.isNotEmpty())
            assertNull(lastSearch().queryParameter("gameVersion"))
            assertTrue(logs().any { it.contains("已忽略无法识别的游戏版本筛选") })
        }

    @Test
    fun `blank filter sends only the essential params and vanilla never sends a loader id`() =
        fixture(
            filesBody = filePayload(),
        ) {
            service.search("", "mod", instance.id, 0, ContentFilter())
            val request = lastSearch()
            assertNull(request.queryParameter("gameVersion"))
            assertNull(request.queryParameter("modLoaderType"))
            assertNull(request.queryParameter("categoryId"))

            // 原版实例查版本：没有对应编号就不发 modLoaderType，绝不能发 0（镜像会返回 0 个文件）。
            val backend = CurseForgeBackend(store, network)
            val vanilla = instance.copy(loader = LoaderType.Vanilla)
            assertEquals(1, backend.versions("curseforge:238222", vanilla, "mod").size)
            val filesRequest =
                checkNotNull(
                    requests
                        .last {
                            checkNotNull(it.requestUrl).encodedPath == "/v1/mods/238222/files"
                        }.requestUrl,
                )
            assertNull(filesRequest.queryParameter("modLoaderType"))
            assertEquals("26.3", filesRequest.queryParameter("gameVersion"))
        }

    @Test
    fun `recorded category list is exposed by id and name`() =
        fixture {
            val options = service.contentCategories("mod")
            assertEquals(52, options.size)
            assertEquals("423", options.first().id)
            assertEquals("Map and Information", options.first().name)
            assertTrue(options.any { it.id == "421" && it.name == "API and Library" })
        }

    @Test
    fun `selected category maps to its own id and unknown category is dropped with a log`() =
        fixture {
            service.search("", "mod", instance.id, 0, ContentFilter(categories = setOf("423")))
            assertEquals("423", lastSearch().queryParameter("categoryId"))
            service.search("", "mod", instance.id, 0, ContentFilter(categories = setOf("999999")))
            assertNull(lastSearch().queryParameter("categoryId"))
            assertTrue(logs().any { it.contains("分类筛选无法映射") })
        }

    @Test
    fun `missing isClass in the class payload still resolves the class id`() =
        fixture(
            classesPayload =
                JsonParser
                    .parseString(recorded("classes.json"))
                    .asJsonObject
                    .apply {
                        array("data").forEach { element ->
                            val category = element.asJsonObject
                            if (category.string("slug") == "mc-mods") category.remove("isClass")
                        }
                    }.toString(),
        ) {
            val result = service.search("", "mod", instance.id)
            assertTrue(result.hits.isNotEmpty())
            assertEquals("6", lastSearch().queryParameter("classId"))
        }

    @Test
    fun `one broken entry is skipped with a log while the rest stay usable`() =
        fixture(
            searchBody =
                twoItemPayload()
                    .apply {
                        brokenEntry(
                            array("data").last().asJsonObject,
                        )
                    }.toString(),
        ) {
            val result = service.search("", "mod", instance.id)
            assertEquals(1, result.hits.size)
            assertTrue(logs().any { it.contains("无法识别，已跳过") })
        }

    @Test
    fun `a payload without a single mappable entry is an error with a response snippet`() =
        fixture(
            searchBody =
                twoItemPayload()
                    .apply {
                        array(
                            "data",
                        ).forEach { brokenEntry(it.asJsonObject) }
                    }.toString(),
        ) {
            val failure =
                assertFailsWith<IllegalStateException> { service.search("", "mod", instance.id) }
            assertTrue(failure.message.orEmpty().contains("搜索结果映射"))
            assertTrue(failure.message.orEmpty().contains("响应片段"))
            assertTrue(logs().any { it.contains("搜索结果映射") })
        }

    @Test
    fun `project metadata without gameId still resolves its class`() =
        fixture(
            projectBody = gson.toJson(mapOf("data" to recordedHit().apply { remove("gameId") })),
            filesBody = filePayload(),
        ) {
            val details = service.catalog.browse("curseforge:238222")
            assertEquals("Just Enough Items (JEI)", details.hit.title)
            assertEquals("mod", details.hit.type)
        }
}
