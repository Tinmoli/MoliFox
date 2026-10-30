// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证MultiMC 整合包的识别和导入的行为与边界。

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
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MultiMcPackTest {
    @TempDir lateinit var temporary: Path

    private fun info(vararg components: Pair<String, String>) =
        gson
            .toJsonTree(
                mapOf(
                    "formatVersion" to 1,
                    "components" to
                        (listOf("net.minecraft" to "1.20.1") + components).map {
                            mapOf("uid" to it.first, "version" to it.second)
                        },
                ),
            ).asJsonObject

    private fun zipBytes(entries: Map<String, String>): ByteArray =
        ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                for ((name, value) in entries) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(value.toByteArray())
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }

    private fun archive(
        metadata: JsonObject = info(),
        prefix: String = "",
        folder: String = "minecraft/",
        config: String = "[General]\nname=Test instance\n",
        files: Map<String, String> = mapOf("config/test.txt" to "setting"),
        extras: Map<String, String> = emptyMap(),
    ): Path =
        temporary.resolve("${UUID.randomUUID()}.zip").apply {
            writeBytes(
                zipBytes(
                    mapOf(
                        "${prefix}mmc-pack.json" to gson.toJson(metadata),
                        "${prefix}instance.cfg" to config,
                    ) + files.mapKeys { "$prefix$folder${it.key}" } + extras,
                ),
            )
        }

    private fun store() =
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

    @Test
    fun `offline preview accepts root and enclosing standard game folders`() =
        runBlocking {
            val store = store()
            NetworkClient().use { network ->
                val service =
                    ContentService(
                        store,
                        network,
                        MinecraftInstaller(store, network, JavaService(store, network)),
                    )
                for (prefix in listOf("", "My pack/")) {
                    for (
                    folder in
                    listOf("minecraft/", ".minecraft/")
                    ) {
                        val preview = service.previewPack(archive(prefix = prefix, folder = folder))
                        assertEquals("Test instance", preview.title)
                        assertEquals("1.20.1", preview.gameVersion)
                        assertEquals(LoaderType.Vanilla, preview.loader)
                        assertEquals(1, preview.requiredFiles)
                        assertTrue(preview.optionalFiles.isEmpty())
                    }
                }
                assertTrue(store.data.instances.isEmpty())
                assertTrue(store.cacheDirectory.listDirectoryEntries("pack-import-*").isEmpty())
            }
        }

    @Test
    fun `loader identities and exact versions are kept rather than cached display versions`() {
        val root = temporary.resolve("game")
        for (
        (uid, loader) in
        mapOf(
            "net.fabricmc.fabric-loader" to LoaderType.Fabric,
            "org.quiltmc.quilt-loader" to LoaderType.Quilt,
            "net.minecraftforge" to LoaderType.Forge,
            "net.neoforged" to LoaderType.NeoForge,
        )
        ) {
            val metadata =
                info(uid to "2.0").apply {
                    array("components").last().asJsonObject.addProperty("cachedVersion", "old")
                }
            ZipFile(archive(metadata).toFile()).use { zip ->
                val request = readPackManifest(zip, root, "windows").request
                assertEquals(loader, request.loader)
                assertEquals("2.0", request.loaderVersion)
            }
        }
        val disabled =
            info("net.fabricmc.fabric-loader" to "0.16.14").apply {
                array("components").last().asJsonObject.addProperty("disabled", true)
            }
        ZipFile(archive(disabled).toFile()).use {
            assertEquals(LoaderType.Vanilla, readPackManifest(it, root, "windows").request.loader)
        }
    }

    @Test
    fun `unsupported components patches and missing exact versions fail explicitly`() {
        val samples =
            listOf(
                info("unknown.loader" to "1"),
                info("org.lwjgl3" to "3.3.1"),
                info("net.fabricmc.fabric-loader" to ""),
                info("net.fabricmc.fabric-loader" to "1", "net.minecraftforge" to "2"),
                info().apply {
                    array("components").first().asJsonObject.addProperty("disabled", true)
                },
                info().apply { addProperty("formatVersion", 2) },
                info().apply { array("components").add(array("components").first().deepCopy()) },
                info().apply {
                    array("components").first().asJsonObject.addProperty("custom", true)
                },
            )
        for (sample in samples) {
            ZipFile(archive(sample).toFile()).use { zip ->
                assertFails { readPackManifest(zip, temporary.resolve("game"), "windows") }
            }
        }
        for (path in listOf("patches/custom.json", "jarmods/custom.jar")) {
            ZipFile(
                archive(extras = mapOf(path to "custom")).toFile(),
            ).use { zip ->
                assertFails { readPackManifest(zip, temporary.resolve("game"), "windows") }
            }
        }
    }

    @Test
    fun `dependent lwjgl and Qt ini unicode names can be imported`() {
        val metadata =
            info("org.lwjgl3" to "3.3.1").apply {
                array("components").last().asJsonObject.addProperty("dependencyOnly", true)
            }
        ZipFile(
            archive(metadata, config = "[General]\nname=\\x8309\\x72d0\n[Other]\nname=wrong\n")
                .toFile(),
        ).use { zip ->
            val parsed = readPackManifest(zip, temporary.resolve("game"), "windows")
            assertEquals("茉狐", parsed.request.name)
            assertTrue(parsed.summary.contains("官方元数据"))
        }
    }

    @Test
    fun `staging keeps game data and excludes caches and launcher commands`() =
        runBlocking {
            val store = store()
            val root = temporary.resolve("game").apply { createDirectories() }
            val files =
                mapOf(
                    "mods/test.jar.disabled" to "mod",
                    "config/test.txt" to "setting",
                    "saves/world/level.dat" to "world",
                    "versions/old/old.json" to "cached version",
                    "assets/indexes/a.json" to "assets",
                    "logs/latest.log" to "log",
                )
            val source =
                archive(
                    files = files,
                    config =
                        "[General]\nname=Test instance\nPreLaunchCommand=malicious-command\nJavaPath=C:/other/java\n",
                    extras = mapOf("accounts.json" to "private", "launcher.cfg" to "settings"),
                )
            val transaction = PackFileTransaction(store, root)
            try {
                ZipFile(source.toFile()).use { zip ->
                    val parsed = readPackManifest(zip, root, "windows")
                    assertEquals(3, parsed.requiredFiles)
                    stagePackFiles(
                        parsed,
                        zip,
                        transaction,
                        root,
                        null,
                        false,
                        JobContext(store, "test"),
                    ) {
                        error("No download expected")
                    }
                }
                transaction.commit(JobContext(store, "test"))
                assertEquals("mod", root.resolve("mods/test.jar.disabled").readText())
                assertEquals("world", root.resolve("saves/world/level.dat").readText())
                for (
                path in
                listOf(
                    "versions",
                    "assets",
                    "logs",
                    "instance.cfg",
                    "accounts.json",
                    "launcher.cfg",
                )
                ) {
                    assertFalse(root.resolve(path).exists(), path)
                }
            } finally {
                transaction.cleanup()
            }
        }

    @Test
    fun `ambiguous instances game folders unsafe paths and case collisions are rejected`() {
        val samples =
            listOf(
                archive(extras = mapOf("Other/mmc-pack.json" to gson.toJson(info()))),
                archive(extras = mapOf(".minecraft/config/a.txt" to "ambiguous")),
                archive(files = mapOf("../../escape.txt" to "bad")),
                archive(files = mapOf(".molifox-content.json" to "bad")),
                archive(files = mapOf("config/Case.txt" to "one", "config/case.txt" to "two")),
                archive(files = mapOf("a" to "file", "a/child" to "conflict")),
                archive(extras = mapOf("manifest.json" to "{}")),
            )
        for (source in samples) {
            ZipFile(source.toFile()).use { zip ->
                assertFails { readPackManifest(zip, temporary.resolve("game"), "windows") }
            }
        }
    }

    @Test
    fun `full import and repair keep isolation and user configuration`() =
        runBlocking {
            val store = store()
            store.mutate { it.copy(settings = it.settings.copy(isolateVersions = false)) }
            MockWebServer().use { server ->
                fun hash(bytes: ByteArray) =
                    MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") {
                        "%02x".format(it)
                    }
                val client = zipBytes(mapOf("fixture.txt" to "fixture"))
                val profile =
                    gson.toJson(
                        mapOf(
                            "id" to "1.20.1",
                            "type" to "release",
                            "mainClass" to "fixture.Main",
                            "libraries" to emptyList<String>(),
                            "downloads" to
                                mapOf(
                                    "client" to
                                        mapOf(
                                            "url" to server.loopbackUrl("/client.jar").toString(),
                                            "sha1" to hash(client),
                                            "size" to client.size,
                                        ),
                                ),
                            "assetIndex" to
                                mapOf(
                                    "id" to "fixture",
                                    "url" to server.loopbackUrl("/assets.json").toString(),
                                ),
                        ),
                    )
                var failDownload = true
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            when (checkNotNull(request.requestUrl).encodedPath) {
                                "/manifest.json" ->
                                    MockResponse()
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
                                                                            "/profile.json",
                                                                        ).toString(),
                                                                "sha1" to
                                                                    hash(profile.toByteArray()),
                                                            ),
                                                        ),
                                                ),
                                            ),
                                        )
                                "/profile.json" -> MockResponse().setBody(profile)
                                "/assets.json" -> MockResponse().setBody("{\"objects\":{}}")
                                "/client.jar" ->
                                    if (failDownload) {
                                        MockResponse().setResponseCode(404)
                                    } else {
                                        MockResponse().setBody(Buffer().write(client))
                                    }
                                else -> MockResponse().setResponseCode(404)
                            }
                    }
                NetworkClient().use { network ->
                    val service =
                        ContentService(
                            store,
                            network,
                            MinecraftInstaller(
                                store,
                                network,
                                JavaService(store, network),
                                server.loopbackUrl("/manifest.json").toString(),
                            ),
                        )
                    val source = archive()
                    assertFails { service.importPack(source, JobContext(store, "test")) }
                    val instance = store.data.instances.single()
                    assertEquals(VersionIsolation.Inherit, instance.isolation)
                    assertFalse(instance.installed)
                    val root = store.instancePath(instance.id)
                    assertFalse(root.resolve("config/test.txt").exists())
                    failDownload = false
                    service.importPack(
                        Path.of(instance.packFile),
                        JobContext(store, "test"),
                        instance.id,
                    )
                    assertEquals(1, store.data.instances.size)
                    assertTrue(store.instance(instance.id).installed)
                    assertEquals("setting", root.resolve("config/test.txt").readText())
                    root.resolve("config/test.txt").writeText("user setting")
                    service.importPack(
                        Path.of(instance.packFile),
                        JobContext(store, "test"),
                        instance.id,
                    )
                    assertEquals("user setting", root.resolve("config/test.txt").readText())
                }
            }
        }
}
