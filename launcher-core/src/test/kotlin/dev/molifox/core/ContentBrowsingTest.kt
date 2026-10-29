// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证内容版本筛选、兼容性判断与推荐的行为与边界。

package dev.molifox.core

import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ContentBrowsingTest {
    @TempDir lateinit var temporary: Path

    private class Fixture(
        val store: LauncherStore,
        val service: ContentService,
        val server: MockWebServer,
        val instance: GameInstance,
    ) {
        var kind = "mod"
        var badManifest = false
        var wrongProject = false
        var wrongGame = false
        var onDownload: (() -> Unit)? = null
        val world
            get() = store.instancePath(instance.id).resolve("saves/World")

        fun payload(): ByteArray =
            if (kind == "modpack") "pack-stable".toByteArray()
            else
                ByteArrayOutputStream().use { buffer ->
                    ZipOutputStream(buffer).use { zip ->
                        zip.putNextEntry(
                            ZipEntry(if (badManifest) "nested/pack.mcmeta" else "pack.mcmeta")
                        )
                        zip.write(
                            "{\"pack\":{\"pack_format\":81,\"description\":\"Test\"}}".toByteArray()
                        )
                        zip.closeEntry()
                    }
                    buffer.toByteArray()
                }

        fun version(id: String): Map<String, Any> =
            mapOf(
                "id" to id,
                "project_id" to if (wrongProject || id == "foreign") "other" else "project",
                "name" to "File $id",
                "version_number" to id,
                "version_type" to if (id == "beta") "beta" else "release",
                "date_published" to
                    if (id == "beta") "2026-10-27T10:00:00Z" else "2026-10-26T10:00:00Z",
                "game_versions" to listOf(if (wrongGame) "1.20.1" else "26.3"),
                "loaders" to listOf(if (kind == "mod") "fabric" else "minecraft"),
                "featured" to (id == "stable"),
                "changelog" to "Changes for $id",
                "files" to
                    listOf(
                        mapOf(
                            "filename" to if (kind == "modpack") "$id.mrpack" else "sample.zip",
                            "url" to server.loopbackUrl("/files/$id").toString(),
                            "size" to payload().size,
                            "hashes" to mapOf("sha1" to sha1(payload())),
                            "primary" to true,
                        )
                    ),
            )

        fun project() =
            mapOf(
                "id" to "project",
                "slug" to "sample",
                "title" to "测试 & Space",
                "project_type" to kind,
                "body" to "Project description",
                "wiki_url" to "https://example.org/wiki",
                "source_url" to "https://example.org/source",
                "issues_url" to "https://name:secret@example.org/issues",
                "discord_url" to "javascript:alert(1)",
            )
    }

    private fun fixture(action: suspend Fixture.() -> Unit) = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val store = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))
            val instance =
                GameInstance(
                    name = "Target",
                    gameVersion = "26.3",
                    versionId = "26.3-fabric",
                    loader = LoaderType.Fabric,
                    installed = true,
                )
            store.mutate { it.copy(instances = listOf(instance)) }
            NetworkClient().use { network ->
                val service =
                    ContentService(
                        store,
                        network,
                        MinecraftInstaller(store, network, JavaService(store, network)),
                        server.loopbackUrl("/v2").toString().trimEnd('/'),
                    )
                val fixture = Fixture(store, service, server, instance)
                fixture.world.createDirectories().resolve("level.dat").writeText("world metadata")
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            val path = request.requestUrl!!.encodedPath
                            val response: Any =
                                when {
                                    path == "/v2/search" ->
                                        mapOf("hits" to emptyList<Any>(), "total_hits" to 0)
                                    path == "/v2/project/project" -> fixture.project()
                                    path == "/v2/project/project/version" ->
                                        listOf("beta", "stable", "foreign").map(fixture::version)
                                    path.startsWith("/v2/version/") ->
                                        fixture.version(path.substringAfterLast('/'))
                                    path.startsWith("/files/") -> {
                                        fixture.onDownload?.invoke()
                                        return MockResponse()
                                            .setBody(Buffer().write(fixture.payload()))
                                    }
                                    else -> return MockResponse().setResponseCode(404)
                                }
                            return MockResponse()
                                .setHeader("Content-Type", "application/json")
                                .setBody(gson.toJson(response))
                        }
                    }
                fixture.action()
            }
        }
    }

    @Test
    fun `browse without instance returns version metadata and safe links`() = fixture {
        val details = service.catalog.browse("project")
        assertEquals(listOf("beta", "stable"), details.versions.map { it.id })
        assertEquals(listOf("26.3"), details.versions.first().gameVersions)
        assertEquals(
            listOf("Modrinth", "项目 Wiki", "源代码"),
            details.links.map { it.title },
        )
        assertFalse(details.links.any { "secret" in it.url || it.url.startsWith("javascript:") })
        assertFalse(details.links.any { it.url.contains("search.mcmod.cn") })
        val requests = List(2) { server.takeRequest() }
        assertEquals("false", requests.last().requestUrl!!.queryParameter("include_changelog"))
        assertEquals("stable", recommendedContentVersion(details.versions)?.id)
    }

    @Test
    fun `game loader and channel filters intersect without changing instance`() = fixture {
        val details = service.catalog.browse("project")
        assertEquals(
            listOf("stable"),
            filteredContentVersions(
                    details.versions,
                    ContentFilter("26.3", LoaderType.Fabric),
                    "release",
                )
                .map { it.id },
        )
        assertTrue(filteredContentVersions(details.versions, ContentFilter("1.20.1")).isEmpty())
        assertTrue(
            filteredContentVersions(details.versions, ContentFilter("26.3", LoaderType.Forge))
                .isEmpty()
        )
        assertEquals(instance, store.instance(instance.id))
    }

    @Test
    fun `recommendation never replaces a stable release with newer beta`() {
        val stable = ContentVersion("stable", "", "", "release", "2026-01-01", "")
        val beta =
            stable.copy(id = "beta", channel = "beta", published = "2026-02-01", featured = true)
        assertEquals("stable", recommendedContentVersion(listOf(beta, stable))?.id)
        assertEquals("beta", recommendedContentVersion(listOf(beta))?.id)
        assertNull(recommendedContentVersion(emptyList()))
    }

    @Test
    fun `recommendation follows installation target without falling back to incompatible files`() {
        val stable =
            ContentVersion("compatible", "", "", "release", "2026-01-01", "")
                .copy(gameVersions = listOf("1.20.1"), loaders = listOf("fabric"))
        val featured =
            stable.copy(id = "featured-other-game", featured = true, gameVersions = listOf("1.21"))
        val wrongLoader =
            stable.copy(id = "wrong-loader", featured = true, loaders = listOf("neoforge"))
        val target =
            GameInstance(
                name = "Target",
                gameVersion = "1.20.1",
                loader = LoaderType.Fabric,
                installed = true,
            )
        assertEquals(
            "compatible",
            recommendedContentVersion(listOf(featured, wrongLoader, stable), target)?.id,
        )
        assertNull(recommendedContentVersion(listOf(featured, wrongLoader), target))
        assertEquals(
            "featured-other-game",
            recommendedContentVersion(listOf(featured, stable), target, "modpack")?.id,
        )
        assertEquals("featured-other-game", recommendedContentVersion(listOf(featured, stable))?.id)
    }

    @Test
    fun `explicit browsing filter overrides selected instance and can clear it`() = fixture {
        service.search(
            "test",
            "mod",
            instance.id,
            filter = ContentFilter("1.21.11", LoaderType.Quilt),
        )
        val first = server.takeRequest().requestUrl!!.queryParameter("facets")!!
        assertTrue(first.contains("1.21.11") && first.contains("quilt"))
        assertFalse(first.contains("26.3") || first.contains("fabric"))
        service.search("", "mod", instance.id, filter = ContentFilter())
        assertEquals(
            com.google.gson.JsonParser.parseString("[[\"project_type:mod\"]]"),
            com.google.gson.JsonParser.parseString(
                server.takeRequest().requestUrl!!.queryParameter("facets")
            ),
        )
    }

    @Test
    fun `foreign version cannot supply a changelog or pack file`() = fixture {
        wrongProject = true
        assertFailsWith<IllegalArgumentException> {
            service.catalog.browseVersion("project", "stable")
        }
        kind = "modpack"
        assertFailsWith<IllegalArgumentException> {
            service.downloadPack(
                ContentHit("project", "sample", "Pack", "", "", 0, kind, null),
                JobContext(store, "test"),
                "stable",
            )
        }
    }

    @Test
    fun `pack downloads exactly selected older version rather than latest beta`() = fixture {
        kind = "modpack"
        val pack =
            service.downloadPack(
                ContentHit("project", "sample", "Pack", "", "", 0, kind, null),
                JobContext(store, "test"),
                "stable",
            )
        assertEquals("stable.mrpack", pack.name)
        assertContentEquals(payload(), pack.readBytes())
        assertEquals("/v2/version/stable", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals("/files/stable", server.takeRequest().requestUrl!!.encodedPath)
    }

    private suspend fun Fixture.install() =
        service.installDatapack(
            ContentHit(
                "project",
                "sample",
                "Data",
                "",
                "",
                0,
                "datapack",
                null,
            ),
            "stable",
            instance.id,
            "World",
            JobContext(store, "test"),
        )

    @Test
    fun `data pack goes to selected world and staging is removed`() = fixture {
        kind = "datapack"
        install()
        assertContentEquals(payload(), world.resolve("datapacks/sample.zip").readBytes())
        assertEquals(
            listOf("sample.zip"),
            world.resolve("datapacks").listDirectoryEntries().map { it.name },
        )
        assertTrue(
            store.temporaryDirectory.resolve("datapack-staging").listDirectoryEntries().isEmpty()
        )
        assertFalse(store.instancePath(instance.id).resolve("datapacks").exists())
    }

    @Test
    fun `existing data pack remains intact after collision`() = fixture {
        kind = "datapack"
        world.resolve("datapacks").createDirectory().resolve("sample.zip").writeText("keep")
        assertFailsWith<IllegalArgumentException> { install() }
        assertEquals("keep", world.resolve("datapacks/sample.zip").readText())
        assertTrue(
            store.temporaryDirectory.resolve("datapack-staging").listDirectoryEntries().isEmpty()
        )
    }

    @Test
    fun `incompatible data pack and invalid archive cannot change world`() = fixture {
        kind = "datapack"
        wrongGame = true
        assertFailsWith<IllegalArgumentException> { install() }
        wrongGame = false
        badManifest = true
        assertFailsWith<IllegalStateException> { install() }
        assertFalse(world.resolve("datapacks").exists())
        assertTrue(
            store.temporaryDirectory.resolve("datapack-staging").listDirectoryEntries().isEmpty()
        )
    }

    @Test
    fun `world replaced during download is rejected before writing`() = fixture {
        kind = "datapack"
        onDownload = {
            world.moveTo(world.resolveSibling("Original"))
            world.createDirectory().resolve("level.dat").writeText("replacement")
        }
        assertFailsWith<IllegalArgumentException> { install() }
        assertFalse(world.resolve("datapacks").exists())
        assertFalse(world.resolveSibling("Original/datapacks").exists())
    }
}
