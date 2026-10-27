package dev.molifox.core

import com.google.gson.JsonObject
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MrpackExportTest {
    @TempDir lateinit var temporary: Path
    private val metadata = MrpackExportMetadata("茉狐测试包", "1.0.0", "中文简介")

    private suspend fun fixture(
        action: suspend (LauncherStore, ContentService, GameInstance, Path) -> Unit
    ) {
        val store = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))
        val instance =
            GameInstance(
                name = "Source",
                gameVersion = "1.20.1",
                versionId = "1.20.1",
                installed = true,
                isolation = VersionIsolation.Enabled,
            )
        store.mutate { it.copy(instances = listOf(instance)) }
        val root = store.instancePath(instance.id).apply { createDirectories() }
        NetworkClient().use { network ->
            val service =
                ContentService(
                    store,
                    network,
                    MinecraftInstaller(store, network, JavaService(store, network)),
                )
            action(store, service, instance, root)
        }
    }

    private fun write(root: Path, path: String, content: String = path) =
        root.resolve(path).apply {
            parent.createDirectories()
            writeText(content)
        }

    private fun service(store: LauncherStore, network: NetworkClient, server: MockWebServer) =
        ContentService(
            store,
            network,
            MinecraftInstaller(store, network, JavaService(store, network)),
            server.loopbackUrl("/v2").toString(),
        )

    private fun remote(
        file: MrpackExportFile,
        url: String = "https://cdn.modrinth.com/data/project/versions/version/file.jar",
    ) =
        gson
            .toJsonTree(
                mapOf(
                    "id" to "version",
                    "files" to
                        listOf(
                            mapOf(
                                "url" to url,
                                "size" to file.size,
                                "hashes" to mapOf("sha1" to file.sha1, "sha512" to file.sha512),
                            )
                        ),
                )
            )
            .asJsonObject

    @Test
    fun `offline defaults exclude private runtime and user data while preserving configuration`() =
        runBlocking {
            fixture { store, service, instance, root ->
                val visible =
                    setOf(
                        "config/a.toml",
                        "defaultconfigs/b.toml",
                        "kubejs/startup_scripts/test.js",
                        "scripts/a.zs",
                        "mods/local.jar",
                        "saves/World/level.dat",
                        "screenshots/a.png",
                        "options.txt",
                        "servers.dat",
                    )
                val excluded =
                    setOf(
                        "accounts.json",
                        "credentials/one.json",
                        "launcher.json",
                        "launcher_accounts.json",
                        "logs/latest.log",
                        "assets/objects/abc",
                        "libraries/a.jar",
                        "versions/other/other.json",
                        ".molifox-content.json",
                        "config/nested/tokens/private.json",
                        "1.20.1.jar",
                        "1.20.1.json",
                    )
                (visible + excluded).forEach { write(root, it) }
                val plan = service.previewMrpackExport(instance.id, metadata, false)
                assertEquals(visible, plan.files.map { it.path }.toSet())
                assertEquals(
                    setOf(
                        "config/a.toml",
                        "defaultconfigs/b.toml",
                        "kubejs/startup_scripts/test.js",
                        "scripts/a.zs",
                    ),
                    plan.defaultSelection,
                )
                val target = temporary.resolve("offline.mrpack")
                service.exportMrpack(plan, target, JobContext(store, "test"))
                ZipFile(target.toFile()).use { zip ->
                    assertEquals(
                        plan.defaultSelection.map { "client-overrides/$it" }.toSet() +
                            "modrinth.index.json",
                        zip.entries().asSequence().map { it.name }.toSet(),
                    )
                    val manifest =
                        readPackManifest(zip, temporary.resolve("destination"), "windows")
                    assertEquals(metadata.name, manifest.request.name)
                    assertEquals(metadata.version, manifest.version)
                    assertEquals(metadata.summary, manifest.summary)
                    assertEquals("1.20.1", manifest.request.gameVersion)
                }
            }
        }

    @Test
    fun `unknown local resources need explicit inclusion and permission`() = runBlocking {
        fixture { store, service, instance, root ->
            write(root, "mods/private.jar", "private bytes")
            write(root, "resourcepacks/local.zip")
            val plan = service.previewMrpackExport(instance.id, metadata, false)
            assertTrue(plan.defaultSelection.isEmpty())
            assertTrue(plan.files.all { it.requiresPermission })
            val selected = plan.files.map { it.path }.toSet()
            val target = temporary.resolve("local.mrpack")
            assertFailsWith<IllegalArgumentException> {
                service.exportMrpack(plan, target, JobContext(store, "test"), selected)
            }
            assertFalse(target.exists())
            service.exportMrpack(plan, target, JobContext(store, "test"), selected, selected)
            ZipFile(target.toFile()).use { zip ->
                assertEquals(
                    "private bytes",
                    zip.getInputStream(zip.getEntry("client-overrides/mods/private.jar"))
                        .reader()
                        .readText(),
                )
            }
            assertTrue(root.resolve("mods/private.jar").exists())
        }
    }

    @Test
    fun `recognized resources are batched verified indexed and import through existing transaction`() =
        runBlocking {
            fixture { store, _, instance, root ->
                val payloads =
                    (0..100).associate { i ->
                        "mods/mod-$i.jar${if (i == 0) ".disabled" else ""}" to "payload $i"
                    }
                payloads.forEach { (path, value) -> write(root, path, value) }
                write(root, "config/中文.toml", "配置")
                MockWebServer().use { server ->
                    NetworkClient().use { network ->
                        val service = service(store, network, server)
                        val offline = service.previewMrpackExport(instance.id, metadata, false)
                        val resources = offline.files.filter { it.requiresPermission }
                        server.dispatcher =
                            object : okhttp3.mockwebserver.Dispatcher() {
                                override fun dispatch(
                                    request: okhttp3.mockwebserver.RecordedRequest
                                ): MockResponse {
                                    val body =
                                        com.google.gson.JsonParser.parseString(
                                                request.body.clone().readUtf8()
                                            )
                                            .asJsonObject
                                    val hashes = body.array("hashes").map { it.asString }.toSet()
                                    return MockResponse()
                                        .setBody(
                                            gson.toJson(
                                                JsonObject().apply {
                                                    resources
                                                        .filter { it.sha512 in hashes }
                                                        .forEach { add(it.sha512, remote(it)) }
                                                }
                                            )
                                        )
                                }
                            }
                        val plan = service.previewMrpackExport(instance.id, metadata)
                        assertEquals(2, server.requestCount)
                        repeat(2) {
                            val request = server.takeRequest()
                            assertEquals("/v2/version_files", request.path)
                            assertEquals("POST", request.method)
                            assertNull(request.getHeader("x-api-key"))
                        }
                        assertTrue(plan.warnings.isEmpty())
                        assertEquals(102, plan.defaultSelection.size)
                        assertTrue(plan.files.all { !it.requiresPermission })
                        val target = temporary.resolve("indexed.mrpack")
                        service.exportMrpack(plan, target, JobContext(store, "test"))
                        ZipFile(target.toFile()).use { zip ->
                            assertEquals(
                                setOf("modrinth.index.json", "client-overrides/config/中文.toml"),
                                zip.entries().asSequence().map { it.name }.toSet(),
                            )
                            val manifest =
                                readPackManifest(zip, temporary.resolve("imported"), "windows")
                            assertEquals(101, manifest.requiredFiles)
                            assertTrue(
                                manifest.index!!.array("files").any {
                                    it.asJsonObject.string("path").endsWith(".disabled")
                                }
                            )
                            assertTrue(
                                manifest.index.array("files").all {
                                    it.asJsonObject.obj("env").string("server") == "unsupported"
                                }
                            )
                            val destination =
                                temporary.resolve("imported").apply { createDirectories() }
                            val transaction = PackFileTransaction(store, destination)
                            try {
                                stagePackFiles(
                                    manifest,
                                    zip,
                                    transaction,
                                    destination,
                                    null,
                                    false,
                                    JobContext(store, "test"),
                                ) { artifact ->
                                    val relative =
                                        transaction.content
                                            .relativize(artifact.target)
                                            .joinToString("/")
                                    write(
                                        transaction.content,
                                        relative,
                                        payloads.getValue(relative),
                                    )
                                }
                                transaction.commit(JobContext(store, "test"))
                                payloads.forEach { (path, value) ->
                                    assertEquals(value, destination.resolve(path).readText())
                                }
                                assertEquals("配置", destination.resolve("config/中文.toml").readText())
                            } finally {
                                transaction.cleanup()
                            }
                        }
                    }
                }
            }
        }

    @Test
    fun `source metadata must match both hashes size and official upload host rules`() =
        runBlocking {
            fixture { store, _, instance, root ->
                write(root, "mods/test.jar")
                MockWebServer().use { server ->
                    NetworkClient().use { network ->
                        val service = service(store, network, server)
                        val file =
                            service.previewMrpackExport(instance.id, metadata, false).files.single()
                        val invalid =
                            listOf(
                                    "http://cdn.modrinth.com/test.jar",
                                    "https://cdn.modrinth.com.evil.example/test.jar",
                                    "https://user@cdn.modrinth.com/a.jar",
                                    "https://cdn.modrinth.com:8443/a.jar",
                                    "https://cdn.modrinth.com/a b.jar",
                                    "https://cdn.modrinth.com/a.jar#fragment",
                                    "https://edge.forgecdn.net/a.jar",
                                )
                                .map { remote(file, it) } +
                                listOf(
                                    remote(file).apply {
                                        array("files")
                                            .first()
                                            .asJsonObject
                                            .obj("hashes")
                                            .addProperty("sha1", "0".repeat(40))
                                    },
                                    remote(file).apply {
                                        array("files")
                                            .first()
                                            .asJsonObject
                                            .addProperty("size", file.size + 1)
                                    },
                                    remote(file).apply {
                                        array("files")
                                            .first()
                                            .asJsonObject
                                            .obj("hashes")
                                            .addProperty("sha512", "0".repeat(128))
                                    },
                                )
                        for (info in invalid) {
                            server.enqueue(
                                MockResponse().setBody(gson.toJson(mapOf(file.sha512 to info)))
                            )
                            val plan = service.previewMrpackExport(instance.id, metadata)
                            assertTrue(plan.defaultSelection.isEmpty())
                            assertNull(plan.files.single().downloadUrl)
                            assertTrue(plan.files.single().requiresPermission)
                        }
                    }
                }
            }
        }

    @Test
    fun `failed remote lookup is visible and never silently embeds resources`() = runBlocking {
        fixture { store, _, instance, root ->
            write(root, "mods/test.jar")
            MockWebServer().use { server ->
                NetworkClient().use { network ->
                    server.enqueue(
                        MockResponse().setResponseCode(403).setBody("secret-service-message")
                    )
                    val plan =
                        service(store, network, server).previewMrpackExport(instance.id, metadata)
                    assertEquals(1, plan.warnings.size)
                    assertFalse(plan.warnings.single().contains("secret-service-message"))
                    assertTrue(plan.defaultSelection.isEmpty())
                }
            }
        }
    }

    @Test
    fun `changed and missing files preserve existing output and remove partial archives`() =
        runBlocking {
            fixture { store, service, instance, root ->
                val source = write(root, "config/a.txt", "old")
                val plan = service.previewMrpackExport(instance.id, metadata, false)
                val target =
                    temporary.resolve("existing.mrpack").apply { writeText("original archive") }
                source.writeText("new")
                assertFailsWith<IllegalArgumentException> {
                    service.exportMrpack(plan, target, JobContext(store, "test"))
                }
                assertEquals("original archive", target.readText())
                source.deleteExisting()
                assertFailsWith<IllegalArgumentException> {
                    service.exportMrpack(plan, target, JobContext(store, "test"))
                }
                assertEquals("original archive", target.readText())
                assertTrue(temporary.listDirectoryEntries("*.part").isEmpty())
            }
        }

    @Test
    fun `component changes invalidate a prepared export`() = runBlocking {
        fixture { store, service, instance, root ->
            write(root, "config/a.txt")
            val plan = service.previewMrpackExport(instance.id, metadata, false)
            store.updateInstance(instance.id) {
                it.copy(loader = LoaderType.Fabric, loaderVersion = "0.16.14")
            }
            val target = temporary.resolve("changed.mrpack")
            assertFailsWith<IllegalArgumentException> {
                service.exportMrpack(plan, target, JobContext(store, "test"))
            }
            assertFalse(target.exists())
        }
    }

    @Test
    fun `output inside game or launcher data and invalid selections are refused`() = runBlocking {
        fixture { store, service, instance, root ->
            write(root, "config/a.txt")
            val plan = service.previewMrpackExport(instance.id, metadata, false)
            for (target in
                listOf(
                    root.resolve("out.mrpack"),
                    store.minecraftRoot.resolve("out.mrpack"),
                    store.dataDirectory.resolve("out.mrpack"),
                    temporary.resolve("out.zip"),
                )) {
                assertFailsWith<IllegalArgumentException> {
                    service.exportMrpack(plan, target, JobContext(store, "test"))
                }
                assertFalse(target.exists())
            }
            assertFailsWith<IllegalArgumentException> {
                service.exportMrpack(
                    plan,
                    temporary.resolve("out.mrpack"),
                    JobContext(store, "test"),
                    setOf("../private.json"),
                )
            }
        }
    }

    @Test
    fun `cancelled export creates no output or temporary archive`() = runBlocking {
        fixture { store, service, instance, root ->
            write(root, "config/a.txt")
            val plan = service.previewMrpackExport(instance.id, metadata, false)
            val target = temporary.resolve("cancelled.mrpack")
            val job = Job().apply { cancel() }
            assertFailsWith<CancellationException> {
                withContext(job) { service.exportMrpack(plan, target, JobContext(store, "test")) }
            }
            assertFalse(target.exists())
            assertTrue(temporary.listDirectoryEntries("*.part").isEmpty())
        }
    }

    @Test
    fun `all four loader dependencies and vanilla round trip without launcher specific metadata`() =
        runBlocking {
            fixture { store, service, instance, _ ->
                for (loader in LoaderType.entries) {
                    store.updateInstance(instance.id) {
                        it.copy(
                            loader = loader,
                            loaderVersion = if (loader == LoaderType.Vanilla) "" else "1.2.3",
                        )
                    }
                    val plan = service.previewMrpackExport(instance.id, metadata, false)
                    val target = temporary.resolve("${loader.name}.mrpack")
                    service.exportMrpack(plan, target, JobContext(store, "test"))
                    val preview = service.previewPack(target)
                    assertEquals(loader, preview.loader)
                    assertEquals(
                        if (loader == LoaderType.Vanilla) "" else "1.2.3",
                        preview.loaderVersion,
                    )
                }
            }
        }

    @Test
    fun `shared directory exports user content and external standard loaders with explicit versions`() =
        runBlocking {
            fixture { store, service, instance, _ ->
                store.updateInstance(instance.id) { it.copy(isolation = VersionIsolation.Disabled) }
                val root = store.instancePath(instance.id)
                write(root, "config/a.txt")
                write(root, "OtherLauncher/settings.json")
                write(root, "unknown-file.txt")
                val plan = service.previewMrpackExport(instance.id, metadata, false)
                assertEquals(listOf("config/a.txt"), plan.files.map { it.path })
                store.updateInstance(instance.id) {
                    it.copy(
                        externalProfile = true,
                        loader = LoaderType.Fabric,
                        loaderVersion = "0.16.14",
                    )
                }
                val external = service.previewMrpackExport(instance.id, metadata, false)
                assertEquals(LoaderType.Fabric, external.components.loader)
                assertEquals("0.16.14", external.components.loaderVersion)
                store.updateInstance(instance.id) {
                    it.copy(externalProfile = false, installed = false)
                }
                assertFailsWith<IllegalArgumentException> {
                    service.previewMrpackExport(instance.id, metadata, false)
                }
            }
        }

    @Test
    fun `user data can be selected explicitly while private credentials cannot`() = runBlocking {
        fixture { store, service, instance, root ->
            write(root, "saves/World/level.dat", "save")
            write(root, "screenshots/a.png")
            write(root, "credentials/account.json", "private")
            val plan = service.previewMrpackExport(instance.id, metadata, false)
            assertTrue(plan.defaultSelection.isEmpty())
            val target = temporary.resolve("selected.mrpack")
            service.exportMrpack(
                plan,
                target,
                JobContext(store, "test"),
                setOf("saves/World/level.dat"),
            )
            ZipFile(target.toFile()).use { zip ->
                assertEquals(
                    setOf("modrinth.index.json", "client-overrides/saves/World/level.dat"),
                    zip.entries().asSequence().map { it.name }.toSet(),
                )
            }
            assertFailsWith<IllegalArgumentException> {
                service.exportMrpack(
                    plan,
                    target,
                    JobContext(store, "test"),
                    setOf("credentials/account.json"),
                )
            }
        }
    }

    @Test
    fun `invalid package metadata and missing loader versions fail before export`() = runBlocking {
        fixture { store, service, instance, _ ->
            for (info in
                listOf(
                    metadata.copy(name = " "),
                    metadata.copy(version = ""),
                    metadata.copy(summary = "x".repeat(2001)),
                    metadata.copy(name = "bad\u0000name"),
                )) {
                assertFailsWith<IllegalArgumentException> {
                    service.previewMrpackExport(instance.id, info, false)
                }
            }
            store.updateInstance(instance.id) {
                it.copy(loader = LoaderType.Forge, loaderVersion = "")
            }
            assertFailsWith<IllegalArgumentException> {
                service.previewMrpackExport(instance.id, metadata, false)
            }
        }
    }
}
