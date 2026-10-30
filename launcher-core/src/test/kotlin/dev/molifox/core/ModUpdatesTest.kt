// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证模组更新识别、版本比较与安全替换的行为与边界。

package dev.molifox.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes
import kotlin.io.path.reader
import kotlin.io.path.writeBytes
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModUpdatesTest {
    @TempDir lateinit var temporary: Path

    private fun jar(
        path: Path,
        entries: Map<String, String>,
    ): Path =
        path.also { target ->
            target.parent.createDirectories()
            ZipOutputStream(target.outputStream()).use { zip ->
                entries.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
            }
        }

    private fun jarBytes(version: String): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            zip.putNextEntry(ZipEntry("fabric.mod.json"))
            zip.write("""{"id":"fox","name":"Fox","version":"$version"}""".toByteArray())
            zip.closeEntry()
        }
        return buffer.toByteArray()
    }

    private fun sha512(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-512").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun project(
        id: String,
        title: String = id,
        provider: String = "modrinth",
    ) = RemoteModProject(id, id, title, provider)

    private fun version(
        project: RemoteModProject,
        number: String,
        bytes: ByteArray,
        gameVersions: List<String> = listOf("1.20.1"),
        loaders: List<String> = listOf("fabric"),
        filename: String = "new-$number.jar",
        versionType: String = "release",
    ) = RemoteModVersion(
        project,
        "${project.id}-$number",
        number,
        "Fox $number",
        gameVersions,
        loaders,
        versionType,
        "https://cdn.modrinth.com/data/fox/versions/$number.jar",
        filename,
        bytes.size.toLong(),
        sha512(bytes),
        "",
    )

    private class FakeSource(
        override val provider: String,
        private val fingerprints: Map<String, RemoteModVersion> = emptyMap(),
        private val projects: Map<String, RemoteModProject> = emptyMap(),
        private val searchHits: List<RemoteModProject> = emptyList(),
        private val versionMap: Map<String, List<RemoteModVersion>> = emptyMap(),
        /** 非空表示该源支持批量最新版本查询：按文件名返回结算结果。 */
        private val batchLatest: Map<String, RemoteModVersion>? = null,
    ) : ModUpdateSource {
        val searchCalls = mutableListOf<String>()
        var batchCalls = 0
            private set
        var versionCalls = 0
            private set

        override suspend fun fingerprint(files: List<LocalModFile>) =
            files
                .mapNotNull { file ->
                    fingerprints[file.filename]?.let { file.filename to it }
                }.toMap()

        override suspend fun latestVersions(
            files: List<LocalModFile>,
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

        override suspend fun projectBySlug(
            slug: String,
            instance: GameInstance,
        ) = projects[slug]

        override suspend fun search(
            name: String,
            instance: GameInstance,
        ): List<RemoteModProject> {
            searchCalls += name
            return searchHits
        }

        override suspend fun versions(
            project: RemoteModProject,
            instance: GameInstance,
        ): List<RemoteModVersion> {
            versionCalls++
            return versionMap[project.id].orEmpty()
        }
    }

    private fun fixture(
        loader: LoaderType = LoaderType.Fabric,
        gameVersion: String = "1.20.1",
    ): Triple<LauncherStore, GameInstance, Path> {
        val platform =
            object : PlatformServices by DesktopPlatform() {
                override val defaultDataDirectory = temporary.resolve("profile")

                override fun moveToTrash(file: Path) {
                    val target = temporary.resolve("trash/${UUID.randomUUID()}-${file.fileName}")
                    target.parent.createDirectories()
                    Files.move(file, target)
                }
            }
        val store = LauncherStore(platform)
        val instance =
            GameInstance(
                name = "Mods",
                gameVersion = gameVersion,
                versionId = "fixture",
                loader = loader,
                installed = true,
            )
        store.mutate { it.copy(instances = listOf(instance)) }
        val root = store.instancePath(instance.id)
        root.resolve("mods").createDirectories()
        return Triple(store, instance, root)
    }

    @Test
    fun `version comparison handles numeric segments and prereleases`() {
        assertTrue(compareModVersions("1.2", "1.10") < 0)
        assertEquals(0, compareModVersions("1.2.0", "1.2"))
        assertEquals(0, compareModVersions("v1.3", "1.3.0"))
        assertEquals(0, compareModVersions("1.2.0+build.5", "1.2.0"))
        assertTrue(compareModVersions("2.0.0-beta", "2.0.0") < 0)
        assertTrue(compareModVersions("2.0.0-beta.2", "2.0.0-beta.10") < 0)
        assertTrue(compareModVersions("1.0.0-rc.1", "1.0.0-beta.9") > 0)
        assertTrue(compareModVersions("1.0.0-alpha", "1.0.0-1") > 0)
        assertTrue(isPrereleaseVersion("1.0.0-beta.2"))
        assertFalse(isPrereleaseVersion("1.0.0+build-1"))
        assertTrue(minecraftConstraintMatches(">=1.20 <1.21", "1.20.1"))
        assertFalse(minecraftConstraintMatches(">=1.20 <1.21", "1.21"))
        assertTrue(minecraftConstraintMatches("[1.20,1.21)", "1.20.1"))
        assertFalse(minecraftConstraintMatches("[1.20,1.21)", "1.21"))
        assertTrue(minecraftConstraintMatches("1.20.x", "1.20.4"))
        assertFalse(minecraftConstraintMatches("1.20.x", "1.21"))
        // CurseForge 的 displayName 混有项目名/游戏版本，比较前要抽出真正的版本段。
        assertEquals("0.92.3+1.20.1", normalizedRemoteVersionNumber("Fabric API 0.92.3+1.20.1"))
        assertEquals("15.2.0.27", normalizedRemoteVersionNumber("JEI 15.2.0.27 for 1.20.1"))
        assertEquals("v11.1.118", normalizedRemoteVersionNumber("Cloth Config v11.1.118"))
    }

    @Test
    fun `metadata constraints and project references parse for all three formats`() {
        val reader = ModMetadataReader()
        val fabric =
            reader.read(
                jar(
                    temporary.resolve("fabric.jar"),
                    mapOf(
                        "fabric.mod.json" to
                            """{"id":"fox","name":"Fox","version":"1.2.0","depends":{"minecraft":">=1.20 <1.21"},"contact":{"homepage":"https://modrinth.com/mod/fox"}}""",
                    ),
                ),
            )
        assertEquals("Fabric", fabric.loader)
        assertEquals(listOf("fabric"), fabric.loaderKeys)
        assertEquals(">=1.20 <1.21", fabric.entries.single().minecraftConstraint)
        assertEquals(
            listOf(ModProjectRef("modrinth", "fox")),
            fabric.entries.single().projectRefs,
        )
        val forge =
            reader.read(
                jar(
                    temporary.resolve("forge.jar"),
                    mapOf(
                        "META-INF/mods.toml" to
                            """
                            modLoader="javafml"
                            [[mods]]
                            modId="forgefox"
                            displayName="Forge Fox"
                            version="2.0"
                            displayURL="https://www.curseforge.com/minecraft/mc-mods/forge-fox"
                            [[dependencies.forgefox]]
                            modId="minecraft"
                            versionRange="[1.20,1.21)"
                            """.trimIndent(),
                    ),
                ),
            )
        assertEquals("Forge", forge.loader)
        assertEquals("[1.20,1.21)", forge.entries.single().minecraftConstraint)
        assertEquals(
            listOf(ModProjectRef("curseforge", "forge-fox")),
            forge.entries.single().projectRefs,
        )
        val legacy =
            reader.read(
                jar(
                    temporary.resolve("legacy.jar"),
                    mapOf(
                        "mcmod.info" to
                            """[{"modid":"oldfox","name":"Old Fox","version":"1","mcversion":"1.20.1","url":"https://www.curseforge.com/minecraft/mc-mods/old-fox"}]""",
                    ),
                ),
            )
        assertEquals("1.20.1", legacy.entries.single().minecraftConstraint)
        assertEquals(
            listOf(ModProjectRef("curseforge", "old-fox")),
            legacy.entries.single().projectRefs,
        )
    }

    @Test
    fun `fingerprint match wins over name search`() =
        runBlocking {
            val (store, instance, root) = fixture()
            root
                .resolve("mods/fox.jar")
                .also {
                    jar(
                        it,
                        mapOf(
                            "fabric.mod.json" to
                                """{"id":"fox","name":"Fox","version":"1.0.0"}""",
                        ),
                    )
                }
            val matched = project("matched", "Fox")
            val named = project("named", "Fox")
            val current = version(matched, "1.0.0", jarBytes("1.0.0"))
            val newer = version(matched, "1.1.0", jarBytes("1.1.0"))
            val source =
                FakeSource(
                    "modrinth",
                    fingerprints = mapOf("fox.jar" to current),
                    searchHits = listOf(named),
                    versionMap =
                        mapOf(
                            matched.id to listOf(current, newer),
                            named.id to listOf(version(named, "2.0.0", jarBytes("2.0.0"))),
                        ),
                )
            val service =
                ModUpdateService(
                    store,
                    ModMetadataReader(),
                    listOf(source),
                    { _, target, _ -> target.writeBytes(jarBytes("1.1.0")) },
                )
            val item = service.check(instance.id).items.single()
            assertEquals(ModMatchSource.Fingerprint, item.matchSource)
            assertEquals("1.1.0", item.candidate?.versionNumber)
            assertEquals(matched.id, item.candidate?.projectId)
            assertTrue(source.searchCalls.isEmpty())
        }

    @Test
    fun `only same game version and loader versions are offered`() =
        runBlocking {
            val (store, instance, root) = fixture()
            jar(
                root.resolve("mods/fox.jar"),
                mapOf(
                    "fabric.mod.json" to """{"id":"fox","name":"Fox","version":"1.0.0"}""",
                ),
            )
            val remote = project("fox")
            val current = version(remote, "1.0.0", jarBytes("1.0.0"))
            val wrongGame =
                version(remote, "2.0.0", jarBytes("2.0.0"), gameVersions = listOf("1.21"))
            val wrongLoader =
                version(remote, "3.0.0", jarBytes("3.0.0"), loaders = listOf("forge"))
            val source =
                FakeSource(
                    "modrinth",
                    fingerprints = mapOf("fox.jar" to current),
                    versionMap = mapOf(remote.id to listOf(current, wrongGame, wrongLoader)),
                )
            val service =
                ModUpdateService(
                    store,
                    ModMetadataReader(),
                    listOf(source),
                    { _, target, _ -> target.writeBytes(jarBytes("9.9.9")) },
                )
            val item = service.check(instance.id).items.single()
            assertEquals(ModUpdateStatus.Latest, item.status)
            assertNull(item.candidate)

            val compatible = version(remote, "1.1.0", jarBytes("1.1.0"))
            val updatedSource =
                FakeSource(
                    "modrinth",
                    fingerprints = mapOf("fox.jar" to current),
                    versionMap =
                        mapOf(
                            remote.id to listOf(current, wrongGame, wrongLoader, compatible),
                        ),
                )
            val updated =
                ModUpdateService(
                    store,
                    ModMetadataReader(),
                    listOf(updatedSource),
                    { _, target, _ -> target.writeBytes(jarBytes("1.1.0")) },
                )
            assertEquals(
                "1.1.0",
                updated
                    .check(instance.id)
                    .items
                    .single()
                    .candidate
                    ?.versionNumber,
            )
        }

    @Test
    fun `ambiguous name match is flagged and not applied`() =
        runBlocking {
            val (store, instance, root) = fixture()
            val old =
                jar(
                    root.resolve("mods/fox.jar"),
                    mapOf(
                        "fabric.mod.json" to """{"id":"fox","name":"Fox","version":"1.0.0"}""",
                    ),
                )
            val original = old.readBytes()
            val first = project("first", "Fox")
            val second = project("second", "Fox")
            val source =
                FakeSource(
                    "modrinth",
                    searchHits = listOf(first, second),
                    versionMap =
                        mapOf(
                            first.id to listOf(version(first, "2.0.0", jarBytes("2.0.0"))),
                            second.id to listOf(version(second, "2.0.0", jarBytes("2.0.0"))),
                        ),
                )
            val service =
                ModUpdateService(
                    store,
                    ModMetadataReader(),
                    listOf(source),
                    { _, target, _ -> target.writeBytes(jarBytes("2.0.0")) },
                )
            val item = service.check(instance.id).items.single()
            assertEquals(ModUpdateStatus.Unknown, item.status)
            assertTrue(item.possibleMatch)
            assertTrue(item.warning.contains("可能匹配"))
            assertNull(item.candidate)
            assertFalse(item.applicable)
            assertTrue(runCatching { service.apply(instance.id, item, null) }.isFailure)
            assertContentEquals(original, old.readBytes())
        }

    @Test
    fun `unique name match is possible and explicitly applicable`() =
        runBlocking {
            val (store, instance, root) = fixture()
            jar(
                root.resolve("mods/fox.jar"),
                mapOf(
                    "fabric.mod.json" to """{"id":"fox","name":"Fox","version":"1.0.0"}""",
                ),
            )
            val remote = project("fox", "Fox")
            val latest = version(remote, "1.1.0", jarBytes("1.1.0"))
            val source =
                FakeSource(
                    "modrinth",
                    searchHits = listOf(remote),
                    versionMap = mapOf(remote.id to listOf(latest)),
                )
            val service =
                ModUpdateService(
                    store,
                    ModMetadataReader(),
                    listOf(source),
                    { _, target, _ -> target.writeBytes(jarBytes("1.1.0")) },
                )
            val item = service.check(instance.id).items.single()
            assertEquals(ModUpdateStatus.Updatable, item.status)
            assertEquals(ModMatchSource.Name, item.matchSource)
            assertTrue(item.possibleMatch)
            assertTrue(item.applicable)
            assertTrue(item.warning.contains("可能不是同一项目"))
        }

    @Test
    fun `update replaces file and preserves disabled state`() =
        runBlocking {
            val (store, instance, root) = fixture()
            val old =
                jar(
                    root.resolve("mods/fox.jar.disabled"),
                    mapOf(
                        "fabric.mod.json" to """{"id":"fox","name":"Fox","version":"1.0.0"}""",
                    ),
                )
            val oldBytes = old.readBytes()
            val remote = project("fox")
            val current = version(remote, "1.0.0", jarBytes("1.0.0"))
            val newBytes = jarBytes("1.1.0")
            val latest = version(remote, "1.1.0", newBytes, filename = "fox-1.1.0.jar")
            val source =
                FakeSource(
                    "modrinth",
                    fingerprints = mapOf("fox.jar.disabled" to current),
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
            assertFalse(old.exists())
            val replacement = root.resolve("mods/fox-1.1.0.jar.disabled")
            assertTrue(replacement.exists())
            assertContentEquals(newBytes, replacement.readBytes())
            assertFalse(root.resolve("mods/fox-1.1.0.jar").exists())
            assertTrue(
                store.state.value.logs.any {
                    it.message.contains(
                        "更新模组：Fox 1.0.0 → 1.1.0",
                    )
                },
            )
            val trashed =
                temporary.resolve("trash").listDirectoryEntries().firstOrNull {
                    it.readBytes().contentEquals(oldBytes)
                }
            assertNotNull(trashed)
            Unit
        }

    @Test
    fun `failed download keeps the original jar`() =
        runBlocking {
            val (store, instance, root) = fixture()
            val old =
                jar(
                    root.resolve("mods/fox.jar"),
                    mapOf(
                        "fabric.mod.json" to """{"id":"fox","name":"Fox","version":"1.0.0"}""",
                    ),
                )
            val original = old.readBytes()
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
            assertTrue(
                runCatching {
                    service.apply(
                        instance.id,
                        item,
                        null,
                    )
                }.exceptionOrNull() is IOException,
            )
            assertTrue(old.exists())
            assertContentEquals(original, old.readBytes())
            assertFalse(root.resolve("mods/fox-1.1.0.jar").exists())
            assertTrue(
                root.resolve("mods").listDirectoryEntries().none {
                    it.name.startsWith(".molifox-update-")
                },
            )
        }

    @Test
    fun `fingerprint matched mods use one batch request per check and reuse hashes`() =
        runBlocking {
            val (store, instance, root) = fixture()
            val installed =
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
            val hashes = installed.associate { (name, bytes) -> name to sha512(bytes) }
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
            val requests = mutableListOf<String>()
            MockWebServer().use { server ->
                server.dispatcher =
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
                server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
                var hashCalls = 0
                val service =
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
                            hashCalls++
                            digest(path, algorithm)
                        },
                    )
                val first = service.check(instance.id)
                assertEquals(6, first.items.size)
                assertEquals(6, first.updates)
                assertTrue(first.items.all { it.candidate?.versionNumber == "1.1.0" })
                // 6 个文件 = 1 次指纹 + 1 次批量版本，没有逐项目版本请求。
                assertEquals(1, requests.count { it == "/v2/version_files" })
                assertEquals(1, requests.count { it == "/v2/version_files/update" })
                assertTrue(requests.none { it.startsWith("/v2/project/") })
                assertEquals(6, hashCalls)

                // 再检查一次：结果一致，摘要与元数据全部命中缓存，不会重新读 jar。
                requests.clear()
                val again = service.check(instance.id)
                assertEquals(6, again.items.size)
                assertEquals(6, hashCalls)
                assertEquals(2, requests.size)
                assertEquals(1, requests.count { it == "/v2/version_files/update" })
            }
        }

    private fun modrinthVersion(
        project: String,
        number: String,
        filename: String,
    ): JsonObject =
        JsonObject().apply {
            addProperty("id", "v-$project-$number")
            addProperty("project_id", project)
            addProperty("version_number", number)
            addProperty("name", "$project $number")
            addProperty("version_type", "release")
            add("game_versions", JsonArray().apply { add("1.20.1") })
            add("loaders", JsonArray().apply { add("fabric") })
            add(
                "files",
                JsonArray().apply {
                    add(
                        JsonObject().apply {
                            addProperty("primary", true)
                            addProperty("url", "https://cdn.example/$filename")
                            addProperty("filename", filename)
                            addProperty("size", 1L)
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
