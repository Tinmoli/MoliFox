// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证各导出格式的归档结构、可导入性、可选内容与私密文件排除。

package dev.molifox.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.reader
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackExportFormatsTest {
    @TempDir lateinit var temporary: Path
    private val metadata = MrpackExportMetadata("多格式包", "1.2.3", "中文说明")

    private suspend fun fixture(
        loader: LoaderType = LoaderType.Fabric,
        loaderVersion: String = "0.15.11",
        action: suspend (LauncherStore, GameInstance, Path) -> Unit,
    ) {
        val store = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))
        val instance =
            GameInstance(
                name = "Source",
                gameVersion = "1.20.1",
                versionId = "1.20.1",
                loader = loader,
                loaderVersion = loaderVersion,
                installed = true,
                isolation = VersionIsolation.Enabled,
            )
        store.mutate { it.copy(instances = listOf(instance)) }
        val root = store.instancePath(instance.id).apply { createDirectories() }
        action(store, instance, root)
    }

    private fun service(
        store: LauncherStore,
        network: NetworkClient,
        server: MockWebServer? = null,
    ) = ContentService(
        store,
        network,
        MinecraftInstaller(store, network, JavaService(store, network)),
        server?.loopbackUrl("/v2")?.toString() ?: "https://api.modrinth.com/v2",
    )

    private fun write(
        root: Path,
        path: String,
        content: String = path,
    ) = root.resolve(path).apply {
        parent.createDirectories()
        writeText(content)
    }

    private fun listing(zip: ZipFile): Set<String> =
        zip
            .entries()
            .asSequence()
            .map { it.name }
            .toSet()

    @Test
    fun `curseforge archive has manifest and overrides and imports back`() =
        runBlocking {
            fixture { store, instance, root ->
                write(root, "mods/local.jar", "jar-bytes")
                write(root, "config/a.toml", "cfg")
                write(root, "saves/World/level.dat", "save")
                write(root, "options.txt", "guiScale:2")
                write(root, "credentials/secret.json", "TOKEN")
                write(root, "launcher_profiles.json", "{}")
                write(root, "usercache.json", "[]")
                write(root, "logs/latest.log", "log")
                NetworkClient().use { network ->
                    val service = service(store, network)
                    val plan = service.previewMrpackExport(instance.id, metadata, false)
                    val options = PackExportOptions(includeLocalResources = true)
                    val local = exportLocalResourcePaths(plan, PackExportFormat.CurseForge)
                    assertEquals(setOf("mods/local.jar"), local)
                    val selection =
                        exportDefaultSelection(plan, PackExportFormat.CurseForge, options)
                    val target = temporary.resolve("curseforge.zip")
                    service.exportMrpack(
                        plan,
                        target,
                        JobContext(store, "export"),
                        selection,
                        local,
                        PackExportFormat.CurseForge,
                        options,
                    )
                    ZipFile(target.toFile()).use { zip ->
                        assertEquals(
                            setOf(
                                "manifest.json",
                                "overrides/",
                                "overrides/config/a.toml",
                                "overrides/mods/local.jar",
                            ),
                            listing(zip),
                        )
                        val manifest = requireNotNull(packJson(zip, "manifest.json"))
                        assertEquals("minecraftModpack", manifest.string("manifestType"))
                        assertEquals("1", manifest.string("manifestVersion"))
                        assertEquals(metadata.name, manifest.string("name"))
                        assertEquals(metadata.version, manifest.string("version"))
                        assertEquals("overrides", manifest.string("overrides"))
                        assertEquals("1.20.1", manifest.obj("minecraft").string("version"))
                        assertEquals(
                            "fabric-0.15.11",
                            manifest
                                .obj("minecraft")
                                .array("modLoaders")[0]
                                .asJsonObject
                                .string("id"),
                        )
                        assertEquals(0, manifest.array("files").size())
                        val parsed = readPackManifest(zip, temporary.resolve("import"), "windows")
                        assertEquals(metadata.name, parsed.request.name)
                        assertEquals("1.20.1", parsed.request.gameVersion)
                        assertEquals(LoaderType.Fabric, parsed.request.loader)
                        assertEquals("0.15.11", parsed.request.loaderVersion)
                        assertTrue("overrides/" in parsed.overridePrefixes)
                    }
                }
            }
        }

    @Test
    fun `curseforge managed files use project ids and unknown mods stay in overrides`() =
        runBlocking {
            fixture { store, instance, root ->
                write(root, "mods/matched.jar", "matched-mod-bytes")
                write(root, "mods/other.jar", "other")
                write(root, "config/a.toml", "cfg")
                val matchedPath = root.resolve("mods/matched.jar")
                val matchedSize = matchedPath.fileSize()
                val matchedSha1 = digest(matchedPath)
                MockWebServer().use { server ->
                    server.dispatcher =
                        object : Dispatcher() {
                            override fun dispatch(request: RecordedRequest): MockResponse {
                                if (request.path != "/v1/fingerprints/432") {
                                    return MockResponse().setResponseCode(404)
                                }
                                val body =
                                    JsonParser
                                        .parseString(request.body.clone().readUtf8())
                                        .asJsonObject
                                val matches = JsonArray()
                                for (element in body.array("fingerprints")) {
                                    matches.add(
                                        JsonObject().apply {
                                            add("id", JsonPrimitive(1))
                                            add(
                                                "file",
                                                JsonObject().apply {
                                                    addProperty("id", 4131L)
                                                    addProperty("modId", 238222L)
                                                    addProperty(
                                                        "fileFingerprint",
                                                        element.asLong,
                                                    )
                                                    addProperty("fileLength", matchedSize)
                                                    add(
                                                        "hashes",
                                                        gson.toJsonTree(
                                                            listOf(
                                                                mapOf(
                                                                    "algo" to 1,
                                                                    "value" to matchedSha1,
                                                                ),
                                                            ),
                                                        ),
                                                    )
                                                },
                                            )
                                        },
                                    )
                                }
                                val payload = JsonObject()
                                payload.add(
                                    "data",
                                    JsonObject().apply { add("exactMatches", matches) },
                                )
                                return MockResponse().setBody(gson.toJson(payload))
                            }
                        }
                    server.start()
                    store.mutate {
                        it.copy(
                            settings =
                                it.settings.copy(
                                    curseForgeBaseUrl =
                                        server.loopbackUrl("").toString().trimEnd('/'),
                                ),
                        )
                    }
                    NetworkClient().use { network ->
                        val service = service(store, network, server)
                        val plan =
                            service.previewMrpackExport(
                                instance.id,
                                metadata,
                                false,
                                resolveCurseForge = true,
                            )
                        val matched = plan.files.single { it.path == "mods/matched.jar" }
                        assertEquals(238222L, matched.curseForgeProjectId)
                        assertEquals(4131L, matched.curseForgeFileId)
                        assertNull(
                            plan.files.single { it.path == "mods/other.jar" }.curseForgeProjectId,
                        )
                        val options = PackExportOptions(includeLocalResources = true)
                        val local = exportLocalResourcePaths(plan, PackExportFormat.CurseForge)
                        assertEquals(setOf("mods/other.jar"), local)
                        val target = temporary.resolve("managed.zip")
                        service.exportMrpack(
                            plan,
                            target,
                            JobContext(store, "export"),
                            exportDefaultSelection(plan, PackExportFormat.CurseForge, options),
                            local,
                            PackExportFormat.CurseForge,
                            options,
                        )
                        ZipFile(target.toFile()).use { zip ->
                            val manifest = requireNotNull(packJson(zip, "manifest.json"))
                            val files = manifest.array("files").map { it.asJsonObject }
                            assertEquals(1, files.size)
                            assertEquals("238222", files.single().string("projectID"))
                            assertEquals("4131", files.single().string("fileID"))
                            assertTrue(files.single().get("required").asBoolean)
                            assertNull(zip.getEntry("overrides/mods/matched.jar"))
                            assertNotNull(zip.getEntry("overrides/mods/other.jar"))
                            // 受管清单 + overrides 仍然能被本启动器按 CurseForge 读取。
                            val parsed =
                                readPackManifest(zip, temporary.resolve("import"), "windows")
                            assertEquals(1, parsed.requiredFiles)
                            assertEquals(LoaderType.Fabric, parsed.request.loader)
                        }
                    }
                }
            }
        }

    @Test
    fun `multimc archive follows instance format and imports back`() =
        runBlocking {
            fixture(LoaderType.Forge, "47.2.0") { store, instance, root ->
                write(root, "mods/local.jar", "jar")
                write(root, "config/a.toml", "cfg")
                NetworkClient().use { network ->
                    val service = service(store, network)
                    val plan = service.previewMrpackExport(instance.id, metadata, false)
                    val options = PackExportOptions(includeLocalResources = true)
                    val target = temporary.resolve("prism.zip")
                    service.exportMrpack(
                        plan,
                        target,
                        JobContext(store, "export"),
                        exportDefaultSelection(plan, PackExportFormat.MultiMc, options),
                        setOf("mods/local.jar"),
                        PackExportFormat.MultiMc,
                        options,
                    )
                    ZipFile(target.toFile()).use { zip ->
                        assertEquals(
                            setOf(
                                "instance.cfg",
                                "mmc-pack.json",
                                ".minecraft/",
                                ".minecraft/config/a.toml",
                                ".minecraft/mods/local.jar",
                            ),
                            listing(zip),
                        )
                        val config =
                            zip
                                .getInputStream(requireNotNull(zip.getEntry("instance.cfg")))
                                .reader()
                                .readText()
                        assertTrue(config.contains("[General]"))
                        assertTrue(config.contains("InstanceType=OneSix"))
                        assertTrue(config.lineSequence().any { it.trim() == "name=多格式包" })
                        val pack = requireNotNull(packJson(zip, "mmc-pack.json"))
                        assertEquals("1", pack.string("formatVersion"))
                        val components = pack.array("components").map { it.asJsonObject }
                        assertEquals(
                            "1.20.1",
                            components
                                .single {
                                    it.string(
                                        "uid",
                                    ) == "net.minecraft"
                                }.string("version"),
                        )
                        assertEquals(
                            "47.2.0",
                            components
                                .single {
                                    it.string(
                                        "uid",
                                    ) == "net.minecraftforge"
                                }.string("version"),
                        )
                        val parsed = readPackManifest(zip, temporary.resolve("import"), "windows")
                        assertEquals(metadata.name, parsed.request.name)
                        assertEquals("1.20.1", parsed.request.gameVersion)
                        assertEquals(LoaderType.Forge, parsed.request.loader)
                        assertEquals("47.2.0", parsed.request.loaderVersion)
                        assertEquals(".minecraft/", parsed.payloadPrefix)
                    }
                }
            }
        }

    @Test
    fun `mcbbs archive keeps overrides and imports back`() =
        runBlocking {
            fixture { store, instance, root ->
                write(root, "mods/local.jar", "jar")
                write(root, "config/a.toml", "cfg")
                NetworkClient().use { network ->
                    val service = service(store, network)
                    val plan = service.previewMrpackExport(instance.id, metadata, false)
                    val options = PackExportOptions(includeLocalResources = true)
                    val target = temporary.resolve("mcbbs.zip")
                    service.exportMrpack(
                        plan,
                        target,
                        JobContext(store, "export"),
                        exportDefaultSelection(plan, PackExportFormat.Mcbbs, options),
                        setOf("mods/local.jar"),
                        PackExportFormat.Mcbbs,
                        options,
                    )
                    ZipFile(target.toFile()).use { zip ->
                        assertEquals(
                            setOf(
                                "mcbbs.packmeta",
                                "overrides/",
                                "overrides/config/a.toml",
                                "overrides/mods/local.jar",
                            ),
                            listing(zip),
                        )
                        val info = requireNotNull(packJson(zip, "mcbbs.packmeta"))
                        assertEquals("minecraftModpack", info.string("manifestType"))
                        assertEquals(2, info.array("files").size())
                        val parsed = readPackManifest(zip, temporary.resolve("import"), "windows")
                        assertEquals(metadata.name, parsed.request.name)
                        assertEquals(LoaderType.Fabric, parsed.request.loader)
                        assertEquals(2, parsed.checkedFiles.size)
                    }
                }
            }
        }

    @Test
    fun `backup includes saves screenshots and optional logs but is not a modpack`() =
        runBlocking {
            fixture { store, instance, root ->
                write(root, "mods/a.jar", "jar")
                write(root, "config/a.toml", "cfg")
                write(root, "saves/World/level.dat", "save")
                write(root, "screenshots/a.png", "png")
                write(root, "options.txt", "guiScale:2")
                write(root, "logs/latest.log", "plain log line")
                write(root, "crash-reports/crash.txt", "crash")
                write(root, "credentials/account.json", "SECRET")
                write(root, "tokens/one.txt", "SECRET")
                write(root, "launcher_profiles.json", "{}")
                write(root, "usercache.json", "[]")
                write(root, "MoliFox/internal.json", "SECRET")
                NetworkClient().use { network ->
                    val service = service(store, network)
                    val withoutLogs = service.previewMrpackExport(instance.id, metadata, false)
                    assertTrue(withoutLogs.files.none { it.path.startsWith("logs/") })
                    val defaults = PackExportOptions.defaultsFor(PackExportFormat.Backup)
                    val selection =
                        exportDefaultSelection(withoutLogs, PackExportFormat.Backup, defaults)
                    assertEquals(
                        setOf(
                            "mods/a.jar",
                            "config/a.toml",
                            "saves/World/level.dat",
                            "screenshots/a.png",
                            "options.txt",
                        ),
                        selection,
                    )
                    val target = temporary.resolve("backup.zip")
                    service.exportMrpack(
                        withoutLogs,
                        target,
                        JobContext(store, "export"),
                        selection,
                        emptySet(),
                        PackExportFormat.Backup,
                        defaults,
                    )
                    ZipFile(target.toFile()).use { zip ->
                        val names = listing(zip)
                        assertEquals(
                            setOf(
                                "molifox-backup.json",
                                "minecraft/",
                                "minecraft/config/a.toml",
                                "minecraft/mods/a.jar",
                                "minecraft/options.txt",
                                "minecraft/saves/World/level.dat",
                                "minecraft/screenshots/a.png",
                            ),
                            names,
                        )
                        assertTrue(
                            names.none {
                                it.contains("credentials") ||
                                    it.contains("tokens") ||
                                    it.contains("launcher_profiles") ||
                                    it.contains("usercache") ||
                                    it.contains("MoliFox") ||
                                    it.contains("logs/")
                            },
                        )
                        val info = requireNotNull(packJson(zip, "molifox-backup.json"))
                        assertEquals("molifox-backup", info.string("type"))
                        assertEquals(5, info.array("files").size())
                        // 完整备份不是整合包：不能按整合包读回。
                        assertFailsWith<IllegalArgumentException> {
                            readPackManifest(zip, temporary.resolve("import"), "windows")
                        }
                    }
                    // 勾选日志后才扫描并写入 logs/ 与 crash-reports/。
                    val withLogs =
                        service.previewMrpackExport(
                            instance.id,
                            metadata,
                            false,
                            includeLogs = true,
                        )
                    val logOptions =
                        PackExportOptions
                            .defaultsFor(
                                PackExportFormat.Backup,
                            ).copy(includeLogs = true)
                    val logTarget = temporary.resolve("backup-logs.zip")
                    service.exportMrpack(
                        withLogs,
                        logTarget,
                        JobContext(store, "export"),
                        exportDefaultSelection(withLogs, PackExportFormat.Backup, logOptions),
                        emptySet(),
                        PackExportFormat.Backup,
                        logOptions,
                    )
                    ZipFile(logTarget.toFile()).use { zip ->
                        val names = listing(zip)
                        assertTrue("minecraft/logs/latest.log" in names)
                        assertTrue("minecraft/crash-reports/crash.txt" in names)
                    }
                    // 日志不能混进可导入的整合包格式。
                    assertFailsWith<IllegalArgumentException> {
                        service.exportMrpack(
                            withLogs,
                            temporary.resolve("not-backup.zip"),
                            JobContext(store, "export"),
                            setOf("logs/latest.log"),
                            emptySet(),
                            PackExportFormat.Mcbbs,
                            logOptions,
                        )
                    }
                }
            }
        }

    @Test
    fun `logs containing credentials are skipped and warned about`() =
        runBlocking {
            fixture { store, instance, root ->
                write(root, "config/a.toml", "cfg")
                write(root, "logs/latest.log", "accessToken=abcdefghijklmnopqrstuvwx")
                write(root, "logs/clean.log", "plain")
                NetworkClient().use { network ->
                    val service = service(store, network)
                    val plan =
                        service.previewMrpackExport(
                            instance.id,
                            metadata,
                            false,
                            includeLogs = true,
                        )
                    assertTrue(plan.warnings.any { it.contains("登录凭据") })
                    assertEquals(
                        listOf("logs/clean.log"),
                        plan.files.map { it.path }.filter { it.startsWith("logs/") },
                    )
                    val options =
                        PackExportOptions
                            .defaultsFor(
                                PackExportFormat.Backup,
                            ).copy(includeLogs = true)
                    val target = temporary.resolve("safe-backup.zip")
                    service.exportMrpack(
                        plan,
                        target,
                        JobContext(store, "export"),
                        exportDefaultSelection(plan, PackExportFormat.Backup, options),
                        emptySet(),
                        PackExportFormat.Backup,
                        options,
                    )
                    ZipFile(target.toFile()).use { zip ->
                        val names = listing(zip)
                        assertTrue("minecraft/logs/clean.log" in names)
                        assertFalse("minecraft/logs/latest.log" in names)
                    }
                }
            }
        }

    @Test
    fun `enabled mods only excludes disabled mods from every format`() =
        runBlocking {
            fixture { store, instance, root ->
                write(root, "mods/on.jar", "on")
                write(root, "mods/off.jar.disabled", "off")
                write(root, "config/a.toml", "cfg")
                NetworkClient().use { network ->
                    val service = service(store, network)
                    val plan = service.previewMrpackExport(instance.id, metadata, false)
                    assertTrue(plan.files.any { it.path == "mods/off.jar.disabled" })
                    val options =
                        PackExportOptions(includeLocalResources = true, enabledModsOnly = true)
                    val selection =
                        exportDefaultSelection(plan, PackExportFormat.MultiMc, options) +
                            "mods/off.jar.disabled"
                    val target = temporary.resolve("enabled.zip")
                    service.exportMrpack(
                        plan,
                        target,
                        JobContext(store, "export"),
                        selection,
                        setOf("mods/on.jar", "mods/off.jar.disabled"),
                        PackExportFormat.MultiMc,
                        options,
                    )
                    ZipFile(target.toFile()).use { zip ->
                        val names = listing(zip)
                        assertTrue(".minecraft/mods/on.jar" in names)
                        assertFalse(".minecraft/mods/off.jar.disabled" in names)
                    }
                }
            }
        }

    @Test
    fun `optional switches drive the default selection`() =
        runBlocking {
            fixture { store, instance, root ->
                write(root, "saves/World/level.dat", "save")
                write(root, "screenshots/a.png", "png")
                write(root, "options.txt", "cfg")
                write(root, "config/a.toml", "cfg")
                NetworkClient().use { network ->
                    val service = service(store, network)
                    val plan = service.previewMrpackExport(instance.id, metadata, false)
                    val base =
                        exportDefaultSelection(plan, PackExportFormat.Modrinth, PackExportOptions())
                    assertEquals(setOf("config/a.toml"), base)
                    val all =
                        exportDefaultSelection(
                            plan,
                            PackExportFormat.Modrinth,
                            PackExportOptions(
                                includeSaves = true,
                                includeScreenshots = true,
                                includeOptionsTxt = true,
                            ),
                        )
                    assertEquals(
                        setOf(
                            "config/a.toml",
                            "saves/World/level.dat",
                            "screenshots/a.png",
                            "options.txt",
                        ),
                        all,
                    )
                }
            }
        }

    @Test
    fun `cancelling before the write leaves no target and no part file`() =
        runBlocking {
            fixture { store, instance, root ->
                write(root, "config/a.toml", "cfg")
                write(root, "mods/a.jar", "jar")
                NetworkClient().use { network ->
                    val service = service(store, network)
                    val plan = service.previewMrpackExport(instance.id, metadata, false)
                    for (
                    format in
                    listOf(
                        PackExportFormat.CurseForge,
                        PackExportFormat.MultiMc,
                        PackExportFormat.Mcbbs,
                        PackExportFormat.Backup,
                    )
                    ) {
                        val target = temporary.resolve("cancelled-${format.name}.zip")
                        val job = Job().apply { cancel() }
                        assertFailsWith<CancellationException> {
                            withContext(job) {
                                service.exportMrpack(
                                    plan,
                                    target,
                                    JobContext(store, "export"),
                                    plan.files.map { it.path }.toSet(),
                                    plan.files.map { it.path }.toSet(),
                                    format,
                                    PackExportOptions(includeLocalResources = true),
                                )
                            }
                        }
                        assertFalse(target.exists())
                        assertTrue(temporary.listDirectoryEntries("*.part").isEmpty())
                    }
                }
            }
        }

    @Test
    fun `written archives are re-opened and required entries asserted`() =
        runBlocking {
            fixture { store, instance, _ ->
                NetworkClient().use { network ->
                    val service = service(store, network)
                    val plan = service.previewMrpackExport(instance.id, metadata, false)
                    val layout =
                        buildPackExportLayout(
                            PackExportFormat.CurseForge,
                            plan,
                            PackExportOptions(),
                            emptyList(),
                            null,
                        )
                    assertEquals(listOf("manifest.json", "overrides/"), layout.requiredEntries)
                    val broken = temporary.resolve("broken.zip")
                    ZipOutputStream(broken.outputStream()).use { zip ->
                        zip.putNextEntry(ZipEntry("manifest.json"))
                        zip.write(layout.documents.single().second)
                        zip.closeEntry()
                    }
                    assertFailsWith<IllegalArgumentException> {
                        // 缺少 overrides/ 目录条目：写后校验必须拒绝。
                        validatePackExport(
                            broken,
                            PackExportFormat.CurseForge,
                            layout,
                            plan,
                            PackExportOptions(),
                            emptySet(),
                        )
                    }
                }
            }
        }

    @Test
    fun `selectable entries cover every direct child and hide launcher data`() =
        runBlocking {
            fixture { store, instance, root ->
                write(root, "mods/a.jar", "jar-bytes")
                write(root, "mods/1.12.2/b.jar", "jar2")
                write(root, "config/a.toml", "cfg")
                write(root, "resourcepacks/rp.zip", "rp")
                write(root, "saves/World/level.dat", "save")
                write(root, "screenshots/a.png", "png")
                write(root, "customnpcs/scripts/npc.json", "npc")
                write(root, "journeymap/data/map.bin", "map")
                write(root, "cache/tmp.bin", "cache")
                write(root, ".mixin.out/x.class", "mix")
                write(root, "options.txt", "opt")
                write(root, "servers.dat", "srv")
                write(root, "loose.dat", "loose")
                write(root, "logs/latest.log", "log!")
                write(root, "accounts/acc.json", "SECRET")
                write(root, "credentials.json", "SECRET")
                write(root, "launcher_profiles.json", "{}")
                write(root, "usercache.json", "[]")
                write(root, "MoliFox/internal.json", "SECRET")
                write(root, ".molifox-cache/x", "SECRET")
                write(root, "pending.part", "partial")
                write(root, "${instance.versionId}.json", "{}")
                write(root, "${instance.versionId}.jar", "jar")
                write(root, "${instance.versionId}.json.molifox-backup-20260101-000000", "{}")
                NetworkClient().use { network ->
                    val service = service(store, network)
                    val scan = service.exportEntries(instance.id).last()
                    assertEquals(
                        listOf(
                            ".mixin.out",
                            "cache",
                            "config",
                            "customnpcs",
                            "journeymap",
                            "logs",
                            "mods",
                            "resourcepacks",
                            "saves",
                            "screenshots",
                            "loose.dat",
                            "options.txt",
                            "servers.dat",
                        ),
                        scan.entries.map { it.name },
                    )
                    assertEquals(
                        setOf(
                            ".molifox-cache",
                            "1.20.1.jar",
                            "1.20.1.json",
                            "1.20.1.json.molifox-backup-20260101-000000",
                            "MoliFox",
                            "accounts",
                            "credentials.json",
                            "launcher_profiles.json",
                            "pending.part",
                            "usercache.json",
                        ),
                        scan.omitted.toSet(),
                    )
                    assertTrue(scan.entries.none { it.scanning })
                    val mods = scan.entries.single { it.name == "mods" }
                    assertEquals(2L, mods.fileCount)
                    assertEquals(("jar-bytes".length + "jar2".length).toLong(), mods.totalBytes)
                    val options = scan.entries.single { it.name == "options.txt" }
                    assertFalse(options.directory)
                    assertEquals("opt".length.toLong(), options.totalBytes)
                    val logs = scan.entries.single { it.name == "logs" }
                    assertTrue(logs.backupOnly)
                    assertEquals(1L, logs.fileCount)
                    assertEquals("log!".length.toLong(), logs.totalBytes)
                }
            }
        }

    @Test
    fun `default checked entries are the five portable ones for every format`() =
        runBlocking {
            fixture { store, instance, root ->
                write(root, "mods/a.jar", "jar")
                write(root, "config/a.toml", "cfg")
                write(root, "resourcepacks/rp.zip", "rp")
                write(root, "shaderpacks/sp.zip", "sp")
                write(root, "options.txt", "opt")
                write(root, "saves/World/level.dat", "save")
                write(root, "screenshots/a.png", "png")
                write(root, "servers.dat", "srv")
                write(root, "customnpcs/scripts/npc.json", "npc")
                write(root, "logs/latest.log", "log")
                write(root, ".mixin.out/x", "mix")
                NetworkClient().use { network ->
                    val service = service(store, network)
                    val scan = service.exportEntries(instance.id).last()
                    val defaults = defaultExportEntries(scan.entries)
                    assertEquals(
                        setOf("mods", "config", "resourcepacks", "shaderpacks", "options.txt"),
                        defaults,
                    )
                    // 手动改过的行在切换格式后保持原样：默认值与格式无关，不重新套用。
                    val manual = (defaults - "resourcepacks") + "saves"
                    for (format in PackExportFormat.entries) {
                        assertEquals(manual, retainExportSelection(scan.entries, manual))
                    }
                    assertFalse("saves" in defaults)
                    assertFalse("logs" in defaults)
                    assertFalse(".mixin.out" in defaults)
                    assertFalse("customnpcs" in defaults)
                }
            }
        }

    @Test
    fun `selected top level entries define exactly the archive payload`() =
        runBlocking {
            fixture { store, instance, root ->
                write(root, "mods/a.jar", "jar")
                write(root, "config/a.toml", "cfg")
                write(root, "saves/World/level.dat", "save")
                write(root, "options.txt", "opt")
                NetworkClient().use { network ->
                    val service = service(store, network)
                    val plan = service.previewMrpackExport(instance.id, metadata, false)
                    val options = PackExportOptions(includeLocalResources = true)
                    val selection =
                        exportSelectedPaths(
                            plan,
                            PackExportFormat.MultiMc,
                            options,
                            setOf("mods", "options.txt"),
                        )
                    val target = temporary.resolve("subset.zip")
                    service.exportMrpack(
                        plan,
                        target,
                        JobContext(store, "export"),
                        selection,
                        setOf("mods/a.jar"),
                        PackExportFormat.MultiMc,
                        options,
                    )
                    ZipFile(target.toFile()).use { zip ->
                        assertEquals(
                            setOf(
                                "instance.cfg",
                                "mmc-pack.json",
                                ".minecraft/",
                                ".minecraft/mods/a.jar",
                                ".minecraft/options.txt",
                            ),
                            listing(zip),
                        )
                    }
                }
            }
        }

    @Test
    fun `backup without saves contains no saves entries`() =
        runBlocking {
            fixture { store, instance, root ->
                write(root, "mods/a.jar", "jar")
                write(root, "config/a.toml", "cfg")
                write(root, "saves/World/level.dat", "save")
                write(root, "screenshots/a.png", "png")
                write(root, "options.txt", "opt")
                NetworkClient().use { network ->
                    val service = service(store, network)
                    val plan = service.previewMrpackExport(instance.id, metadata, false)
                    val scan = service.exportEntries(instance.id).last()
                    val keys = scan.entries.map { it.key }.toSet() - "saves"
                    val options = PackExportOptions.defaultsFor(PackExportFormat.Backup)
                    val selection =
                        exportSelectedPaths(plan, PackExportFormat.Backup, options, keys)
                    val target = temporary.resolve("no-saves.zip")
                    service.exportMrpack(
                        plan,
                        target,
                        JobContext(store, "export"),
                        selection,
                        emptySet(),
                        PackExportFormat.Backup,
                        options,
                    )
                    ZipFile(target.toFile()).use { zip ->
                        val names = listing(zip)
                        assertFalse("minecraft/saves/World/level.dat" in names)
                        assertTrue(names.none { it.startsWith("minecraft/saves/") })
                        assertTrue("minecraft/mods/a.jar" in names)
                        assertTrue("minecraft/screenshots/a.png" in names)
                    }
                }
            }
        }

    @Test
    fun `empty selection is refused with a clear message`() =
        runBlocking {
            fixture { store, instance, root ->
                write(root, "mods/a.jar", "jar")
                write(root, "options.txt", "opt")
                NetworkClient().use { network ->
                    val service = service(store, network)
                    val plan = service.previewMrpackExport(instance.id, metadata, false)
                    for (format in listOf(PackExportFormat.Modrinth, PackExportFormat.Backup)) {
                        val failure =
                            assertFailsWith<IllegalArgumentException> {
                                service.exportMrpack(
                                    plan,
                                    temporary.resolve("empty.${format.extension}"),
                                    JobContext(store, "export"),
                                    emptySet(),
                                    emptySet(),
                                    format,
                                    PackExportOptions(includeLocalResources = true),
                                )
                            }
                        assertTrue(failure.message.orEmpty().contains("至少选择"))
                    }
                }
            }
        }

    @Test
    fun `validation only asserts the selected entries`() =
        runBlocking {
            fixture { store, instance, root ->
                write(root, "mods/a.jar", "jar")
                write(root, "config/a.toml", "cfg")
                NetworkClient().use { network ->
                    val service = service(store, network)
                    val plan = service.previewMrpackExport(instance.id, metadata, false)
                    val onlyMod = plan.files.filter { it.path == "mods/a.jar" }
                    val layout =
                        buildPackExportLayout(
                            PackExportFormat.MultiMc,
                            plan,
                            PackExportOptions(),
                            onlyMod,
                            null,
                        )
                    val archive = temporary.resolve("selected.zip")
                    ZipOutputStream(archive.outputStream()).use { zip ->
                        for ((name, bytes) in layout.documents) {
                            zip.putNextEntry(ZipEntry(name))
                            zip.write(bytes)
                            zip.closeEntry()
                        }
                        for (directory in layout.directories) {
                            zip.putNextEntry(ZipEntry(directory))
                            zip.closeEntry()
                        }
                        for ((archivePath, file) in layout.payload) {
                            zip.putNextEntry(ZipEntry(archivePath))
                            zip.write(root.resolve(file.path).readBytes())
                            zip.closeEntry()
                        }
                    }
                    // 未勾选的 config/a.toml 不应被要求存在。
                    validatePackExport(
                        archive,
                        PackExportFormat.MultiMc,
                        layout,
                        plan,
                        PackExportOptions(),
                        setOf("mods/a.jar"),
                    )
                    assertFailsWith<IllegalArgumentException> {
                        validatePackExport(
                            archive,
                            PackExportFormat.MultiMc,
                            layout,
                            plan,
                            PackExportOptions(),
                            setOf("mods/a.jar", "config/a.toml"),
                        )
                    }
                }
            }
        }
}
