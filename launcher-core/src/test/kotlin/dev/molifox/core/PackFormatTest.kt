// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证整合包清单解析、预检与参数转换的行为与边界。

package dev.molifox.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeBytes
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackFormatTest : PackFormatFixtures() {
    @TempDir override lateinit var temporary: Path

    @Test
    fun `MCBBS preview supports canonical alternate and wrapped manifests offline`() =
        runBlocking {
            val store = store()
            NetworkClient().use { network ->
                val service = service(store, network)
                for (filename in listOf("mcbbs.packmeta", "manifest.json")) {
                    for (
                    prefix in
                    listOf("", "Pack/")
                    ) {
                        val preview =
                            service.previewPack(
                                pack(filename = filename, prefix = prefix),
                            )
                        assertEquals("茉狐测试包", preview.title)
                        assertEquals("1.20.1", preview.gameVersion)
                        assertEquals("1.0", preview.version)
                        assertEquals(1, preview.requiredFiles)
                        assertTrue(preview.summary.contains("test description"))
                        assertTrue(preview.summary.contains("2048 MB"))
                    }
                }
                val oldVersion = mcbbs().apply { addProperty("manifestVersion", 1) }
                assertEquals("1.20.1", service.previewPack(pack(oldVersion)).gameVersion)
                assertTrue(store.data.instances.isEmpty())
                assertTrue(store.cacheDirectory.listDirectoryEntries("pack-import-*").isEmpty())
            }
        }

    @Test
    fun `canonical MCBBS has priority over compatible CurseForge companion`() {
        for (
        (id, type) in
        mapOf(
            "fabric" to LoaderType.Fabric,
            "quilt" to LoaderType.Quilt,
            "forge" to LoaderType.Forge,
            "neoforge" to LoaderType.NeoForge,
        )
        ) {
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
                    pack(
                        mcbbs("fabric", "1"),
                        extra = mapOf("manifest.json" to gson.toJson(other)),
                    ),
                ) {}
            }
        }
        val otherMcbbs = mcbbs("forge", "1")
        assertFails {
            parsed(
                pack(
                    mcbbs("fabric", "1"),
                    extra = mapOf("manifest.json" to gson.toJson(otherMcbbs)),
                ),
            ) {}
        }
    }

    @Test
    fun `addon hashes are checked before creating instances`() =
        runBlocking {
            val store = store()
            NetworkClient().use { network ->
                val service = service(store, network)
                for (
                info in
                listOf(
                    mcbbs().apply {
                        array("files").first().asJsonObject.addProperty("hash", "invalid")
                    },
                    mcbbs().apply {
                        array("files").first().asJsonObject.addProperty("hash", "0".repeat(40))
                    },
                )
                ) {
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
            checkNotNull(
                assertFails { parsed(pack(files = files)) {} }.message,
            ).contains("config/test.txt"),
        )
        for (
        url in
        listOf(
            "http://example.com",
            "https://example.com",
            "https://raw.githubusercontent.com/a?token=secret",
        )
        ) {
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
            ),
        ) {
            assertEquals(
                "https://raw.githubusercontent.com/team/pack/main/overrides/config/test.txt",
                it.checkedFiles.single().url,
            )
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
        for (
        files in
        listOf(
            mapOf("config/test.txt" to "setting", "config" to "file"),
            mapOf("config/test.txt" to "setting", "config/Test.txt" to "other"),
            mapOf("../escape" to "bad"),
        )
        ) {
            assertFails { parsed(pack(files = files)) {} }
        }
        assertFails { parsed(pack(prefix = "../")) {} }
        assertFails {
            parsed(
                pack(
                    extra =
                        mapOf("Other/modpack.json" to gson.toJson(legacyManifest())),
                ),
            ) {}
        }
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
                    ),
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
                                    ),
                                ),
                            "files/modpack.json" to "not metadata",
                        ),
                    ),
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
    fun `old custom launch profiles cannot silently become vanilla`() {
        for (
        path in
        listOf("pack.json", "custom.json", "versions/custom/custom.json", "custom.jar")
        ) {
            assertFails {
                parsed(
                    pack(
                        legacyManifest(),
                        "modpack.json",
                        files =
                            mapOf(
                                path to
                                    "{\"mainClass\":" +
                                    "\"net.fabricmc.loader.impl.launch.knot.KnotClient\"}",
                            ),
                    ),
                ) {}
            }
        }
        assertFails {
            parsed(
                pack(
                    legacyManifest().apply { addProperty("mainClass", "custom.Main") },
                    "modpack.json",
                ),
            ) {}
        }
    }

    @Test
    fun `pack argument conversion preserves literal tokens and rejects managed flags`() {
        val values = listOf("-Dpath=C:\\folder with spaces", "value'quote", "value\"quote", "中文")
        assertEquals(values, splitArguments(packArgumentsText(values)))
        for (
        values in
        listOf(listOf(""), listOf("both'and\"quotes"), listOf("line\nbreak"))
        ) {
            assertFails {
                packArgumentsText(values)
            }
        }
        for (flag in listOf("-cp", "-jar", "-Xmx2G", "@args.txt")) {
            assertFails {
                PackLaunchOptions(listOf(flag), emptyList()).resolve(LauncherSettings(), "windows")
            }
        }
        for (flag in listOf("--accessToken", "--gameDir", "--width")) {
            assertFails {
                PackLaunchOptions(emptyList(), listOf(flag, "other"))
                    .resolve(LauncherSettings(), "windows")
            }
        }
    }

    @Test
    fun `nullable optional MCBBS fields keep their documented defaults`() {
        parsed(
            pack(
                mcbbs().apply {
                    for (field in listOf("libraries", "launchInfo", "settings", "serverInfo")) {
                        add(
                            field,
                            null,
                        )
                    }
                },
            ),
        ) {
            assertEquals("1.20.1", it.request.gameVersion)
            assertNull(it.launchOptions)
        }
    }
}
