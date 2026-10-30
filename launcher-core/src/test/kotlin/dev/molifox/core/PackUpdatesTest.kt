// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证资源包与光影更新检查的识别顺序与游戏版本过滤规则。

package dev.molifox.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PackUpdatesTest {
    private val temporary: Path = workTestDirectory("launcher-core-pack-updates")

    @AfterEach
    fun cleanUp() {
        removeTestTree(temporary)
    }

    private fun store(): LauncherStore {
        val platform =
            object : PlatformServices by DesktopPlatform() {
                override val defaultDataDirectory =
                    temporary.resolve(
                        "profile-${UUID.randomUUID()}",
                    )

                override fun moveToTrash(file: Path) {
                    val target = temporary.resolve("trash/${UUID.randomUUID()}-${file.fileName}")
                    target.parent.createDirectories()
                    Files.move(file, target)
                }
            }
        return LauncherStore(platform)
    }

    private fun instance(
        store: LauncherStore,
        gameVersion: String = "1.20.1",
    ): GameInstance {
        val instance =
            GameInstance(
                name = "Packs",
                gameVersion = gameVersion,
                versionId = "fixture",
                loader = LoaderType.Fabric,
                installed = true,
                isolation = VersionIsolation.Enabled,
            )
        store.mutate { it.copy(instances = listOf(instance)) }
        store.instancePath(instance.id).createDirectories()
        return store.instance(instance.id)
    }

    private fun zip(path: Path): Path =
        path.also { target ->
            target.parent.createDirectories()
            ZipOutputStream(target.outputStream()).use { archive ->
                archive.putNextEntry(ZipEntry("pack.mcmeta"))
                archive.write("{}".toByteArray())
                archive.closeEntry()
            }
        }

    private fun project(
        id: String,
        title: String = id,
    ) = RemoteModProject(id, id, title, "modrinth")

    private fun version(
        project: RemoteModProject,
        number: String,
        gameVersions: List<String> = listOf("1.20.1"),
        filename: String = "${project.slug}-$number.zip",
        versionType: String = "release",
    ) = RemoteModVersion(
        project,
        "${project.id}:$number",
        number,
        "${project.title} $number",
        gameVersions,
        emptyList(),
        versionType,
        "https://example.invalid/${project.slug}/$number.zip",
        filename,
        1024,
        "",
        "",
    )

    private class FakeSource(
        override val provider: String = "modrinth",
        private val fingerprints: Map<String, RemoteModVersion> = emptyMap(),
        private val searchHits: List<RemoteModProject> = emptyList(),
        private val versionMap: Map<String, List<RemoteModVersion>> = emptyMap(),
        /** 非空表示该源支持批量最新版本查询：按文件名返回结算结果。 */
        private val batchLatest: Map<String, RemoteModVersion>? = null,
    ) : PackUpdateSource {
        var batchCalls = 0
            private set
        var versionCalls = 0
            private set

        override suspend fun fingerprint(
            files: List<LocalPackFile>,
            folder: PackFolder,
        ) = files
            .mapNotNull { file ->
                fingerprints[file.filename]?.let { file.filename to it }
            }.toMap()

        override suspend fun latestVersions(
            files: List<LocalPackFile>,
            folder: PackFolder,
            instance: GameInstance,
            allowPrerelease: Boolean,
        ): Map<String, RemoteModVersion>? {
            val known = batchLatest ?: return null
            batchCalls++
            return files
                .mapNotNull { file ->
                    known[file.filename]?.let { file.filename to it }
                }.toMap()
        }

        override suspend fun search(
            name: String,
            folder: PackFolder,
            instance: GameInstance,
        ) = searchHits

        override suspend fun versions(
            project: RemoteModProject,
            folder: PackFolder,
            instance: GameInstance,
        ): List<RemoteModVersion> {
            versionCalls++
            return versionMap[project.id].orEmpty()
        }
    }

    @Test
    fun `game version rules differ between resource packs and shaders`() {
        // 资源包必须声明当前游戏版本。
        assertTrue(packVersionMatches(PackFolder.ResourcePacks, listOf("1.20.1"), "1.20.1"))
        assertFalse(packVersionMatches(PackFolder.ResourcePacks, listOf("1.19.2"), "1.20.1"))
        assertFalse(packVersionMatches(PackFolder.ResourcePacks, emptyList(), "1.20.1"))
        // 光影不声明版本时按「不限版本」接受，声明了别的版本仍然拒绝。
        assertTrue(packVersionMatches(PackFolder.ShaderPacks, emptyList(), "1.20.1"))
        assertTrue(packVersionMatches(PackFolder.ShaderPacks, listOf("1.20.1"), "1.20.1"))
        assertFalse(packVersionMatches(PackFolder.ShaderPacks, listOf("1.19.2"), "1.20.1"))
        // 实例没有版本号时不做过滤。
        assertTrue(packVersionMatches(PackFolder.ResourcePacks, emptyList(), ""))
    }

    @Test
    fun `resource pack update ignores candidates for other minecraft versions`() =
        runBlocking {
            val store = store()
            val instance = instance(store)
            zip(store.instancePath(instance.id).resolve("resourcepacks/FoxPack-1.0.zip"))
            val fox = project("fox")
            val current = version(fox, "1.0", filename = "FoxPack-1.0.zip")
            val report =
                PackUpdateService(
                    store,
                    listOf(
                        FakeSource(
                            fingerprints = mapOf("FoxPack-1.0.zip" to current),
                            versionMap =
                                mapOf(
                                    fox.id to
                                        listOf(
                                            current,
                                            version(fox, "1.1", gameVersions = listOf("1.19.2")),
                                        ),
                                ),
                        ),
                    ),
                ).check(instance.id)
            val item = report.items.single()
            assertEquals(PackUpdateStatus.Latest, item.status)
            assertEquals(PackMatchSource.Fingerprint, item.matchSource)
            assertEquals("1.0", item.latestVersion)
        }

    @Test
    fun `shader update accepts versions without a minecraft constraint`() =
        runBlocking {
            val store = store()
            val instance = instance(store)
            zip(store.instancePath(instance.id).resolve("shaderpacks/FoxShader.zip"))
            val shader = project("foxshader")
            val current = version(shader, "1.0", gameVersions = emptyList())
            val report =
                PackUpdateService(
                    store,
                    listOf(
                        FakeSource(
                            fingerprints = mapOf("FoxShader.zip" to current),
                            versionMap =
                                mapOf(
                                    shader.id to
                                        listOf(
                                            current,
                                            version(shader, "2.0", gameVersions = emptyList()),
                                        ),
                                ),
                        ),
                    ),
                ).check(instance.id)
            val item = report.items.single()
            assertEquals(PackFolder.ShaderPacks, item.folder)
            assertEquals(PackUpdateStatus.Updatable, item.status)
            assertEquals("2.0", item.latestVersion)
            assertNotNull(item.candidate)
            assertEquals(1, report.updatable(PackFolder.ShaderPacks))
        }

    @Test
    fun `shader candidates declaring another version are skipped`() =
        runBlocking {
            val store = store()
            val instance = instance(store)
            zip(store.instancePath(instance.id).resolve("shaderpacks/FoxShader.zip"))
            val shader = project("foxshader")
            val current = version(shader, "1.0", gameVersions = listOf("1.19.2"))
            val report =
                PackUpdateService(
                    store,
                    listOf(
                        FakeSource(
                            fingerprints = mapOf("FoxShader.zip" to current),
                            versionMap =
                                mapOf(
                                    shader.id to
                                        listOf(
                                            current,
                                            version(shader, "2.0", gameVersions = listOf("1.19.2")),
                                        ),
                                ),
                        ),
                    ),
                ).check(instance.id)
            assertEquals(PackUpdateStatus.Unknown, report.items.single().status)
            assertTrue(
                report.items
                    .single()
                    .warning
                    .contains("当前游戏版本"),
            )
        }

    @Test
    fun `name fallback matches a single project and uses the same file name as current version`() =
        runBlocking {
            val store = store()
            val instance = instance(store)
            zip(store.instancePath(instance.id).resolve("resourcepacks/Fox Pack 1.0.zip"))
            val fox = RemoteModProject("fox", "fox-pack", "Fox Pack", "modrinth")
            val report =
                PackUpdateService(
                    store,
                    listOf(
                        FakeSource(
                            searchHits = listOf(fox),
                            versionMap =
                                mapOf(
                                    fox.id to
                                        listOf(
                                            version(fox, "1.0", filename = "Fox Pack 1.0.zip"),
                                            version(fox, "1.1"),
                                        ),
                                ),
                        ),
                    ),
                ).check(instance.id)
            val item = report.items.single()
            assertEquals(PackMatchSource.Name, item.matchSource)
            assertEquals(PackUpdateStatus.Updatable, item.status)
            assertEquals("1.0", item.currentVersion)
            assertEquals("1.1", item.latestVersion)
        }

    @Test
    fun `no matching project is reported as no update found`() =
        runBlocking {
            val store = store()
            val instance = instance(store)
            zip(store.instancePath(instance.id).resolve("resourcepacks/Unknown Pack.zip"))
            val report = PackUpdateService(store, listOf(FakeSource())).check(instance.id)
            val item = report.items.single()
            assertEquals(PackUpdateStatus.Unknown, item.status)
            assertTrue(item.warning.contains("未收录或名称无法匹配"))
            assertEquals(1, report.unknown(PackFolder.ResourcePacks))
        }

    @Test
    fun `folder packs are listed but only name matched`() =
        runBlocking {
            val store = store()
            val instance = instance(store)
            val folder = store.instancePath(instance.id).resolve("shaderpacks/Shader Folder")
            folder.createDirectories()
            folder.resolve("shaders").createDirectories()
            val report = PackUpdateService(store, listOf(FakeSource())).check(instance.id)
            assertEquals("Shader Folder", report.items.single().filename)
        }

    @Test
    fun `batch latest avoids per project version requests`() =
        runBlocking {
            val store = store()
            val instance = instance(store)
            zip(store.instancePath(instance.id).resolve("resourcepacks/FoxPack-1.0.zip"))
            val fox = project("fox")
            val current = version(fox, "1.0", filename = "FoxPack-1.0.zip")
            val latest = version(fox, "1.1")
            val source =
                FakeSource(
                    fingerprints = mapOf("FoxPack-1.0.zip" to current),
                    batchLatest = mapOf("FoxPack-1.0.zip" to latest),
                )
            val item = PackUpdateService(store, listOf(source)).check(instance.id).items.single()
            assertEquals(PackUpdateStatus.Updatable, item.status)
            assertEquals("1.1", item.latestVersion)
            assertEquals(1, source.batchCalls)
            assertEquals(0, source.versionCalls)
        }

    @Test
    fun `pack fingerprint and latest versions use one batch request each`() =
        runBlocking {
            val store = store()
            val instance = instance(store)
            val root = store.instancePath(instance.id)
            val installed = installedPacks(root)
            val current = packVersionIndex(installed, "1.0") { it }
            val latest = packVersionIndex(installed, "1.1") { it.replace("1.0", "1.1") }
            val kinds = packKinds(installed)
            val requests = mutableListOf<String>()
            MockWebServer().use { server ->
                server.dispatcher = packUpdateDispatcher(requests, current, latest, kinds)
                server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
                val report =
                    PackUpdateService(
                        store,
                        listOf(
                            ModrinthPackUpdateSource(
                                NetworkClient(),
                                "http://127.0.0.1:${server.port}/v2",
                            ),
                        ),
                    ).check(instance.id)
                assertEquals(4, report.items.size)
                assertEquals(4, report.updatable(PackFolder.ResourcePacks))
                assertEquals(1, requests.count { it == "/v2/version_files" })
                assertEquals(1, requests.count { it == "/v2/version_files/update" })
                assertEquals(1, requests.count { it == "/v2/projects" })
                assertTrue(requests.none { it.startsWith("/v2/project/") })
            }
        }

    private fun installedPacks(root: Path): List<Pair<String, String>> =
        (0 until 4).map { index ->
            val name = "FoxPack-$index-1.0.zip"
            val file = zip(root.resolve("resourcepacks/$name"))
            name to sha512(file.readBytes())
        }

    private fun packVersionIndex(
        installed: List<Pair<String, String>>,
        number: String,
        filename: (String) -> String,
    ): JsonObject =
        JsonObject().apply {
            installed.forEachIndexed { index, (name, hash) ->
                add(hash, packVersionJson("pack$index", number, filename(name), listOf("1.20.1")))
            }
        }

    private fun packKinds(installed: List<Pair<String, String>>): JsonArray =
        JsonArray().apply {
            installed.forEachIndexed { index, _ ->
                add(
                    JsonObject().apply {
                        addProperty("id", "pack$index")
                        addProperty("project_type", "resourcepack")
                    },
                )
            }
        }

    private fun packUpdateDispatcher(
        requests: MutableList<String>,
        current: JsonObject,
        latest: JsonObject,
        kinds: JsonArray,
    ): Dispatcher =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                requests += path
                val body: Any =
                    when (path) {
                        "/v2/version_files" -> current
                        "/v2/version_files/update" -> latest
                        "/v2/projects" -> kinds
                        else -> JsonObject()
                    }
                return MockResponse()
                    .setBody(body.toString())
                    .setHeader("Content-Type", "application/json")
            }
        }

    private fun sha512(bytes: ByteArray): String =
        java.security.MessageDigest
            .getInstance("SHA-512")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun packVersionJson(
        project: String,
        number: String,
        filename: String,
        gameVersions: List<String>,
    ) = JsonObject().apply {
        addProperty("id", "v-$project-$number")
        addProperty("project_id", project)
        addProperty("version_number", number)
        addProperty("name", "$project $number")
        addProperty("version_type", "release")
        add("game_versions", JsonArray().apply { gameVersions.forEach { add(it) } })
        add(
            "files",
            JsonArray().apply {
                add(
                    JsonObject().apply {
                        addProperty("primary", true)
                        addProperty("url", "https://cdn.example/$filename")
                        addProperty("filename", filename)
                        addProperty("size", 1024L)
                        add(
                            "hashes",
                            JsonObject().apply {
                                addProperty("sha512", "0".repeat(128))
                                addProperty("sha1", "0".repeat(40))
                            },
                        )
                    },
                )
            },
        )
    }
}
