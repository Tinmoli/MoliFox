// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证整合包清单格式（MCBBS、CurseForge 与旧版纯归档）兼容行为的行为与边界。

package dev.molifox.core

import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PackFormatTest {
    @TempDir lateinit var temporary: Path

    private fun hash(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun addon(path: String = "config/test.txt", content: String = "setting") =
        gson
            .toJsonTree(
                mapOf(
                    "type" to "addon",
                    "path" to path,
                    "hash" to hash(content.toByteArray()),
                    "force" to true,
                )
            )
            .asJsonObject

    private fun mcbbs(loader: String = "", loaderVersion: String = "") =
        gson
            .toJsonTree(
                mapOf(
                    "manifestType" to "minecraftModpack",
                    "manifestVersion" to 2,
                    "name" to "茉狐测试包",
                    "version" to "1.0",
                    "description" to "test description",
                    "addons" to
                        (listOf(mapOf("id" to "game", "version" to "1.20.1")) +
                            if (loader.isBlank()) emptyList()
                            else listOf(mapOf("id" to loader, "version" to loaderVersion))),
                    "files" to listOf(addon()),
                    "libraries" to emptyList<String>(),
                    "launchInfo" to
                        mapOf(
                            "javaArgument" to emptyList<String>(),
                            "launchArgument" to emptyList<String>(),
                            "minMemory" to 2048,
                        ),
                )
            )
            .asJsonObject

    /** 旧版纯归档的清单：只有名字、游戏版本和包版本。 */
    private fun legacyManifest() =
        gson
            .toJsonTree(mapOf("name" to "Old pack", "gameVersion" to "1.20.1", "version" to "1.0"))
            .asJsonObject

    private fun companion(version: String = "1.20.1", loader: String = "") =
        gson
            .toJsonTree(
                mapOf(
                    "manifestType" to "minecraftModpack",
                    "manifestVersion" to 1,
                    "name" to "Companion",
                    "overrides" to "overrides",
                    "minecraft" to
                        mapOf<String, Any>(
                            "version" to version,
                            "modLoaders" to
                                (if (loader.isBlank()) emptyList<Map<String, Any>>()
                                else listOf(mapOf<String, Any>("id" to loader, "primary" to true))),
                        ),
                    "files" to emptyList<String>(),
                )
            )
            .asJsonObject

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

    private fun pack(
        info: JsonObject = mcbbs(),
        filename: String = "mcbbs.packmeta",
        prefix: String = "",
        files: Map<String, String> = mapOf("config/test.txt" to "setting"),
        extra: Map<String, String> = emptyMap(),
    ): Path =
        temporary.resolve("${UUID.randomUUID()}.zip").apply {
            val content = if (filename == "modpack.json") "minecraft/" else "overrides/"
            writeBytes(
                zipBytes(
                    mapOf("$prefix$filename" to gson.toJson(info)) +
                        files.mapKeys { "$prefix$content${it.key}" } +
                        extra
                )
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

    private fun service(store: LauncherStore, network: NetworkClient) =
        ContentService(
            store,
            network,
            MinecraftInstaller(store, network, JavaService(store, network)),
        )

    private fun parsed(source: Path, action: (PackManifest) -> Unit) =
        ZipFile(source.toFile()).use {
            action(readPackManifest(it, temporary.resolve("validation"), "windows"))
        }

    @Test
    fun `MCBBS preview supports canonical alternate and wrapped manifests offline`() = runBlocking {
        val store = store()
        NetworkClient().use { network ->
            val service = service(store, network)
            for (filename in listOf("mcbbs.packmeta", "manifest.json")) for (prefix in
                listOf("", "Pack/")) {
                val preview = service.previewPack(pack(filename = filename, prefix = prefix))
                assertEquals("茉狐测试包", preview.title)
                assertEquals("1.20.1", preview.gameVersion)
                assertEquals("1.0", preview.version)
                assertEquals(1, preview.requiredFiles)
                assertTrue(preview.summary.contains("test description"))
                assertTrue(preview.summary.contains("2048 MB"))
            }
            val oldVersion = mcbbs().apply { addProperty("manifestVersion", 1) }
            assertEquals("1.20.1", service.previewPack(pack(oldVersion)).gameVersion)
            assertTrue(store.data.instances.isEmpty())
            assertTrue(store.cacheDirectory.listDirectoryEntries("pack-import-*").isEmpty())
        }
    }

    @Test
    fun `canonical MCBBS has priority over compatible CurseForge companion`() {
        for ((id, type) in
            mapOf(
                "fabric" to LoaderType.Fabric,
                "quilt" to LoaderType.Quilt,
                "forge" to LoaderType.Forge,
                "neoforge" to LoaderType.NeoForge,
            )) {
            val other = companion(loader = if (id == "quilt") "" else "$id-1.2.3")
            parsed(pack(mcbbs(id, "1.2.3"), extra = mapOf("manifest.json" to gson.toJson(other)))) {
                assertEquals(type, it.request.loader)
                assertEquals("1.2.3", it.request.loaderVersion)
                assertEquals(1, it.checkedFiles.size)
            }
        }
    }

    @Test
    fun `conflicting companion metadata cannot change declared game or loader`() {
        for (other in listOf(companion("1.21"), companion(loader = "forge-2"))) {
            assertFails {
                parsed(
                    pack(mcbbs("fabric", "1"), extra = mapOf("manifest.json" to gson.toJson(other)))
                ) {}
            }
        }
        val otherMcbbs = mcbbs("forge", "1")
        assertFails {
            parsed(
                pack(
                    mcbbs("fabric", "1"),
                    extra = mapOf("manifest.json" to gson.toJson(otherMcbbs)),
                )
            ) {}
        }
    }

    @Test
    fun `addon hashes are checked before creating instances`() = runBlocking {
        val store = store()
        NetworkClient().use { network ->
            val service = service(store, network)
            for (info in
                listOf(
                    mcbbs().apply {
                        array("files").first().asJsonObject.addProperty("hash", "invalid")
                    },
                    mcbbs().apply {
                        array("files").first().asJsonObject.addProperty("hash", "0".repeat(40))
                    },
                )) {
                val source = pack(info)
                assertFails { service.previewPack(source) }
                assertFails { service.importPack(source, JobContext(store, "test")) }
            }
            assertTrue(store.data.instances.isEmpty())
            assertFalse(store.dataDirectory.resolve("packs").exists())
        }
    }

    @Test
    fun `missing addon requires an allowed download source and a valid hash`() {
        val files = mapOf("config/other.txt" to "present")
        assertTrue(
            assertFails { parsed(pack(files = files)) {} }.message!!.contains("config/test.txt")
        )
        for (url in
            listOf(
                "http://example.com",
                "https://example.com",
                "https://raw.githubusercontent.com/a?token=secret",
            )) {
            assertFails {
                parsed(pack(mcbbs().apply { addProperty("fileApi", url) }, files = files)) {}
            }
        }
        parsed(
            pack(
                mcbbs().apply {
                    addProperty("fileApi", "https://raw.githubusercontent.com/team/pack/main")
                },
                files = files,
            )
        ) {
            assertEquals(
                "https://raw.githubusercontent.com/team/pack/main/overrides/config/test.txt",
                it.checkedFiles.single().url,
            )
        }
    }

    @Test
    fun `remote addons stage transactionally and verify sha1`() = runBlocking {
        val store = store()
        val root = temporary.resolve("remote-game").apply { createDirectories() }
        val source =
            pack(
                mcbbs().apply {
                    addProperty("fileApi", "https://raw.githubusercontent.com/team/pack/main")
                },
                files = mapOf("config/other.txt" to "present"),
            )
        for (corrupt in listOf(true, false)) {
            val transaction = PackFileTransaction(store, root)
            try {
                ZipFile(source.toFile()).use { zip ->
                    val manifest = readPackManifest(zip, root, "windows")
                    suspend fun stage() =
                        stagePackFiles(
                            manifest,
                            zip,
                            transaction,
                            root,
                            null,
                            false,
                            JobContext(store, "test"),
                        ) {
                            it.target.parent.createDirectories()
                            it.target.writeText(if (corrupt) "wrong" else "setting")
                        }
                    if (corrupt) {
                        assertFails { stage() }
                        assertFalse(root.resolve("config/test.txt").exists())
                    } else {
                        stage()
                        transaction.commit(JobContext(store, "test"))
                        assertEquals("setting", root.resolve("config/test.txt").readText())
                    }
                }
            } finally {
                transaction.cleanup()
            }
        }
    }

    @Test
    fun `unsafe duplicate and conflicting paths are rejected before extraction`() {
        val metadata =
            listOf(
                mcbbs().apply {
                    array("files").first().asJsonObject.addProperty("path", "../escape.txt")
                },
                mcbbs().apply {
                    array("files").first().asJsonObject.addProperty("path", ".molifox-content.json")
                },
                mcbbs().apply { array("files").add(array("files").first().deepCopy()) },
                mcbbs().apply {
                    array("files").first().asJsonObject.addProperty("type", "unknown")
                },
            )
        for (info in metadata) assertFails { parsed(pack(info)) {} }
        for (files in
            listOf(
                mapOf("config/test.txt" to "setting", "config" to "file"),
                mapOf("config/test.txt" to "setting", "config/Test.txt" to "other"),
                mapOf("../escape" to "bad"),
            )) assertFails { parsed(pack(files = files)) {} }
        assertFails { parsed(pack(prefix = "../")) {} }
        assertFails { parsed(pack(extra = mapOf("Other/modpack.json" to gson.toJson(legacyManifest())))) {} }
    }

    @Test
    fun `unknown addons custom libraries arguments and disabled install settings fail clearly`() {
        val samples =
            listOf(
                mcbbs().apply {
                    array("addons")
                        .add(gson.toJsonTree(mapOf("id" to "optifine", "version" to "HD")))
                },
                mcbbs().apply { array("addons").add(array("addons").first().deepCopy()) },
                mcbbs().apply { array("addons").first().asJsonObject.addProperty("id", "unknown") },
                mcbbs("forge", "").apply {},
                mcbbs("fabric", "1").apply {
                    array("addons").add(gson.toJsonTree(mapOf("id" to "quilt", "version" to "2")))
                },
                mcbbs().apply { array("libraries").add("custom") },
                mcbbs().apply {
                    obj("launchInfo").array("javaArgument").add("-cp")
                },
                mcbbs().apply { add("settings", gson.toJsonTree(mapOf("install_mods" to false))) },
                mcbbs().apply { addProperty("manifestVersion", 3) },
            )
        for (sample in samples) assertFails { parsed(pack(sample)) {} }
    }

    @Test
    fun `embedded data named like a manifest is not treated as another instance`() {
        val source =
            pack(
                extra =
                    mapOf(
                        "overrides/manifest.json" to "not metadata",
                        "overrides/mmc-pack.json" to "not metadata",
                    )
            )
        parsed(source) { assertEquals(3, it.requiredFiles) }
        val own =
            temporary.resolve("own.zip").apply {
                writeBytes(
                    zipBytes(
                        mapOf(
                            "molifox-instance.json" to
                                gson.toJson(
                                    mapOf(
                                        "formatVersion" to 1,
                                        "instance" to InstallRequest("Own", "1.20.1"),
                                    )
                                ),
                            "files/modpack.json" to "not metadata",
                        )
                    )
                )
            }
        parsed(own) { assertEquals("Own", it.request.name) }
    }

    @Test
    fun `CurseForge wrapped archives still use their own manifest`() {
        parsed(pack(companion(), "manifest.json", prefix = "Pack/")) {
            assertEquals("1.20.1", it.request.gameVersion)
            assertEquals(listOf("Pack/overrides/"), it.overridePrefixes)
        }
    }

    @Test
    fun `old plain archives support offline preview and game content staging`() = runBlocking {
        val store = store()
        for (prefix in listOf("", "Old pack/")) {
            val source =
                pack(
                    legacyManifest(),
                    "modpack.json",
                    prefix,
                    files = mapOf("options.txt" to "options", "saves/world/level.dat" to "world"),
                )
            val root = temporary.resolve(UUID.randomUUID().toString()).apply { createDirectories() }
            val transaction = PackFileTransaction(store, root)
            try {
                ZipFile(source.toFile()).use { zip ->
                    val manifest = readPackManifest(zip, root, "windows")
                    assertEquals("Old pack", manifest.request.name)
                    assertEquals(LoaderType.Vanilla, manifest.request.loader)
                    stagePackFiles(
                        manifest,
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
                assertEquals("world", root.resolve("saves/world/level.dat").readText())
            } finally {
                transaction.cleanup()
            }
        }
    }

    @Test
    fun `old custom launch profiles cannot silently become vanilla`() {
        for (path in
            listOf("pack.json", "custom.json", "versions/custom/custom.json", "custom.jar")) {
            assertFails {
                parsed(
                    pack(
                        legacyManifest(),
                        "modpack.json",
                        files =
                            mapOf(
                                path to
                                    "{\"mainClass\":\"net.fabricmc.loader.impl.launch.knot.KnotClient\"}"
                            ),
                    )
                ) {}
            }
        }
        assertFails {
            parsed(
                pack(legacyManifest().apply { addProperty("mainClass", "custom.Main") }, "modpack.json")
            ) {}
        }
    }

    @Test
    fun `MCBBS staging excludes private launcher files and preserves user settings during repair`() =
        runBlocking {
            val store = store()
            val root =
                temporary.resolve("private-game").apply { resolve("config").createDirectories() }
            root.resolve("config/test.txt").writeText("user setting")
            val source =
                pack(
                    extra =
                        mapOf(
                            "overrides/accounts.json" to "private",
                            "overrides/hmclversion.cfg" to "commands",
                            "overrides/logs/latest.log" to "log",
                        )
                )
            val transaction = PackFileTransaction(store, root)
            try {
                ZipFile(source.toFile()).use { zip ->
                    stagePackFiles(
                        readPackManifest(zip, root, "windows"),
                        zip,
                        transaction,
                        root,
                        null,
                        true,
                        JobContext(store, "test"),
                    ) {
                        error("No download expected")
                    }
                }
                transaction.commit(JobContext(store, "test"))
                assertEquals("user setting", root.resolve("config/test.txt").readText())
                assertFalse(root.resolve("accounts.json").exists())
                assertFalse(root.resolve("hmclversion.cfg").exists())
                assertFalse(root.resolve("logs").exists())
            } finally {
                transaction.cleanup()
            }
        }

    @Test
    fun `MCBBS full install with CurseForge files supports failure and repair without credentials`() =
        runBlocking {
            val store = store()
            store.mutate {
                it.copy(
                    settings =
                        it.settings.copy(
                            isolateVersions = false,
                            memoryMb = 512,
                            initialMemoryMb = 128,
                            javaPath =
                                Path.of(
                                        System.getProperty("java.home"),
                                        "bin",
                                        store.platform.javaExecutable,
                                    )
                                    .toString(),
                            extraJvmArgs = "-Dglobal.message=global",
                            advancedLaunch = AdvancedLaunchSettings("--demo"),
                        )
                )
            }
            MockWebServer().use { server ->
                val requests = CopyOnWriteArrayList<RecordedRequest>()
                val serverBase = server.loopbackUrl("").toString().trimEnd('/')
                store.mutate {
                    it.copy(settings = it.settings.copy(curseForgeBaseUrl = serverBase))
                }
                val client = zipBytes(mapOf("fixture.txt" to "fixture"))
                val resource = "resource".toByteArray()
                val base = server.loopbackUrl("/").toString().trimEnd('/')
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
                                            "url" to "$base/client.jar",
                                            "sha1" to hash(client),
                                            "size" to client.size,
                                        )
                                ),
                            "assetIndex" to mapOf("id" to "fixture", "url" to "$base/assets.json"),
                        )
                    )
                var failDownload = true
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            requests += request
                            val path = request.requestUrl!!.encodedPath
                            val body: Any =
                                when (path) {
                                    "/v1/mods" ->
                                        listOf(
                                            mapOf(
                                                "id" to 100,
                                                "gameId" to 432,
                                                "classId" to 12,
                                                "slug" to "fixture",
                                                "allowModDistribution" to true,
                                            )
                                        )
                                    "/v1/mods/files" ->
                                        listOf(
                                            mapOf(
                                                "id" to 200,
                                                "modId" to 100,
                                                "gameId" to 432,
                                                "isAvailable" to true,
                                                "isServerPack" to false,
                                                "displayName" to "Resource",
                                                "fileName" to "resource.zip",
                                                "releaseType" to 1,
                                                "fileDate" to "2025-01-01T00:00:00Z",
                                                "fileLength" to resource.size,
                                                "hashes" to
                                                    listOf(
                                                        mapOf(
                                                            "algo" to 1,
                                                            "value" to hash(resource),
                                                        )
                                                    ),
                                                "downloadUrl" to "$base/resource.zip",
                                                "gameVersions" to listOf("1.20.1"),
                                                "dependencies" to emptyList<String>(),
                                            )
                                        )
                                    "/v1/categories" ->
                                        listOf(
                                            mapOf(
                                                "id" to 12,
                                                "gameId" to 432,
                                                "isClass" to true,
                                                "slug" to "texture-packs",
                                            )
                                        )
                                    "/manifest.json" ->
                                        return MockResponse()
                                            .setBody(
                                                gson.toJson(
                                                    mapOf(
                                                        "versions" to
                                                            listOf(
                                                                mapOf(
                                                                    "id" to "1.20.1",
                                                                    "type" to "release",
                                                                    "url" to "$base/profile.json",
                                                                    "sha1" to
                                                                        hash(profile.toByteArray()),
                                                                )
                                                            )
                                                    )
                                                )
                                            )
                                    "/profile.json" -> return MockResponse().setBody(profile)
                                    "/assets.json" ->
                                        return MockResponse().setBody("{\"objects\":{}}")
                                    "/client.jar" ->
                                        return if (failDownload) MockResponse().setResponseCode(404)
                                        else MockResponse().setBody(Buffer().write(client))
                                    "/resource.zip" ->
                                        return MockResponse().setBody(Buffer().write(resource))
                                    else -> return MockResponse().setResponseCode(404)
                                }
                            return MockResponse().setBody(gson.toJson(mapOf("data" to body)))
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
                                "$base/manifest.json",
                            ),
                        )
                    val source =
                        pack(
                            mcbbs().apply {
                                obj("launchInfo")
                                    .array("javaArgument")
                                    .add("-Dpack.message=茉狐 value with spaces")
                                obj("launchInfo")
                                    .add(
                                        "launchArgument",
                                        gson.toJsonTree(
                                            listOf("--server", "茉狐 server", "--port", "25565")
                                        ),
                                    )
                                array("files")
                                    .add(
                                        gson.toJsonTree(
                                            mapOf(
                                                "type" to "curse",
                                                "projectID" to 100,
                                                "fileID" to 200,
                                                "url" to "https://invalid.example/not-used",
                                            )
                                        )
                                    )
                            }
                        )
                    assertEquals(2, service.previewPack(source).requiredFiles)
                    assertFails { service.importPack(source, JobContext(store, "test")) }
                    val instance = store.data.instances.single()
                    assertFalse(instance.installed)
                    assertEquals(VersionIsolation.Inherit, instance.isolation)
                    failDownload = false
                    service.importPack(
                        Path.of(instance.packFile),
                        JobContext(store, "test"),
                        instance.id,
                    )
                    assertEquals(1, store.data.instances.size)
                    assertTrue(store.instance(instance.id).installed)
                    val root = store.instancePath(instance.id)
                    assertEquals("setting", root.resolve("config/test.txt").readText())
                    assertEquals("resource", root.resolve("resourcepacks/resource.zip").readText())
                    val imported = store.instance(instance.id)
                    assertEquals(
                        listOf("-Dglobal.message=global", "-Dpack.message=茉狐 value with spaces"),
                        splitArguments(imported.extraJvmArgs),
                    )
                    assertEquals(
                        listOf("--demo", "--server", "茉狐 server", "--port", "25565"),
                        extraGameArguments(requireNotNull(imported.advancedLaunch)),
                    )
                    store.updateInstance(instance.id) {
                        it.copy(
                            extraJvmArgs = it.extraJvmArgs + " -Duser.custom=kept",
                            advancedLaunch =
                                requireNotNull(it.advancedLaunch)
                                    .copy(
                                        extraGameArgs =
                                            it.advancedLaunch.extraGameArgs + " --user-value kept"
                                    ),
                        )
                    }
                    val customized = store.instance(instance.id)
                    service.importPack(
                        Path.of(instance.packFile),
                        JobContext(store, "test"),
                        instance.id,
                    )
                    assertEquals(customized.extraJvmArgs, store.instance(instance.id).extraJvmArgs)
                    assertEquals(
                        customized.advancedLaunch,
                        store.instance(instance.id).advancedLaunch,
                    )
                    val wrongPack =
                        pack(
                            mcbbs().apply {
                                array("addons").first().asJsonObject.addProperty("version", "1.21")
                            }
                        )
                    assertFails {
                        service.importPack(wrongPack, JobContext(store, "test"), instance.id)
                    }
                    assertEquals("1.20.1", store.instance(instance.id).gameVersion)
                    assertTrue(requests.all { it.getHeader("Authorization") == null })
                    checkRealJavaArguments(store, network, instance.id)
                }
            }
        }

    private suspend fun checkRealJavaArguments(
        store: LauncherStore,
        network: NetworkClient,
        instanceId: String,
    ) {
        val source = temporary.resolve("PackArgumentsFixture.java")
        source.writeText(
            """public class PackArgumentsFixture { public static void main(String[] args) { System.out.println("PACK_JVM=" + System.getProperty("pack.message")); System.out.println("PACK_GLOBAL=" + System.getProperty("global.message")); System.out.println("PACK_ARGS=" + String.join("|", args)); System.out.println("PACK_CWD=" + java.nio.file.Path.of("").toAbsolutePath().normalize()); } }"""
        )
        assertEquals(
            0,
            ToolProvider.getSystemJavaCompiler()
                .run(
                    null,
                    null,
                    null,
                    "--release",
                    "17",
                    "-d",
                    temporary.toString(),
                    source.toString(),
                ),
        )
        // A controlled probe replaces only the fixture profile; no Minecraft is executed.
        store.updateInstance(instanceId) {
            it.copy(gameVersion = "fixture", versionId = "fixture", externalProfile = true)
        }
        val jar =
            store.minecraftRoot.resolve("versions/fixture/fixture.jar").apply {
                parent.createDirectories()
            }
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            output.putNextEntry(JarEntry("PackArgumentsFixture.class"))
            output.write(temporary.resolve("PackArgumentsFixture.class").readBytes())
            output.closeEntry()
        }
        writeJson(
            jar.resolveSibling("fixture.json"),
            mapOf(
                "id" to "fixture",
                "mainClass" to "PackArgumentsFixture",
                "javaVersion" to mapOf("majorVersion" to Runtime.version().feature()),
                "libraries" to emptyList<String>(),
                "arguments" to mapOf("game" to emptyList<String>()),
            ),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val java = JavaService(store, network)
        val accounts = AccountService(store, network, scope)
        accounts.addOffline("PackTester")
        val launcher =
            GameLauncher(store, MinecraftInstaller(store, network, java), java, accounts, scope)
        val jobs = JobQueue(store, scope)
        try {
            val jobId = jobs.enqueue("pack argument fixture") { launcher.launch(instanceId, this) }
            withTimeout(30000) {
                jobs.awaitIdle()
                while (store.state.value.runningInstanceId != null) delay(25)
            }
            val job = store.state.value.jobs.first { it.id == jobId }
            assertEquals(JobStatus.Completed, job.status, job.error)
            val logs = store.state.value.logs.map { it.message }
            for (line in
                listOf(
                    "PACK_JVM=茉狐 value with spaces",
                    "PACK_GLOBAL=global",
                    "PACK_ARGS=--width|${store.data.settings.width}|--height|${store.data.settings.height}|--demo|--server|茉狐 server|--port|25565|--user-value|kept",
                    "PACK_CWD=${store.instancePath(instanceId).toAbsolutePath().normalize()}",
                )) assertTrue(line in logs, logs.joinToString("\n"))
        } finally {
            launcher.stop()
            scope.cancel()
        }
    }

    @Test
    fun `pack argument conversion preserves literal tokens and rejects managed flags`() {
        val values = listOf("-Dpath=C:\\folder with spaces", "value'quote", "value\"quote", "中文")
        assertEquals(values, splitArguments(packArgumentsText(values)))
        for (values in
            listOf(listOf(""), listOf("both'and\"quotes"), listOf("line\nbreak"))) assertFails {
            packArgumentsText(values)
        }
        for (flag in listOf("-cp", "-jar", "-Xmx2G", "@args.txt")) assertFails {
            PackLaunchOptions(listOf(flag), emptyList()).resolve(LauncherSettings(), "windows")
        }
        for (flag in listOf("--accessToken", "--gameDir", "--width")) assertFails {
            PackLaunchOptions(emptyList(), listOf(flag, "other"))
                .resolve(LauncherSettings(), "windows")
        }
    }

    @Test
    fun `nullable optional MCBBS fields keep their documented defaults`() {
        parsed(
            pack(
                mcbbs().apply {
                    for (field in listOf("libraries", "launchInfo", "settings", "serverInfo")) add(
                        field,
                        null,
                    )
                }
            )
        ) {
            assertEquals("1.20.1", it.request.gameVersion)
            assertNull(it.launchOptions)
        }
    }
}
