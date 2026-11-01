// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验证 CurseForge / MultiMC / MCBBS 三种整合包导出的归档结构与可导入性。

package dev.molifox.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.fileSize
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackExportFormatsTest : PackExportTestSupport() {
    @Test
    fun `curseforge archive has manifest and overrides and imports back`() =
        runBlocking {
            fixture { store, instance, root ->
                writeCurseForgeFixture(root)
                NetworkClient().use { network ->
                    assertCurseForgeExport(store, instance, service(store, network))
                }
            }
        }

    private fun writeCurseForgeFixture(root: Path) {
        write(root, "mods/local.jar", "jar-bytes")
        write(root, "config/a.toml", "cfg")
        write(root, "saves/World/level.dat", "save")
        write(root, "options.txt", "guiScale:2")
        write(root, "credentials/secret.json", "TOKEN")
        write(root, "launcher_profiles.json", "{}")
        write(root, "usercache.json", "[]")
        write(root, "logs/latest.log", "log")
    }

    /** 导出 CurseForge 包：只带本地资源，清单指向 overrides/，并且能按 CurseForge 读回。 */
    private suspend fun assertCurseForgeExport(
        store: LauncherStore,
        instance: GameInstance,
        service: ContentService,
    ) {
        val plan = service.previewMrpackExport(instance.id, metadata, false)
        val options = PackExportOptions(includeLocalResources = true)
        val local = exportLocalResourcePaths(plan, PackExportFormat.CurseForge)
        assertEquals(setOf("mods/local.jar"), local)
        val target = temporary.resolve("curseforge.zip")
        service.exportMrpack(
            plan,
            target,
            JobContext(store, "export"),
            exportDefaultSelection(plan, PackExportFormat.CurseForge, options),
            local,
            PackExportFormat.CurseForge,
            options,
        )
        ZipFile(target.toFile()).use { zip -> assertCurseForgeArchive(zip) }
    }

    private fun assertCurseForgeArchive(zip: ZipFile) {
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
                    server.dispatcher = curseForgeFingerprintDispatcher(matchedSize, matchedSha1)
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
                        assertManagedCurseForgeExport(
                            store,
                            instance,
                            service(store, network, server),
                        )
                    }
                }
            }
        }

    /** 只回应 CurseForge 指纹查询：命中就带上项目/文件编号，其余一律 404。 */
    private fun curseForgeFingerprintDispatcher(
        matchedSize: Long,
        matchedSha1: String,
    ): Dispatcher =
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
                    matches.add(curseForgeMatch(element.asLong, matchedSize, matchedSha1))
                }
                val payload = JsonObject()
                payload.add(
                    "data",
                    JsonObject().apply { add("exactMatches", matches) },
                )
                return MockResponse().setBody(gson.toJson(payload))
            }
        }

    private fun curseForgeMatch(
        fingerprint: Long,
        fileLength: Long,
        sha1: String,
    ): JsonObject =
        JsonObject().apply {
            add("id", JsonPrimitive(1))
            add(
                "file",
                JsonObject().apply {
                    addProperty("id", 4131L)
                    addProperty("modId", 238222L)
                    addProperty("fileFingerprint", fingerprint)
                    addProperty("fileLength", fileLength)
                    add(
                        "hashes",
                        gson.toJsonTree(
                            listOf(
                                mapOf(
                                    "algo" to 1,
                                    "value" to sha1,
                                ),
                            ),
                        ),
                    )
                },
            )
        }

    private suspend fun assertManagedCurseForgeExport(
        store: LauncherStore,
        instance: GameInstance,
        service: ContentService,
    ) {
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
        assertManagedCurseForgeArchive(target)
    }

    private fun assertManagedCurseForgeArchive(target: Path) {
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
            val parsed = readPackManifest(zip, temporary.resolve("import"), "windows")
            assertEquals(1, parsed.requiredFiles)
            assertEquals(LoaderType.Fabric, parsed.request.loader)
        }
    }

    @Test
    fun `multimc archive follows instance format and imports back`() =
        runBlocking {
            fixture(LoaderType.Forge, "47.2.0") { store, instance, root ->
                write(root, "mods/local.jar", "jar")
                write(root, "config/a.toml", "cfg")
                NetworkClient().use { network ->
                    assertMultiMcExport(store, instance, service(store, network))
                }
            }
        }

    private suspend fun assertMultiMcExport(
        store: LauncherStore,
        instance: GameInstance,
        service: ContentService,
    ) {
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
        ZipFile(target.toFile()).use { zip -> assertMultiMcArchive(zip) }
    }

    private fun assertMultiMcArchive(zip: ZipFile) {
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
            components.single { it.string("uid") == "net.minecraft" }.string("version"),
        )
        assertEquals(
            "47.2.0",
            components.single { it.string("uid") == "net.minecraftforge" }.string("version"),
        )
        val parsed = readPackManifest(zip, temporary.resolve("import"), "windows")
        assertEquals(metadata.name, parsed.request.name)
        assertEquals("1.20.1", parsed.request.gameVersion)
        assertEquals(LoaderType.Forge, parsed.request.loader)
        assertEquals("47.2.0", parsed.request.loaderVersion)
        assertEquals(".minecraft/", parsed.payloadPrefix)
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
}
