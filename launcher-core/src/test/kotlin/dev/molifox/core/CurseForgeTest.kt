// 负责验证CurseForge API 适配和授权下载规则的行为与边界。

package dev.molifox.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CurseForgeTest {
    @TempDir lateinit var temporary: Path

    internal class Fixture(
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
                                    "https://www.curseforge.com/minecraft/mc-mods/project-$id"
                            ),
                        "downloadCount" to 100,
                        "allowModDistribution" to (mode != "blocked" || id !in setOf(2, 3)),
                    )
                )
                .asJsonObject

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
                        "fileDate" to
                            if (id == 11) "2025-01-01T00:00:00Z" else "2025-02-01T00:00:00Z",
                        "fileLength" to bytes.size,
                        "hashes" to
                            listOf(
                                mapOf(
                                    "algo" to 1,
                                    "value" to if (mode == "bad-hash") "0" else hash(bytes),
                                )
                            ),
                        "downloadUrl" to
                            if (mode == "missing-url" && id == 21) null
                            else server.loopbackUrl("/files/$id").toString(),
                        "gameVersions" to
                            listOf(
                                if (mode == "wrong-version") "1.21" else "1.20.1",
                                if (mode == "wrong-loader") "Forge" else "Fabric",
                            ),
                        "dependencies" to
                            if (id == 12)
                                listOf(
                                    mapOf(
                                        "modId" to 2,
                                        "relationType" to if (mode == "incompatible") 5 else 3,
                                    ),
                                    mapOf("modId" to 3, "relationType" to 2),
                                    mapOf("modId" to 4, "relationType" to 1),
                                )
                            else emptyList(),
                    )
                )
                .asJsonObject
        }

        companion object {
            fun hash(bytes: ByteArray) =
                MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") {
                    "%02x".format(it)
                }
        }
    }

    private fun fixture(action: suspend Fixture.() -> Unit) = runBlocking {
        MockWebServer().use { server ->
            val store =
                LauncherStore(
                    object : PlatformServices by DesktopPlatform() {
                        override fun moveToTrash(file: Path) {
                            val target = temporary.resolve("trash/${UUID.randomUUID()}")
                            target.parent.createDirectories()
                            Files.move(file, target)
                        }
                    },
                    temporary.resolve("profile"),
                )
            val instance =
                GameInstance(
                    name = "CurseForge fixture",
                    gameVersion = "1.20.1",
                    loader = LoaderType.Fabric,
                    versionId = "fixture",
                    installed = true,
                )
            store.mutate {
                it.copy(
                    settings =
                        it.settings.copy(
                            contentDownloadSource = "curseforge",
                            curseForgeApiKey = "fixture-key",
                        ),
                    instances = listOf(instance),
                )
            }
            NetworkClient().use { network ->
                val api = server.loopbackUrl("/v1").toString()
                val service =
                    ContentService(
                        store,
                        network,
                        MinecraftInstaller(
                            store,
                            network,
                            JavaService(store, network),
                            server.loopbackUrl("/minecraft/manifest").toString(),
                        ),
                        server.loopbackUrl("/v2").toString(),
                        api,
                    )
                val fixture =
                    Fixture(
                        store,
                        server,
                        service,
                        CurseForgeBackend(store, network, api),
                        instance,
                        network,
                    )
                val client =
                    ByteArrayOutputStream().use { output ->
                        ZipOutputStream(output).use { zip ->
                            zip.putNextEntry(ZipEntry("fixture.txt"))
                            zip.write("fixture".toByteArray())
                            zip.closeEntry()
                        }
                        output.toByteArray()
                    }
                val profile =
                    gson.toJson(
                        mapOf(
                            "id" to "1.20.1",
                            "mainClass" to "fixture.Main",
                            "type" to "release",
                            "downloads" to
                                mapOf(
                                    "client" to
                                        mapOf(
                                            "url" to
                                                server
                                                    .loopbackUrl("/minecraft/client.jar")
                                                    .toString(),
                                            "size" to client.size,
                                            "sha1" to Fixture.hash(client),
                                        )
                                ),
                            "assetIndex" to
                                mapOf(
                                    "id" to "fixture",
                                    "url" to server.loopbackUrl("/minecraft/assets").toString(),
                                ),
                            "libraries" to emptyList<String>(),
                        )
                    )
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            fixture.requests += request
                            val url = request.requestUrl!!
                            val path = url.encodedPath
                            if (
                                path.startsWith("/v1/") &&
                                    request.getHeader("x-api-key") != "fixture-key"
                            )
                                return MockResponse().setResponseCode(403)
                            if (fixture.mode == "malformed-response" && path.startsWith("/v1/"))
                                return MockResponse().setBody("invalid-json fixture-key")
                            if (fixture.mode == "redirect" && path.startsWith("/v1/"))
                                return MockResponse()
                                    .setResponseCode(302)
                                    .addHeader("Location", server.loopbackUrl("/leak"))
                            if (path.startsWith("/files/")) {
                                if (fixture.mode == "download-fail" && path.endsWith("21"))
                                    return MockResponse().setResponseCode(404)
                                return MockResponse()
                                    .setBody(
                                        if (fixture.mode == "corrupt-download") "corrupt"
                                        else "file${path.substringAfterLast('/')}"
                                    )
                            }
                            val data: Any =
                                when {
                                    path == "/v1/mods" ->
                                        JsonParser.parseString(request.body.clone().readUtf8())
                                            .asJsonObject
                                            .array("modIds")
                                            .map { fixture.project(it.asInt) }
                                    path == "/v1/mods/files" ->
                                        JsonParser.parseString(request.body.clone().readUtf8())
                                            .asJsonObject
                                            .array("fileIds")
                                            .map { fixture.file(it.asInt) }
                                    path == "/minecraft/manifest" ->
                                        return MockResponse()
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
                                                                            .loopbackUrl(
                                                                                "/minecraft/profile"
                                                                            )
                                                                            .toString(),
                                                                    "sha1" to
                                                                        Fixture.hash(
                                                                            profile.toByteArray()
                                                                        ),
                                                                )
                                                            )
                                                    )
                                                )
                                            )
                                    path == "/minecraft/profile" ->
                                        return MockResponse().setBody(profile)
                                    path == "/minecraft/assets" ->
                                        return MockResponse().setBody("{\"objects\":{}}")
                                    path == "/minecraft/client.jar" ->
                                        return MockResponse().setBody(okio.Buffer().write(client))
                                    path == "/v1/categories" ->
                                        listOf(
                                            mapOf(
                                                "id" to 6,
                                                "gameId" to 432,
                                                "slug" to "mc-mods",
                                                "isClass" to true,
                                            ),
                                            mapOf(
                                                "id" to 12,
                                                "gameId" to 432,
                                                "slug" to "texture-packs",
                                                "isClass" to true,
                                            ),
                                        )
                                    path == "/v1/mods/search" ->
                                        return MockResponse()
                                            .setBody(
                                                gson.toJson(
                                                    mapOf(
                                                        "data" to listOf(fixture.project(1)),
                                                        "pagination" to mapOf("totalCount" to 24),
                                                    )
                                                )
                                            )
                                    path == "/v1/fingerprints/432" -> {
                                        val body =
                                            JsonParser.parseString(request.body.clone().readUtf8())
                                                .asJsonObject
                                        val file =
                                            fixture.file(11).apply {
                                                addProperty(
                                                    "fileFingerprint",
                                                    body.array("fingerprints").first().asLong,
                                                )
                                            }
                                        if (fixture.mode == "false-fingerprint")
                                            file
                                                .array("hashes")
                                                .first()
                                                .asJsonObject
                                                .addProperty("value", "0".repeat(40))
                                        mapOf("exactMatches" to listOf(mapOf("file" to file)))
                                    }
                                    path.endsWith("/description") ->
                                        "<p>Project description</p><script>untrusted()</script>"
                                    path.endsWith("/changelog") -> "<p>Changes</p>"
                                    Regex("/v1/mods/[0-9]+/files/[0-9]+").matches(path) ->
                                        fixture.file(path.substringAfterLast('/').toInt())
                                    Regex("/v1/mods/[0-9]+/files").matches(path) -> {
                                        val id = url.pathSegments[2].toInt()
                                        val items =
                                            if (id == 1) listOf(fixture.file(12), fixture.file(11))
                                            else listOf(fixture.file(id * 10 + 1))
                                        return MockResponse()
                                            .setBody(
                                                gson.toJson(
                                                    mapOf(
                                                        "data" to items,
                                                        "pagination" to
                                                            mapOf("totalCount" to items.size),
                                                    )
                                                )
                                            )
                                    }
                                    Regex("/v1/mods/[0-9]+").matches(path) ->
                                        fixture.project(path.substringAfterLast('/').toInt())
                                    else -> return MockResponse().setResponseCode(404)
                                }
                            return MockResponse().setBody(gson.toJson(mapOf("data" to data)))
                        }
                    }
                fixture.action()
            }
        }
    }

    private fun hit() = ContentHit("curseforge:1", "project-1", "Project 1", "", "", 0, "mod", null)

    @Test
    fun `search filters version loader category and bounded pagination`() = fixture {
        assertEquals(
            "curseforge:1",
            service.search("hello world", "mod", instance.id).hits.single().id,
        )
        val request =
            requests.find { it.requestUrl!!.encodedPath.endsWith("/search") }!!.requestUrl!!
        assertEquals("hello world", request.queryParameter("searchFilter"))
        assertEquals("1.20.1", request.queryParameter("gameVersion"))
        assertEquals("4", request.queryParameter("modLoaderType"))
        assertEquals("6", request.queryParameter("classId"))
        service.search("", "mod", offset = 9996)
        assertEquals("4", requests.last().requestUrl!!.queryParameter("pageSize"))
        val count = requests.size
        assertTrue(service.search("", "mod", offset = 10000).hits.isEmpty())
        assertEquals(count + 1, requests.size) // category discovery only; no out-of-range search
    }

    @Test
    fun `details and exact versions keep provider identity when source changes`() = fixture {
        store.mutate { it.copy(settings = it.settings.copy(contentDownloadSource = "modrinth")) }
        val details = service.catalog.details(hit().id, instance.id)
        assertEquals(listOf("curseforge:1:12", "curseforge:1:11"), details.versions.map { it.id })
        assertEquals("Project description", details.body)
        assertEquals("Author", details.hit.author)
        assertEquals("CurseForge", details.providerName)
        assertEquals(
            "Changes",
            service.catalog.versionDetails(hit().id, "curseforge:1:11", instance.id).changelog,
        )
        assertEquals(
            "https://www.curseforge.com/minecraft/mc-mods/project-1",
            contentWebsite(details.hit),
        )
        val plan = service.planContent(hit(), instance.id, versionId = "curseforge:1:11")
        assertEquals(listOf("curseforge:1:11"), plan.files.map { it.versionId })
    }

    @Test
    fun `browse lists provider versions without an instance and excludes loader tags from game versions`() =
        fixture {
            store.mutate {
                it.copy(settings = it.settings.copy(contentDownloadSource = "modrinth"))
            }
            val details = service.catalog.browse(hit().id)
            assertEquals("CurseForge", details.providerName)
            assertEquals(
                listOf("curseforge:1:12", "curseforge:1:11"),
                details.versions.map { it.id },
            )
            assertEquals(listOf("1.20.1"), details.versions.first().gameVersions)
            assertEquals(listOf("fabric"), details.versions.first().loaders)
            val request =
                requests.last { it.requestUrl!!.encodedPath.endsWith("/files") }.requestUrl!!
            assertNull(request.queryParameter("gameVersion"))
            assertNull(request.queryParameter("modLoaderType"))
            assertEquals("curseforge:1:12", recommendedContentVersion(details.versions)?.id)
            assertTrue(details.links.any { it.title == "MC 百科搜索" })
        }

    @Test
    fun `custom search filters override instance for CurseForge and can show all versions`() =
        fixture {
            service.search(
                "",
                "mod",
                instance.id,
                filter = ContentFilter("26.3", LoaderType.NeoForge),
            )
            val request =
                requests.last { it.requestUrl!!.encodedPath.endsWith("/search") }.requestUrl!!
            assertEquals("26.3", request.queryParameter("gameVersion"))
            assertEquals("6", request.queryParameter("modLoaderType"))
            service.search("", "mod", instance.id, filter = ContentFilter())
            val unfiltered =
                requests.last { it.requestUrl!!.encodedPath.endsWith("/search") }.requestUrl!!
            assertNull(unfiltered.queryParameter("gameVersion"))
            assertNull(unfiltered.queryParameter("modLoaderType"))
        }

    @Test
    fun `required optional and embedded dependencies use one transactional install`() = fixture {
        val first = service.planContent(hit(), instance.id)
        assertEquals(
            setOf("curseforge:1", "curseforge:2"),
            first.files.map { it.projectId }.toSet(),
        )
        assertEquals(listOf("curseforge:3"), first.optional.map { it.id })
        val plan = service.planContent(hit(), instance.id, setOf("curseforge:3"))
        assertEquals(3, plan.files.size)
        service.installPlan(plan, JobContext(store, "test"))
        val root = store.instancePath(instance.id)
        assertEquals("file12", root.resolve("mods/12.jar").readText())
        assertEquals("file21", root.resolve("mods/21.jar").readText())
        assertEquals("file31", root.resolve("mods/31.jar").readText())
        assertTrue(
            requests
                .filter { it.path!!.startsWith("/files/") }
                .all { it.getHeader("x-api-key") == null }
        )
        assertTrue(readObject(root.resolve(".molifox-content.json")).has("curseforge:1"))
    }

    @Test
    fun `blocked dependencies are all reported without guessing downloads`() = fixture {
        mode = "blocked"
        val error =
            assertFailsWith<ManualContentRequired> {
                service.planContent(hit(), instance.id, setOf("curseforge:3"))
            }
        assertEquals(setOf("21.jar", "31.jar"), error.files.map { it.filename }.toSet())
        assertTrue(
            error.files.all {
                it.website.startsWith("https://www.curseforge.com/minecraft/mc-mods/")
            }
        )
        assertFalse(store.instancePath(instance.id).resolve("mods").exists())
        assertTrue(requests.none { it.path!!.startsWith("/files/") })
    }

    @Test
    fun `missing download URLs also require manual files`() = fixture {
        mode = "missing-url"
        assertEquals(
            "21.jar",
            assertFailsWith<ManualContentRequired> { service.planContent(hit(), instance.id) }
                .files
                .single()
                .filename,
        )
    }

    @Test
    fun `incompatible metadata and malformed hashes are rejected`() = fixture {
        for (value in
            listOf("wrong-version", "wrong-loader", "wrong-game", "server-pack", "bad-hash")) {
            mode = value
            assertFails(value) { service.planContent(hit(), instance.id) }
        }
        assertFalse(store.instancePath(instance.id).resolve("mods").exists())
    }

    @Test
    fun `failed dependency download leaves files and registry untouched`() = fixture {
        val root = store.instancePath(instance.id)
        root.resolve("mods").createDirectories()
        root.resolve("mods/user.jar").writeText("user content")
        mode = "download-fail"
        assertFails {
            service.installPlan(service.planContent(hit(), instance.id), JobContext(store, "test"))
        }
        assertEquals("user content", root.resolve("mods/user.jar").readText())
        assertFalse(root.resolve("mods/12.jar").exists())
        assertFalse(root.resolve(".molifox-content.json").exists())
        assertTrue(root.listDirectoryEntries(".molifox-content-*").isEmpty())
    }

    @Test
    fun `fingerprint match needs sha1 before adoption and update preserves disabled state`() =
        fixture {
            val root = store.instancePath(instance.id)
            val old = root.resolve("mods/old.jar.disabled")
            old.parent.createDirectories()
            old.writeText("file11")
            mode = "false-fingerprint"
            assertNull(service.catalog.checkUpdates(instance.id).items.single().source)
            mode = "normal"
            val report = service.catalog.checkUpdates(instance.id)
            val item = report.items.single()
            assertEquals("curseforge:1:12", item.update!!.id)
            val plan = service.planUpdates(report, setOf(item.key))
            service.installPlan(plan, JobContext(store, "test"))
            assertFalse(old.exists())
            assertEquals("file12", root.resolve("mods/12.jar.disabled").readText())
            assertFalse(root.resolve("mods/12.jar").exists())
        }

    @Test
    fun `key missing and API redirects fail without credential leaks`() = fixture {
        store.mutate { it.copy(settings = it.settings.copy(curseForgeApiKey = "")) }
        assertFails { service.search("", "mod") }
        assertTrue(requests.isEmpty())
        store.mutate { it.copy(settings = it.settings.copy(curseForgeApiKey = "fixture-key")) }
        mode = "redirect"
        val error = assertFails { service.search("", "mod") }
        assertFalse(error.stackTraceToString().contains("fixture-key"))
        assertEquals(1, requests.size)
        assertTrue(requests.none { it.path == "/leak" })
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
                        raw.readBytes()
                            .flatMap {
                                listOf(it, 9.toByte(), 10.toByte(), 13.toByte(), 32.toByte())
                            }
                            .toByteArray()
                    )
                }
            assertEquals(curseForgeFingerprint(raw), curseForgeFingerprint(spaced))
            raw.appendBytes(byteArrayOf(11))
            assertNotEquals(curseForgeFingerprint(raw), curseForgeFingerprint(spaced))
        }

    private fun manifest() =
        gson
            .toJsonTree(
                mapOf(
                    "manifestType" to "minecraftModpack",
                    "manifestVersion" to 1,
                    "name" to "CF Pack",
                    "version" to "1.0",
                    "author" to "Author",
                    "overrides" to "custom-overrides",
                    "minecraft" to
                        mapOf(
                            "version" to "1.20.1",
                            "modLoaders" to
                                listOf(mapOf("id" to "fabric-0.16.14", "primary" to true)),
                        ),
                    "files" to
                        listOf(
                            mapOf("projectID" to 1, "fileID" to 11, "required" to true),
                            mapOf("projectID" to 2, "fileID" to 21, "required" to false),
                        ),
                )
            )
            .asJsonObject

    @Test
    fun `malformed responses mask their body and API keys cannot target other hosts`() = fixture {
        mode = "malformed-response"
        val failure = assertFails { service.search("", "mod") }
        assertFalse(failure.stackTraceToString().contains("fixture-key"))
        assertFalse(failure.stackTraceToString().contains("invalid-json"))
        val count = requests.size
        assertFails { network.curseForgeJson("https://example.com/v1/mods", "fixture-key") }
        assertEquals(count, requests.size)
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
                        .size() <= 50
                )
            }
            assertTrue(
                requests.none {
                    it.path!!.contains("changelog") || it.path!!.contains("description")
                }
            )
        }

    private fun pack(info: JsonObject, entries: Map<String, String> = emptyMap()): Path {
        val archive = temporary.resolve("${UUID.randomUUID()}.zip")
        ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            for ((name, content) in mapOf("manifest.json" to gson.toJson(info)) + entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return archive
    }

    @Test
    fun `CurseForge preview is offline and reads loader optional files and custom overrides`() =
        fixture {
            val archive = pack(manifest(), mapOf("custom-overrides/config/a.txt" to "setting"))
            val before = store.data.instances
            val preview = service.previewPack(archive)
            assertEquals("CF Pack", preview.title)
            assertEquals(LoaderType.Fabric, preview.loader)
            assertEquals("0.16.14", preview.loaderVersion)
            assertEquals(listOf("curseforge:2:21"), preview.optionalFiles)
            assertEquals(1, preview.requiredFiles)
            assertEquals(before, store.data.instances)
            assertTrue(requests.isEmpty())
        }

    @Test
    fun `bad CurseForge manifests fail before network or instance creation`() = fixture {
        val samples =
            listOf(
                manifest().apply { addProperty("manifestVersion", 2) },
                manifest().apply { addProperty("overrides", "../escape") },
                manifest().apply { array("files").add(array("files").first().deepCopy()) },
                manifest().apply { array("files").first().asJsonObject.addProperty("fileID", -1) },
                manifest().apply { array("files").first().asJsonObject.remove("required") },
                manifest().apply {
                    obj("minecraft")
                        .array("modLoaders")
                        .first()
                        .asJsonObject
                        .addProperty("id", "unsupported-1")
                },
            )
        for (sample in samples) assertFails { service.previewPack(pack(sample)) }
        assertFails {
            service.previewPack(
                pack(manifest(), mapOf("custom-overrides/../../escape.txt" to "bad"))
            )
        }
        assertFails {
            service.previewPack(
                pack(manifest(), mapOf("custom-overrides/.molifox-content.json" to "bad"))
            )
        }
        assertEquals(1, store.data.instances.size)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `CurseForge pack staging respects optional selection and commits overrides`() = fixture {
        val root = temporary.resolve("pack-game").apply { createDirectories() }
        val archive = pack(manifest(), mapOf("custom-overrides/config/a.txt" to "setting"))
        val transaction = PackFileTransaction(store, root)
        try {
            ZipFile(archive.toFile()).use { zip ->
                val info = readPackManifest(zip, root, "windows")
                val files =
                    prepareCurseForgePack(
                        info,
                        backend,
                        emptySet(),
                        temporary.resolve("manual-downloads"),
                        JobContext(store, "test"),
                    )
                assertEquals(listOf("mods/11.jar"), files.map { it.path })
                stageCurseForgePackFiles(files, transaction, JobContext(store, "test")) {
                    network.download(it)
                }
                stagePackFiles(
                    info,
                    zip,
                    transaction,
                    root,
                    emptySet(),
                    false,
                    JobContext(store, "test"),
                ) {
                    network.download(it)
                }
            }
            transaction.commit(JobContext(store, "test"))
            assertEquals("file11", root.resolve("mods/11.jar").readText())
            assertEquals("setting", root.resolve("config/a.txt").readText())
            assertFalse(root.resolve("mods/21.jar").exists())
        } finally {
            transaction.cleanup()
        }
    }

    @Test
    fun `manual pack files require exact hashes and are not modified`() = fixture {
        mode = "blocked"
        val archive = pack(manifest())
        val root = temporary.resolve("manual-game").apply { createDirectories() }
        val manual = temporary.resolve("manual-downloads").apply { createDirectories() }
        ZipFile(archive.toFile()).use { zip ->
            val info = readPackManifest(zip, root, "windows")
            assertEquals(
                "21.jar",
                assertFailsWith<ManualContentRequired> {
                        prepareCurseForgePack(
                            info,
                            backend,
                            null,
                            manual,
                            JobContext(store, "test"),
                        )
                    }
                    .files
                    .single()
                    .filename,
            )
            manual.resolve("21.jar").writeText("wrong!")
            assertFailsWith<ManualContentRequired> {
                prepareCurseForgePack(info, backend, null, manual, JobContext(store, "test"))
            }
            manual.resolve("21.jar").writeText("file21")
            val files =
                prepareCurseForgePack(info, backend, null, manual, JobContext(store, "test"))
            val transaction = PackFileTransaction(store, root)
            try {
                stageCurseForgePackFiles(files, transaction, JobContext(store, "test")) {
                    network.download(it)
                }
                transaction.commit(JobContext(store, "test"))
                assertEquals("file21", root.resolve("mods/21.jar").readText())
                assertEquals("file21", manual.resolve("21.jar").readText())
            } finally {
                transaction.cleanup()
            }
        }
        assertTrue(requests.none { it.path == "/files/21" })
    }

    @Test
    fun `missing manual files fail full import before creating a new instance`() = fixture {
        mode = "blocked"
        val archive = pack(manifest())
        val before = store.data.instances
        assertFailsWith<ManualContentRequired> {
            service.importPack(archive, JobContext(store, "test"))
        }
        assertEquals(before, store.data.instances)
        assertFalse(store.dataDirectory.resolve("packs").exists())
        assertTrue(store.cacheDirectory.listDirectoryEntries("pack-import-*").isEmpty())
    }

    @Test
    fun `manual files changed after preparation cannot be committed`() = fixture {
        mode = "blocked"
        val root = temporary.resolve("changed-game").apply { createDirectories() }
        val manual = temporary.resolve("manual-downloads").apply { createDirectories() }
        manual.resolve("21.jar").writeText("file21")
        val archive = pack(manifest())
        ZipFile(archive.toFile()).use { zip ->
            val info = readPackManifest(zip, root, "windows")
            val files =
                prepareCurseForgePack(info, backend, null, manual, JobContext(store, "test"))
            manual.resolve("21.jar").writeText("fileXX")
            val transaction = PackFileTransaction(store, root)
            try {
                assertFails {
                    stageCurseForgePackFiles(files, transaction, JobContext(store, "test")) {
                        network.download(it)
                    }
                }
                assertFalse(root.resolve("mods/11.jar").exists())
                assertFalse(root.resolve("mods/21.jar").exists())
            } finally {
                transaction.cleanup()
            }
        }
    }

    @Test
    fun `CurseForge full import keeps isolation and failed pack can be repaired`() = fixture {
        resourcePack = true
        store.mutate { it.copy(settings = it.settings.copy(isolateVersions = false)) }
        val archive =
            pack(
                manifest().apply {
                    obj("minecraft").add("modLoaders", gson.toJsonTree(emptyList<String>()))
                },
                mapOf("custom-overrides/config/a.txt" to "setting"),
            )
        val before = store.data.instances.size
        mode = "download-fail"
        assertFails { service.importPack(archive, JobContext(store, "test")) }
        val failed = store.data.instances.last()
        assertEquals(before + 1, store.data.instances.size)
        assertEquals(VersionIsolation.Enabled, failed.isolation)
        assertFalse(failed.installed)
        val root = store.instancePath(failed.id)
        assertFalse(root.resolve("resourcepacks/11.zip").exists())
        assertTrue(root.listDirectoryEntries(".molifox-pack-*").isEmpty())
        mode = "normal"
        service.importPack(Path.of(failed.packFile), JobContext(store, "test"), failed.id)
        assertEquals(before + 1, store.data.instances.size)
        assertTrue(store.instance(failed.id).installed)
        assertEquals("file11", root.resolve("resourcepacks/11.zip").readText())
        assertEquals("file21", root.resolve("resourcepacks/21.zip").readText())
        root.resolve("config/a.txt").writeText("user setting")
        service.importPack(Path.of(failed.packFile), JobContext(store, "test"), failed.id)
        assertEquals("user setting", root.resolve("config/a.txt").readText())
    }

    @Test
    fun `repair reuses hash verified existing restricted files`() = fixture {
        mode = "blocked"
        val root = temporary.resolve("repair-game").apply { resolve("mods").createDirectories() }
        root.resolve("mods/21.jar").writeText("file21")
        val archive = pack(manifest())
        ZipFile(archive.toFile()).use { zip ->
            val info = readPackManifest(zip, root, "windows")
            val files =
                prepareCurseForgePack(
                    info,
                    backend,
                    null,
                    temporary.resolve("missing-manual"),
                    JobContext(store, "test"),
                    root,
                )
            assertEquals(
                root.resolve("mods/21.jar"),
                files.find { it.path == "mods/21.jar" }!!.local,
            )
        }
    }
}
