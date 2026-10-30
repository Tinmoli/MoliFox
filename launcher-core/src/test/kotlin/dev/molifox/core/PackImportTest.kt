// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证多格式整合包导入的行为与边界。

package dev.molifox.core

import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackImportTest {
    @TempDir lateinit var temporary: Path
    private val bytes = "downloaded pack content".toByteArray()

    private fun hash(
        data: ByteArray,
        algorithm: String,
    ) = MessageDigest.getInstance(algorithm).digest(data).joinToString("") { "%02x".format(it) }

    private fun file(
        path: String,
        environment: String = "required",
    ): JsonObject =
        gson
            .toJsonTree(
                mapOf(
                    "path" to path,
                    "hashes" to
                        mapOf("sha1" to hash(bytes, "SHA-1"), "sha512" to hash(bytes, "SHA-512")),
                    "fileSize" to bytes.size,
                    "env" to mapOf("client" to environment, "server" to "required"),
                    "downloads" to listOf("https://cdn.modrinth.com/$path"),
                ),
            ).asJsonObject

    private fun index(files: List<JsonObject> = emptyList()): JsonObject =
        gson
            .toJsonTree(
                mapOf(
                    "formatVersion" to 1,
                    "game" to "minecraft",
                    "versionId" to "1.0",
                    "name" to "Fixture pack",
                    "summary" to "Pack preview",
                    "dependencies" to mapOf("minecraft" to "fixture"),
                    "files" to files,
                ),
            ).asJsonObject

    private fun zipBytes(entries: Map<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, data) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(data)
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }

    private fun pack(
        index: JsonObject,
        extras: Map<String, ByteArray> = emptyMap(),
    ): Path =
        temporary.resolve("${UUID.randomUUID()}.mrpack").apply {
            writeBytes(
                zipBytes(mapOf("modrinth.index.json" to gson.toJson(index).toByteArray()) + extras),
            )
        }

    private fun store(): LauncherStore =
        LauncherStore(
            object : PlatformServices by DesktopPlatform() {
                override fun moveToTrash(file: Path) {
                    val destination = temporary.resolve("trash/${UUID.randomUUID()}")
                    destination.parent.createDirectories()
                    Files.move(file, destination)
                }
            },
            temporary.resolve("profile"),
        )

    @Test
    fun `preview lists client optional files without creating an instance`() =
        runBlocking {
            val store = store()
            NetworkClient().use { network ->
                val service =
                    ContentService(
                        store,
                        network,
                        MinecraftInstaller(store, network, JavaService(store, network)),
                    )
                val source =
                    pack(
                        index(
                            listOf(
                                file("mods/a.jar"),
                                file("mods/b.jar", "optional"),
                                file("mods/c.jar", "unsupported"),
                            ),
                        ),
                    )
                val preview = service.previewPack(source)
                assertEquals(listOf("mods/b.jar"), preview.optionalFiles)
                assertEquals(1, preview.requiredFiles)
                assertEquals("fixture", preview.gameVersion)
                assertEquals(64, preview.hash.length)
                assertTrue(store.data.instances.isEmpty())
                assertTrue(store.cacheDirectory.listDirectoryEntries("pack-import-*").isEmpty())
            }
        }

    @Test
    fun `bad manifests and overrides fail before creating an instance`() =
        runBlocking {
            val store = store()
            NetworkClient().use { network ->
                val service =
                    ContentService(
                        store,
                        network,
                        MinecraftInstaller(store, network, JavaService(store, network)),
                    )
                val missingHash = file("a.txt").apply { obj("hashes").remove("sha512") }
                val badSize = file("a.txt").apply { addProperty("fileSize", -1) }
                val badEnv = file("a.txt").apply { obj("env").addProperty("client", "invalid") }
                val samples =
                    listOf(
                        pack(index().apply { addProperty("formatVersion", 1.5) }),
                        pack(index(listOf(missingHash))),
                        pack(index(listOf(badSize))),
                        pack(index(listOf(badEnv))),
                        pack(index(listOf(file("same.txt"), file("same.txt")))),
                        pack(index(listOf(file("config"), file("config/child.txt")))),
                        pack(index(), mapOf("overrides/../../escape.txt" to bytes)),
                        pack(index(), mapOf("client-overrides/.MOLIFOX-content.json" to bytes)),
                    )
                for (source in samples) {
                    assertFails { service.previewPack(source) }
                    assertFails { service.importPack(source, JobContext(store, "test")) }
                    assertTrue(store.data.instances.isEmpty())
                }
                assertTrue(store.cacheDirectory.listDirectoryEntries("pack-import-*").isEmpty())
                assertFalse(temporary.resolve("escape.txt").exists())
            }
        }

    @Test
    fun `changed preview and unknown optional choice are rejected before installation`() =
        runBlocking {
            val store = store()
            NetworkClient().use { network ->
                val service =
                    ContentService(
                        store,
                        network,
                        MinecraftInstaller(store, network, JavaService(store, network)),
                    )
                val source = pack(index())
                val preview = service.previewPack(source)
                source.writeBytes(
                    zipBytes(
                        mapOf(
                            "modrinth.index.json" to
                                gson
                                    .toJson(index().apply { addProperty("name", "Changed") })
                                    .toByteArray(),
                        ),
                    ),
                )
                assertFails {
                    service.importPack(
                        source,
                        JobContext(store, "test"),
                        expectedHash = preview.hash,
                    )
                }
                assertFails {
                    service.importPack(
                        source,
                        JobContext(store, "test"),
                        optionalFiles = setOf("not-in-pack.jar"),
                    )
                }
                assertTrue(store.data.instances.isEmpty())
            }
        }

    @Test
    fun `staging skips unselected and server files and applies client overrides last`() =
        runBlocking {
            val store = store()
            val root = temporary.resolve("game").apply { createDirectories() }
            val source =
                pack(
                    index(
                        listOf(
                            file("mods/a.jar"),
                            file("mods/b.jar", "optional"),
                            file("mods/c.jar", "unsupported"),
                        ),
                    ),
                    mapOf(
                        "overrides/config/test.txt" to "base".toByteArray(),
                        "client-overrides/config/test.txt" to "client".toByteArray(),
                        "server-overrides/config/test.txt" to "server".toByteArray(),
                    ),
                )
            val transaction = PackFileTransaction(store, root)
            val downloaded = mutableListOf<String>()
            try {
                ZipFile(source.toFile()).use { zip ->
                    stagePackFiles(
                        readPackManifest(zip, root, store.platform.osName),
                        zip,
                        transaction,
                        root,
                        emptySet(),
                        false,
                        JobContext(store, "test"),
                    ) {
                        downloaded += it.target.fileName.toString()
                        it.target.parent.createDirectories()
                        it.target.writeBytes(bytes)
                    }
                }
                transaction.commit(JobContext(store, "test"))
                assertEquals(listOf("a.jar"), downloaded)
                assertEquals("client", root.resolve("config/test.txt").readText())
                assertFalse(root.resolve("mods/b.jar").exists())
                assertFalse(root.resolve("mods/c.jar").exists())
            } finally {
                transaction.cleanup()
            }
        }

    @Test
    fun `both hashes and size are checked and download mirrors can recover`() =
        runBlocking {
            val store = store()
            val root = temporary.resolve("game").apply { createDirectories() }
            for (kind in listOf("sha1", "sha512", "fileSize")) {
                val entry = file("a.txt")
                if (kind == "fileSize") {
                    entry.addProperty(kind, bytes.size + 1)
                } else {
                    entry.obj("hashes").addProperty(
                        kind,
                        "0".repeat(
                            if (kind ==
                                "sha1"
                            ) {
                                40
                            } else {
                                128
                            },
                        ),
                    )
                }
                val source = pack(index(listOf(entry)))
                val transaction = PackFileTransaction(store, root)
                try {
                    ZipFile(source.toFile()).use { zip ->
                        assertFails {
                            stagePackFiles(
                                readPackManifest(zip, root, store.platform.osName),
                                zip,
                                transaction,
                                root,
                                null,
                                false,
                                JobContext(store, "test"),
                            ) {
                                it.target.parent.createDirectories()
                                it.target.writeBytes(bytes)
                            }
                        }
                    }
                    assertFalse(root.resolve("a.txt").exists())
                } finally {
                    transaction.cleanup()
                }
            }
            val entry =
                file("a.txt").apply {
                    add(
                        "downloads",
                        gson.toJsonTree(
                            listOf(
                                "https://cdn.modrinth.com/missing.txt",
                                "https://gitlab.com/team/a.txt",
                            ),
                        ),
                    )
                }
            val source = pack(index(listOf(entry)))
            val transaction = PackFileTransaction(store, root)
            var attempts = 0
            try {
                ZipFile(source.toFile()).use { zip ->
                    stagePackFiles(
                        readPackManifest(zip, root, store.platform.osName),
                        zip,
                        transaction,
                        root,
                        null,
                        false,
                        JobContext(store, "test"),
                    ) {
                        attempts++
                        if (attempts == 1) throw java.io.IOException("Fixture mirror unavailable")
                        it.target.parent.createDirectories()
                        it.target.writeBytes(bytes)
                    }
                }
                transaction.commit(JobContext(store, "test"))
                assertEquals(2, attempts)
                assertContentEquals(bytes, root.resolve("a.txt").readBytes())
            } finally {
                transaction.cleanup()
            }
        }

    @Test
    fun `commit failure restores files already replaced`() =
        runBlocking {
            val store = store()
            val root = temporary.resolve("game").apply { createDirectories() }
            root.resolve("a.txt").writeText("user file")
            root.resolve("z.txt").createDirectories()
            val transaction = PackFileTransaction(store, root)
            try {
                transaction.content.resolve("a.txt").writeText("replacement")
                transaction.content.resolve("z.txt").writeText("collision")
                assertFails { transaction.commit(JobContext(store, "test")) }
                assertEquals("user file", root.resolve("a.txt").readText())
                assertTrue(root.resolve("z.txt").isDirectory())
            } finally {
                transaction.cleanup()
            }
        }

    @Test
    fun `pack respects the shared directory and repairs without a second instance`() =
        runBlocking {
            val store = store()
            store.mutate { it.copy(settings = it.settings.copy(isolateVersions = false)) }
            val client = zipBytes(mapOf("fixture.txt" to bytes))
            var rejectClient = true
            MockWebServer().use { server ->
                val base = server.loopbackUrl("/").toString().removeSuffix("/")
                val assets = "{\"objects\":{}}"
                val profile =
                    gson.toJson(
                        mapOf(
                            "id" to "fixture",
                            "mainClass" to "FixtureMinecraft",
                            "type" to "release",
                            "libraries" to emptyList<Any>(),
                            "downloads" to
                                mapOf(
                                    "client" to
                                        mapOf(
                                            "url" to "$base/client.jar",
                                            "sha1" to hash(client, "SHA-1"),
                                            "size" to client.size,
                                        ),
                                ),
                            "assetIndex" to mapOf("id" to "fixture", "url" to "$base/assets.json"),
                        ),
                    )
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            when (request.path) {
                                "/manifest.json" ->
                                    MockResponse()
                                        .setBody(
                                            gson.toJson(
                                                mapOf(
                                                    "versions" to
                                                        listOf(
                                                            mapOf(
                                                                "id" to "fixture",
                                                                "type" to "release",
                                                                "url" to "$base/profile.json",
                                                                "sha1" to
                                                                    hash(
                                                                        profile.toByteArray(),
                                                                        "SHA-1",
                                                                    ),
                                                            ),
                                                        ),
                                                ),
                                            ),
                                        )
                                "/profile.json" -> MockResponse().setBody(profile)
                                "/assets.json" -> MockResponse().setBody(assets)
                                "/client.jar" ->
                                    if (rejectClient) {
                                        MockResponse().setResponseCode(404)
                                    } else {
                                        MockResponse().setBody(Buffer().write(client))
                                    }
                                else -> MockResponse().setResponseCode(404)
                            }
                    }
                NetworkClient().use { network ->
                    val installer =
                        MinecraftInstaller(
                            store,
                            network,
                            JavaService(store, network),
                            "$base/manifest.json",
                        )
                    val service = ContentService(store, network, installer)
                    val source =
                        pack(
                            index(),
                            mapOf("overrides/config/fixture.txt" to "pack config".toByteArray()),
                        )
                    assertFails {
                        service.importPack(
                            source,
                            JobContext(store, "test"),
                            optionalFiles = emptySet(),
                        )
                    }
                    val failed = store.data.instances.single()
                    assertFalse(failed.installed)
                    assertEquals(VersionIsolation.Inherit, failed.isolation)
                    assertTrue(Path.of(failed.packFile).isRegularFile())
                    rejectClient = false
                    service.importPack(
                        Path.of(failed.packFile),
                        JobContext(store, "test"),
                        repairId = failed.id,
                    )
                    assertEquals(1, store.data.instances.size)
                    assertTrue(store.instance(failed.id).installed)
                    assertEquals(emptyList(), store.instance(failed.id).packOptionalFiles)
                    assertEquals(
                        "pack config",
                        store.instancePath(failed.id).resolve("config/fixture.txt").readText(),
                    )
                    assertTrue(store.minecraftRoot.resolve("config/fixture.txt").exists())
                    assertTrue(store.cacheDirectory.listDirectoryEntries("pack-import-*").isEmpty())
                }
            }
        }
}
